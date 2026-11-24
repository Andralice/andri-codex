"""Two real app-servers, one phone gateway and a local model; no upstream calls."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from contextlib import nullcontext
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import threading
import time

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "server"))
from task_monitor_rpc import Rpc, RpcError
from thread_gateway import Gateway, Session


requests = []


class Model(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        requests.append(body)
        answered = any(i.get("type") == "function_call_output" for i in body.get("input", []))
        users = [item for item in body.get("input", []) if item.get("role") == "user"]
        bootstrap = bool(users and "GATEWAY_BOOTSTRAP" in json.dumps(users[-1]))
        if answered or bootstrap:
            item = {"type": "message", "id": "msg_done", "role": "assistant", "status": "completed",
                    "content": [{"type": "output_text", "text": "fixture answer received", "annotations": []}]}
        else:
            item = {"type": "function_call", "id": "fc_question", "call_id": "call_question",
                    "name": "request_user_input", "status": "completed", "arguments": json.dumps({"questions": [{
                        "id": "confirm", "header": "Confirm", "question": "Fixture question?",
                        "options": [{"label": "Continue", "description": "Continue fixture"},
                                    {"label": "Stop", "description": "Stop fixture"}]}]})}
        response = {"id": "resp_fixture", "object": "response", "created_at": int(time.time()),
                    "model": body["model"], "status": "completed", "output": [item],
                    "usage": {"input_tokens": 10, "output_tokens": 5, "total_tokens": 15}}
        events = [
            ("response.created", {"type": "response.created", "response": {**response, "status": "in_progress", "output": []}}),
            ("response.output_item.added", {"type": "response.output_item.added", "output_index": 0, "item": item}),
            ("response.output_item.done", {"type": "response.output_item.done", "output_index": 0, "item": item}),
            ("response.completed", {"type": "response.completed", "response": response}),
        ]
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        for event, data in events:
            self.wfile.write(("event: " + event + "\ndata: " + json.dumps(data) + "\n\n").encode())
            self.wfile.flush()


def until(client, method, observed=None):
    deadline = time.monotonic() + 20
    while True:
        message = client.receive(deadline)
        if observed is not None:
            observed.append(message)
        if message.get("method") == method:
            return message


def main():
    results = {}
    root = Path(tempfile.mkdtemp(prefix="gateway-check-", dir=REPO / "codex"))
    model = ThreadingHTTPServer(("127.0.0.1", 0), Model)
    threading.Thread(target=model.serve_forever, daemon=True).start()
    paths = {name: str(root / (name + ".sock")) for name in ("mobile", "desktop")}
    config = {"model_provider": "gateway_fixture", "model_providers.gateway_fixture": {
        "name": "local gateway fixture", "base_url": f"http://127.0.0.1:{model.server_port}/v1",
        "wire_api": "responses", "requires_openai_auth": False}, "features.apps": False,
        "features.memories": False, "features.shell_tool": False, "features.multi_agent": False}
    processes, threads, anchors = [], [], []
    gateway = client = None
    try:
        for name, path in paths.items():
            command = ["codex", "app-server", "--listen", "unix://" + path]
            for key, value in config.items():
                literal = ("{" + ",".join(k + "=" + json.dumps(v) for k, v in value.items()) + "}"
                           if isinstance(value, dict) else json.dumps(value))
                command.extend(["-c", key + "=" + literal])
            if name == "mobile":
                command = [sys.executable, "-B", str(REPO / "server/question_policy.py"), "--codex",
                           str(Path.home() / ".local/bin/codex"), "--catalog", str(root / "catalog.json"),
                           "--run", *command[1:]]
            with (root / (name + ".log")).open("w") as log:
                processes.append(subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=log, start_new_session=True))
        deadline = time.monotonic() + 20
        while not all(Path(p).exists() for p in paths.values()):
            assert time.monotonic() < deadline, "Fixture backends failed to start"
            time.sleep(.05)
        tids = {}
        for name, path in paths.items():
            rpc = Rpc(path)
            anchors.append(rpc)
            with nullcontext(rpc):
                result = rpc.call("thread/start", {"cwd": str(root), "model": "gpt-6-luna",
                                  "modelProvider": "gateway_fixture", "config": config,
                                  "sandbox": "read-only", "approvalPolicy": "never"})
                assert result["modelProvider"] == "gateway_fixture"
                tids[name] = result["thread"]["id"]
                threads.append((path, tids[name]))
                rpc.call("turn/start", {"threadId": tids[name], "input": [{"type": "text", "text": "GATEWAY_BOOTSTRAP"}]})
                deadline = time.monotonic() + 10
                while True:
                    try:
                        if rpc.call("thread/read", {"threadId": tids[name], "includeTurns": False})["thread"]["status"]["type"] == "idle":
                            break
                    except RpcError:
                        pass
                    assert time.monotonic() < deadline
                    time.sleep(.05)
        endpoint = root / "gateway.sock"
        gateway = Gateway(endpoint, paths)
        threading.Thread(target=gateway.serve_forever, daemon=True).start()
        client = Rpc(endpoint, initialize_params={"clientInfo": {"name": "gateway-android-fixture", "version": "1"},
                                                "capabilities": {"experimentalApi": True}})
        # Match Android's resume, including the initial full turns page.
        def resume(tid):
            return client.call("thread/resume", {"threadId": tid, "excludeTurns": True,
                "initialTurnsPage": {"limit": 8, "itemsView": "full", "sortDirection": "desc"}})
        for name in ("desktop", "mobile", "desktop"):
            assert resume(tids[name])["thread"]["id"] == tids[name]
            deadline = time.monotonic() + 5
            while True:
                status = client.call("thread/read", {"threadId": tids[name], "includeTurns": False})["thread"]["status"]
                if status["type"] == "idle":
                    break
                assert time.monotonic() < deadline, (name, status)
                time.sleep(.05)
        with Rpc(paths["mobile"]) as rpc:
            assert tids["desktop"] not in rpc.call("thread/loaded/list", {"limit": 100})["data"]
        with Rpc(paths["desktop"]) as rpc:
            assert tids["mobile"] not in rpc.call("thread/loaded/list", {"limit": 100})["data"]
        results["resume_existing_writer_without_foreign_claim"] = True
        results["switch_desktop_mobile_desktop"] = True
        new = client.call("thread/start", {"cwd": str(root), "model": "gpt-6-luna",
                         "modelProvider": "gateway_fixture", "config": config,
                         "sandbox": "read-only", "approvalPolicy": "never"})
        tid = new["thread"]["id"]
        threads.append((paths["mobile"], tid))
        with Rpc(paths["mobile"]) as rpc:
            assert tid in rpc.call("thread/loaded/list", {"limit": 100})["data"]
        results["new_chats_remain_mobile"] = True
        # Exercise question traffic on both origins over the same phone connection.
        for question_tid in (tids["desktop"], tids["mobile"]):
            resume(question_tid)
            client.send({"id": 800, "method": "turn/start", "params": {
                "threadId": question_tid, "input": [{"type": "text", "text": "Ask fixture question."}]}})
            question = until(client, "item/tool/requestUserInput")
            assert question["params"]["threadId"] == question_tid
            assert question["id"].startswith("gateway-server:")
            count = len(requests)
            time.sleep(.2)
            assert len(requests) == count, "Question automatically answered"
            client.close()
            client = Rpc(endpoint)
            client.send({"id": 801, "method": "thread/resume", "params": {"threadId": question_tid, "excludeTurns": True}})
            question = until(client, "item/tool/requestUserInput")
            client.send({"id": question["id"], "result": {"answers": {"confirm": {"answers": ["Continue"]}}}})
            observed = []
            assert until(client, "turn/completed", observed)["params"]["turn"]["status"] == "completed"
            assert any(m.get("method") == "serverRequest/resolved" and
                       m["params"]["requestId"] == question["id"] for m in observed)
        results["questions_answers_and_reconnect_on_both_origins"] = True
        def names(tools):
            for tool in tools:
                if "name" in tool:
                    yield tool["name"]
                yield from names(tool.get("tools", []))
        mobile_tools = list(names(requests[-2].get("tools", [])))
        for item in requests[-2].get("input", []):
            if item.get("type") == "additional_tools":
                mobile_tools.extend(names(item["tools"]))
        assert "request_user_input" in mobile_tools
        assert "request_user_input_async" not in mobile_tools
        assert "send_user_message_async" not in mobile_tools
        results["mobile_blocking_question_policy_preserved"] = True
        # Explicitly force colliding server IDs: answers must reach only their origin.
        class FakeClient:
            def send(self, _):
                pass
        class FakeBackend:
            def __init__(self):
                self.messages = []
            def send(self, message):
                self.messages.append(message)
        session = Session(FakeClient(), paths)
        session.backends = {name: FakeBackend() for name in paths}
        ids = [session.server_request(name, 42) for name in paths]
        for request_id in reversed(ids):
            session.handle({"id": request_id, "result": {"ok": True}})
            session.handle({"id": request_id, "result": {"ok": True}})
        for backend in session.backends.values():
            assert backend.messages == [{"id": 42, "result": {"ok": True}}]
        session.handle({"id": "gateway-server:forged", "result": {"ok": True}})
        assert all(len(b.messages) == 1 for b in session.backends.values())
        results["colliding_server_request_ids_are_isolated"] = True
        print(json.dumps(results, indent=2))
        (root / "results.json").write_text(json.dumps(results, indent=2))
    finally:
        (root / "requests.json").write_text(json.dumps(requests, ensure_ascii=False, indent=2))
        if client:
            client.close()
        if gateway:
            gateway.shutdown()
            gateway.server_close()
        for path, tid in threads:
            try:
                with Rpc(path) as rpc:
                    rpc.call("thread/archive", {"threadId": tid})
            except Exception:
                pass
        for anchor in anchors:
            anchor.close()
        for process in reversed(processes):
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
        model.shutdown()


if __name__ == "__main__":
    main()
