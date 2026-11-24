"""Local Unix WebSocket gateway: resume each chat on its existing writer.

New chats and global configuration stay on the mobile backend. Desktop-owned
chats use the desktop backend without claiming them on another app-server.
No prompts, responses, upstream error messages or credentials are logged.
"""

import argparse
import base64
from concurrent.futures import Future, TimeoutError as FutureTimeout
import hashlib
import json
import os
from pathlib import Path
import socket
import socketserver
import struct
import threading
import time

from task_monitor_rpc import Rpc, RpcError


MAX_MESSAGE = 16 * 1024 * 1024


class Client:
    """RFC 6455 framing for the existing SSH app-server proxy transport."""

    def __init__(self, sock):
        self.sock = sock
        self.stream = sock.makefile("rb", buffering=0)
        self.lock = threading.Lock()
        sock.settimeout(15)
        if not self.stream.readline(4096).startswith(b"GET / HTTP/1.1"):
            raise ValueError("Invalid WebSocket request")
        headers = {}
        for _ in range(64):
            line = self.stream.readline(4096)
            if line == b"\r\n":
                break
            if not line or len(line) >= 4096:
                raise ValueError("Invalid WebSocket headers")
            name, value = line.decode("ascii").split(":", 1)
            headers[name.lower()] = value.strip()
        else:
            raise ValueError("Oversized WebSocket headers")
        key = headers.get("sec-websocket-key", "")
        if (headers.get("upgrade", "").lower() != "websocket" or
                headers.get("sec-websocket-version") != "13" or
                "upgrade" not in [s.strip() for s in headers.get("connection", "").lower().split(",")] or
                len(base64.b64decode(key, validate=True)) != 16):
            raise ValueError("Invalid WebSocket handshake")
        accept = base64.b64encode(hashlib.sha1(
            (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest())
        sock.sendall(b"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                     b"Connection: Upgrade\r\nSec-WebSocket-Accept: " + accept + b"\r\n\r\n")
        sock.settimeout(None)

    def frame(self, payload, opcode=1):
        size = len(payload)
        prefix = bytes([0x80 | opcode])
        if size < 126:
            prefix += bytes([size])
        elif size < 65536:
            prefix += b"\x7e" + struct.pack("!H", size)
        else:
            prefix += b"\x7f" + struct.pack("!Q", size)
        with self.lock:
            self.sock.sendall(prefix + payload)

    def send(self, message):
        self.frame(json.dumps(message, ensure_ascii=False).encode())

    def exact(self, size):
        data = bytearray()
        while len(data) < size:
            chunk = self.stream.read(size - len(data))
            if not chunk:
                raise ConnectionError("Client closed")
            data.extend(chunk)
        return bytes(data)

    def receive(self):
        fragments = bytearray()
        fragmented = False
        while True:
            first, second = self.exact(2)
            opcode, size = first & 15, second & 127
            if first & 0x70 or not second & 0x80:
                raise ValueError("Invalid client frame")
            if size >= 126:
                size = int.from_bytes(self.exact(2 if size == 126 else 8), "big")
            if size + len(fragments) > MAX_MESSAGE:
                raise ValueError("Oversized client message")
            if opcode >= 8 and (size > 125 or not first & 128):
                raise ValueError("Invalid control frame")
            mask = self.exact(4)
            payload = bytes(b ^ mask[i % 4] for i, b in enumerate(self.exact(size)))
            if opcode == 8:
                raise ConnectionError("Client closed")
            if opcode == 9:
                self.frame(payload, 10)
                continue
            if opcode == 10:
                continue
            if opcode not in (0, 1) or (opcode == 0) != fragmented:
                raise ValueError("Invalid fragmented message")
            fragments.extend(payload)
            if first & 128:
                message = json.loads(fragments)
                if not isinstance(message, dict):
                    raise ValueError("Invalid RPC message")
                return message
            fragmented = True


class Backend:
    def __init__(self, session, name, path):
        self.session, self.name = session, name
        self.rpc = Rpc(path, initialize_params=session.initialize_params)
        self.pending = {}
        self.sequence = 0
        self.lock = threading.Lock()
        self.closed = False
        threading.Thread(target=self.read, daemon=True).start()

    def request(self, method, params):
        with self.lock:
            if self.closed:
                raise ConnectionError("Backend unavailable")
            self.sequence += 1
            request_id = "gateway:" + str(self.sequence)
            future = Future()
            self.pending[request_id] = future
            try:
                self.rpc.send({"id": request_id, "method": method, "params": params})
            except Exception:
                self.pending.pop(request_id, None)
                future.set_exception(ConnectionError("Backend unavailable"))
        return future

    def call(self, method, params):
        response = self.request(method, params).result(timeout=20)
        if "error" in response:
            raise ConnectionError("Backend query failed")
        return response["result"]

    def send(self, message):
        with self.lock:
            if self.closed:
                raise ConnectionError("Backend unavailable")
            self.rpc.send(message)

    def read(self):
        try:
            while True:
                message = self.rpc.receive(time.monotonic() + 86400 * 365)
                if "method" not in message:
                    with self.lock:
                        future = self.pending.pop(message.get("id"), None)
                    if future:
                        future.set_result(message)
                elif "id" in message:
                    # Different servers may issue the same request ID. Keep the
                    # origin in the ID so user answers return to exactly one.
                    message["id"] = self.session.server_request(self.name, message["id"])
                    self.session.client.send(message)
                else:
                    if message.get("method") == "serverRequest/resolved":
                        params = message.get("params", {})
                        mapped = self.session.resolve_server_request(self.name, params.get("requestId"))
                        if mapped is None:
                            continue
                        message["params"] = {**params, "requestId": mapped}
                    self.session.client.send(message)
        except (OSError, ValueError):
            pass
        finally:
            self.close()

    def close(self):
        with self.lock:
            if self.closed:
                return
            self.closed = True
            pending, self.pending = self.pending, {}
        # Shutdown before closing the buffered stream, waking its reader.
        try:
            self.rpc.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.rpc.close()
        for future in pending.values():
            future.set_exception(ConnectionError("Backend disconnected; request not replayed"))


class Session:
    def __init__(self, client, paths):
        self.client, self.paths = client, paths
        self.backends, self.owners = {}, {}
        self.initialize_params = None
        self.server_requests = {}
        self.server_answered = set()
        self.server_request_lock = threading.Lock()
        self.server_request_sequence = 0

    def server_request(self, name, original_id):
        with self.server_request_lock:
            for key, origin in self.server_requests.items():
                if origin == (name, original_id):
                    return key
            self.server_request_sequence += 1
            request_id = "gateway-server:" + str(self.server_request_sequence)
            self.server_requests[request_id] = (name, original_id)
        return request_id

    def resolve_server_request(self, name, original_id):
        with self.server_request_lock:
            matches = [key for key, origin in self.server_requests.items() if origin == (name, original_id)]
            for key in matches:
                del self.server_requests[key]
                self.server_answered.discard(key)
        return matches[-1] if matches else None

    def backend(self, name):
        backend = self.backends.get(name)
        if backend is None or backend.closed:
            backend = Backend(self, name, self.paths[name])
            self.backends[name] = backend
        return backend

    def route(self, method, params):
        thread_id = params.get("threadId")
        if not thread_id:
            return self.backend("mobile")
        if method != "thread/resume" and thread_id in self.owners:
            return self.backend(self.owners[thread_id])
        owners = []
        for name in self.paths:
            try:
                backend = self.backend(name)
                cursor = None
                while True:
                    page = backend.call("thread/loaded/list", {"limit": 100, "cursor": cursor})
                    if thread_id in page["data"]:
                        owners.append(name)
                        break
                    cursor = page.get("nextCursor")
                    if not cursor:
                        break
            except (OSError, ValueError, RpcError, FutureTimeout):
                # A missing optional desktop service must not prevent new mobile
                # work. Resuming a foreign writer still fails safely upstream.
                if name == "mobile":
                    raise
        if len(owners) > 1:
            raise ValueError("Ambiguous thread ownership")
        name = owners[0] if owners else "mobile"
        self.owners[thread_id] = name
        return self.backend(name)

    def error(self, request_id):
        self.client.send({"id": request_id, "error": {"code": -32001,
                          "message": "连接任务所在的服务失败；请重连后核对任务状态。请求未自动重发。"}})

    def complete(self, future, request_id, backend, method):
        try:
            response = future.result()
            if method in ("thread/start", "thread/fork", "thread/resume"):
                tid = response.get("result", {}).get("thread", {}).get("id")
                if tid:
                    self.owners[tid] = backend.name
            self.client.send({**response, "id": request_id})
        except (OSError, ValueError):
            try:
                self.error(request_id)
            except OSError:
                pass

    def handle(self, message):
        method = message.get("method")
        if not method:
            request_id = message.get("id")
            with self.server_request_lock:
                # Keep the mapping until serverRequest/resolved, so the phone
                # can dismiss a question answered here or on the desktop.
                origin = self.server_requests.get(request_id)
                if request_id in self.server_answered:
                    origin = None
                elif origin:
                    self.server_answered.add(request_id)
            if origin:
                name, original_id = origin
                # Do not open a new connection to answer an old approval.
                self.backends[name].send({**message, "id": original_id})
            return
        params = message.get("params") or {}
        if not isinstance(params, dict):
            raise ValueError("Invalid RPC parameters")
        if method == "initialize":
            if self.initialize_params is not None:
                raise ValueError("Already initialized")
            self.initialize_params = params
            self.client.send({"id": message["id"], "result": self.backend("mobile").rpc.initialized})
        elif method == "initialized":
            pass  # Each backend is initialized once by its connection.
        elif self.initialize_params is None:
            raise ValueError("Not initialized")
        elif "id" in message:
            try:
                backend = self.route(method, params)
                forwarded = params
                if method == "thread/resume" and "initialTurnsPage" in params:
                    # This app-server accepts the option but fails with
                    # "list_turns is not supported yet" after resuming a loaded
                    # thread. Omit the optional optimization; Android fetches
                    # thread/turns/list when the initial page is absent.
                    forwarded = {k: v for k, v in params.items() if k != "initialTurnsPage"}
                future = backend.request(method, forwarded)
                future.add_done_callback(lambda f: self.complete(f, message["id"], backend, method))
            except (OSError, ValueError, RpcError, FutureTimeout):
                self.error(message["id"])
        else:
            self.route(method, params).send(message)

    def close(self):
        for backend in list(self.backends.values()):
            backend.close()


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        session = client = None
        try:
            client = Client(self.request)
            session = Session(client, self.server.paths)
            while True:
                session.handle(client.receive())
        except (OSError, ValueError, KeyError, RpcError, FutureTimeout):
            pass
        finally:
            if session:
                session.close()
            if client:
                client.stream.close()


class Gateway(socketserver.ThreadingUnixStreamServer):
    daemon_threads = True

    def __init__(self, path, paths):
        self.paths = paths
        super().__init__(str(path), Handler)
        os.chmod(path, 0o600)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--listen", required=True)
    parser.add_argument("--mobile", required=True)
    parser.add_argument("--desktop", required=True)
    args = parser.parse_args()
    path = Path(args.listen)
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    # Refuse to replace a live listener or a symlink owned by a daemon.
    if path.is_symlink():
        raise RuntimeError("Remove the retired endpoint symlink before starting the gateway")
    if path.exists():
        probe = socket.socket(socket.AF_UNIX)
        try:
            probe.connect(str(path))
        except ConnectionRefusedError:
            path.unlink()
        else:
            raise RuntimeError("Gateway listener already active")
        finally:
            probe.close()
    with Gateway(path, {"mobile": args.mobile, "desktop": args.desktop}) as gateway:
        gateway.serve_forever()


if __name__ == "__main__":
    main()
