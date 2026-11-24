"""Small local-only app-server WebSocket client (no credentials or approvals)."""

import base64
import hashlib
import json
import os
import socket
import struct
import time


class RpcError(RuntimeError):
    pass


class Rpc:
    def __init__(self, path, timeout=20, initialize_params=None):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(timeout)
        self.timeout = timeout
        self.sequence = 0
        try:
            self.sock.connect(str(path))
            self.stream = self.sock.makefile("rwb", buffering=0)
            key = base64.b64encode(os.urandom(16)).decode()
            self.stream.write(("GET / HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n"
                               "Connection: Upgrade\r\nSec-WebSocket-Version: 13\r\n"
                               f"Sec-WebSocket-Key: {key}\r\n\r\n").encode())
            if b" 101 " not in self.stream.readline(4096):
                raise ConnectionError("WebSocket upgrade refused")
            headers = {}
            for _ in range(64):
                line = self.stream.readline(4096)
                if line == b"\r\n":
                    break
                if not line:
                    raise ConnectionError("Incomplete handshake")
                name, value = line.decode().split(":", 1)
                headers[name.lower()] = value.strip()
            else:
                raise ConnectionError("Oversized handshake")
            accept = base64.b64encode(hashlib.sha1(
                (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
            if headers.get("sec-websocket-accept") != accept:
                raise ConnectionError("Invalid handshake")
            self.initialized = self.call("initialize", initialize_params or {
                "clientInfo": {"name": "andri-task-monitor", "version": "1"},
                "capabilities": {"experimentalApi": True},
            })
            self.send({"method": "initialized", "params": {}})
        except Exception:
            self.close()
            raise

    def close(self):
        if hasattr(self, "stream"):
            self.stream.close()
        self.sock.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def frame(self, payload, opcode=1):
        size = len(payload)
        prefix = bytes([0x80 | opcode])
        if size < 126:
            prefix += bytes([0x80 | size])
        elif size < 65536:
            prefix += b"\xfe" + struct.pack("!H", size)
        else:
            prefix += b"\xff" + struct.pack("!Q", size)
        mask = os.urandom(4)
        self.sock.sendall(prefix + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))

    def send(self, value):
        self.frame(json.dumps(value, ensure_ascii=False).encode())

    def exact(self, count):
        result = bytearray()
        while len(result) < count:
            part = self.stream.read(count - len(result))
            if not part:
                raise ConnectionError("Control connection closed")
            result.extend(part)
        return bytes(result)

    def receive(self, deadline):
        fragments = bytearray()
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("Control response timeout")
            self.sock.settimeout(remaining)
            first, second = self.exact(2)
            opcode, size = first & 15, second & 127
            if second & 128:
                raise ConnectionError("Unexpected masked frame")
            if size >= 126:
                size = int.from_bytes(self.exact(2 if size == 126 else 8), "big")
            if size + len(fragments) > 16 * 1024 * 1024:
                raise ConnectionError("Oversized control message")
            payload = self.exact(size)
            if opcode == 8:
                raise ConnectionError("Control connection closed")
            if opcode == 9:
                self.frame(payload, 10)
                continue
            if opcode == 10:
                continue
            if opcode not in (0, 1):
                raise ConnectionError("Unexpected frame type")
            fragments.extend(payload)
            if first & 128:
                return json.loads(fragments)

    def call(self, method, params):
        self.sequence += 1
        request_id = self.sequence
        self.send({"id": request_id, "method": method, "params": params})
        deadline = time.monotonic() + self.timeout
        while True:
            response = self.receive(deadline)
            if response.get("id") == request_id and "method" not in response:
                if "error" in response:
                    # Never include upstream messages, paths, prompts or credentials in logs.
                    raise RpcError(f"{method}: {response['error'].get('code', 'unknown')}")
                return response["result"]
            # Observe only. Server-side approval/question requests belong to the user.
