"""Configure mobile Codex to wait for submitted answers instead of async defaults.

The installed Codex catalog calls the async tool ``send_user_message_async``
on some models and ``request_user_input_async`` on others. Both register the
same non-waiting handler. Keep the rest of each model's metadata intact.
"""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import tempfile


ASYNC_QUESTION_TOOLS = frozenset({"request_user_input_async", "send_user_message_async"})


def blocking_catalog(catalog):
    # JSON round trip gives us an independent copy without changing other fields.
    result = json.loads(json.dumps(catalog))
    models = result.get("models")
    if not isinstance(models, list) or not models:
        raise ValueError("The installed model catalog must contain models")
    for model in models:
        tools = model.get("experimental_supported_tools")
        if tools is not None:
            if not isinstance(tools, list):
                raise ValueError("Invalid experimental_supported_tools")
            model["experimental_supported_tools"] = [
                tool for tool in tools if tool not in ASYNC_QUESTION_TOOLS
            ]
    return result


def write_catalog(codex, output):
    installed = subprocess.run(
        [codex, "debug", "models", "--bundled"],
        check=True, capture_output=True, text=True, timeout=30,
    )
    catalog = blocking_catalog(json.loads(installed.stdout))
    output = Path(output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".question-catalog-", dir=output.parent)
    try:
        with os.fdopen(fd, "w") as stream:
            json.dump(catalog, stream, ensure_ascii=False)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, output)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return catalog


class RpcConnection:
    """Local app-server control connection; never answers server requests."""

    def __init__(self, path):
        self.socket = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.socket.settimeout(30)
        self.socket.connect(str(path))
        self.stream = self.socket.makefile("rwb", buffering=0)
        key = base64.b64encode(os.urandom(16)).decode()
        self.stream.write(("GET / HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n"
                           "Connection: Upgrade\r\nSec-WebSocket-Version: 13\r\n"
                           f"Sec-WebSocket-Key: {key}\r\n\r\n").encode())
        status = self.stream.readline()
        if b" 101 " not in status:
            raise ConnectionError("App-server refused the WebSocket handshake")
        headers = {}
        while True:
            line = self.stream.readline()
            if not line:
                raise ConnectionError("Incomplete WebSocket handshake")
            if line == b"\r\n":
                break
            name, value = line.decode().split(":", 1)
            headers[name.lower()] = value.strip()
        expected = base64.b64encode(hashlib.sha1(
            (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()
        ).digest()).decode()
        if headers.get("sec-websocket-accept") != expected:
            raise ConnectionError("Invalid WebSocket handshake")
        self.request_id = 0
        self.notifications = []
        self.call("initialize", {
            "clientInfo": {"name": "andri-question-policy", "version": "1"},
            "capabilities": {"experimentalApi": True},
        })
        self.send({"method": "initialized", "params": {}})

    def send(self, message):
        self._send_frame(json.dumps(message).encode(), 1)

    def _send_frame(self, payload, opcode):
        length = len(payload)
        if length < 126:
            size = bytes([0x80 | length])
        elif length < 65536:
            size = b"\xfe" + struct.pack("!H", length)
        else:
            size = b"\xff" + struct.pack("!Q", length)
        mask = os.urandom(4)
        masked = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
        self.socket.sendall(bytes([0x80 | opcode]) + size + mask + masked)

    def _read_exact(self, size):
        result = bytearray()
        while len(result) < size:
            part = self.stream.read(size - len(result))
            if not part:
                raise ConnectionError("App-server closed the control connection")
            result.extend(part)
        return bytes(result)

    def receive(self):
        fragments = bytearray()
        while True:
            first, second = self._read_exact(2)
            opcode, length = first & 15, second & 127
            if second & 128:
                raise ConnectionError("Unexpected masked server frame")
            if length >= 126:
                length = int.from_bytes(self._read_exact(2 if length == 126 else 8), "big")
            if length > 16 * 1024 * 1024:
                raise ConnectionError("Oversized control response")
            payload = self._read_exact(length)
            if opcode == 8:
                raise ConnectionError("App-server closed the control connection")
            if opcode == 9:
                self._send_frame(payload, 10)
                continue
            if opcode == 10:
                continue
            if opcode not in (0, 1):
                raise ConnectionError("Unexpected control frame")
            fragments.extend(payload)
            if len(fragments) > 16 * 1024 * 1024:
                raise ConnectionError("Oversized control response")
            if first & 128:
                return json.loads(fragments)

    def call(self, method, params):
        self.request_id += 1
        request_id = self.request_id
        self.send({"id": request_id, "method": method, "params": params})
        while True:
            message = self.receive()
            if message.get("id") == request_id and "method" not in message:
                if "error" in message:
                    raise RuntimeError(f"{method} failed: {message['error'].get('code')}")
                return message["result"]
            self.notifications.append(message)

    def close(self):
        self.stream.close()
        self.socket.close()


def policy_edits(catalog_path):
    return [
        {"keyPath": "model_catalog_json", "value": str(Path(catalog_path).resolve()), "mergeStrategy": "replace"},
        {"keyPath": "features.default_mode_request_user_input", "value": True, "mergeStrategy": "replace"},
        {"keyPath": "tools.experimental_request_user_input.enabled", "value": True, "mergeStrategy": "replace"},
    ]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--codex", required=True)
    parser.add_argument("--catalog", required=True)
    parser.add_argument("--apply-socket", help="Persist and hot-reload the policy in a running app-server")
    parser.add_argument("--run", nargs=argparse.REMAINDER,
                        help="Regenerate the catalog and replace this process with Codex")
    args = parser.parse_args()
    if args.apply_socket and args.run:
        parser.error("--apply-socket and --run cannot be combined")
    catalog = write_catalog(args.codex, args.catalog)
    if args.run:
        overrides = []
        for edit in policy_edits(args.catalog):
            overrides.extend(["-c", edit["keyPath"] + "=" + json.dumps(edit["value"])])
        os.execv(args.codex, [args.codex, *overrides, *args.run])
    if args.apply_socket:
        connection = RpcConnection(args.apply_socket)
        try:
            result = connection.call("config/batchWrite", {
                "edits": policy_edits(args.catalog), "reloadUserConfig": True,
            })
            print(json.dumps({"policyApplied": True, "models": len(catalog["models"]),
                              "writeStatus": result.get("status"), "restartRequired": True}))
        finally:
            connection.close()
    else:
        print(json.dumps({"models": len(catalog["models"])}))


if __name__ == "__main__":
    main()
