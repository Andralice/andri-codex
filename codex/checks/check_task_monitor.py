"""Installed app-server integration using a loopback fake model, never upstream."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "server"))
from task_monitor import Monitor
from task_monitor_rpc import Rpc, RpcError
from task_monitor_questions import MARKER, native_question

release = threading.Event()
requests = []
control_calls = 0
question_calls = set()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        global control_calls
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        requests.append(body)
        texts = []
        for item in body.get("input", []):
            if item.get("role") == "user":
                content = item.get("content", [])
                if isinstance(content, str):
                    texts.append(content)
                else:
                    texts.append("\n".join(i.get("text", "") for i in content))
        prompt = texts[-1] if texts else ""
        tool_item = None
        if MARKER in prompt:
            arguments = json.loads(prompt.rsplit("\n", 1)[1])
            question_id = arguments["questions"][0]["id"]
            if question_id not in question_calls:
                question_calls.add(question_id)
                tool_item = {"type": "function_call", "id": "fc_" + question_id, "call_id": "call_" + question_id,
                             "name": "request_user_input", "arguments": json.dumps(arguments, ensure_ascii=False), "status": "completed"}
                text = ""
            else:
                text = "已收到你的回答，后台会按对应事项处理。"
        elif "CONTROL_PREFERENCE_FIXTURE" in prompt:
            control_calls += 1
            if control_calls == 1:
                code = ('const t = ALL_TOOLS.find(t => t.name.endsWith("monitor_update_preferences")); '
                        'if (!t) throw Error("Missing private management tool"); '
                        'text(await tools[t.name]({changes:{allow_followups:false,ask_before:["deploy"]},'
                        'expected_revision:0,reason:"fixture human preference update"}));')
                tool_item = {"type": "custom_tool_call", "id": "ct_preferences", "call_id": "call_preferences",
                             "name": "exec", "namespace": "functions", "input": code, "status": "completed"}
                text = ""
            else:
                text = "已保存：只汇报，部署前先问你。"
        elif "监控事件编号：" in prompt:
            event_id = prompt.split("监控事件编号：", 1)[1].split("\n", 1)[0]
            payload = json.loads(prompt.split(event_id + "\n\n", 1)[1])
            ask = "需要晚晚确定验收范围" in payload["history_excerpt"][-1]["assistant"]
            complete = "已完成全部" in payload["history_excerpt"][-1]["assistant"]
            text = json.dumps({"event_id": event_id, "action": "ask_user" if ask else ("done" if complete else "follow_up"),
                               "summary": "原任务已完成" if complete else "还有已授权步骤未完成",
                               "next_prompt": "" if complete else "请完成原来已授权但未完成的最后一步，并如实报告。",
                               "question": "这项工作的验收范围需要补充哪一条？" if ask else "",
                               "remaining_work": [] if complete else [{
                                   "task": "本机fixture剩余步骤", "state": "blocked" if ask else "actionable",
                                   "reason": "必须先确定验收范围" if ask else "原已授权最后一步尚未执行",
                                   "authorization": "用户明确要求完成fixture全部步骤",
                               }]}, ensure_ascii=False)
            if 'MIXED_DEPENDENCIES_FIXTURE' in payload['history_excerpt'][-1]['user'] and not payload.get('review_recovery'):
                invalid = json.loads(text)
                invalid.update(action='ask_user', question='请补充人员证明', next_prompt='')
                invalid['remaining_work'].append({'task':'人员证明','state':'blocked','reason':'现有资料无证明','authorization':''})
                text = json.dumps(invalid)
            if 'RECOVERY_FIXTURE' in payload['history_excerpt'][-1]['user'] and not payload.get('review_recovery'):
                invalid=json.loads(text);invalid['event_id']='invalid_fixture_event';text=json.dumps(invalid)
        elif "【任务管家自动跟进" in prompt:
            text = "已完成全部任务。这里仅为本机 fixture，不代表实际产品功能。"
        elif "QUESTION_FIXTURE" in prompt:
            text = "需要晚晚确定验收范围"
        elif "BUSY_FIXTURE" in prompt:
            release.wait(25)
            text = "busy fixture released"
        else:
            text = "原请求还有一个明确授权的步骤未做，下一步需要继续完成。"
        item = tool_item or {"type": "message", "id": "msg_" + str(len(requests)), "role": "assistant",
                             "status": "completed", "content": [{"type": "output_text", "text": text, "annotations": []}]}
        response = {"id": "resp_" + str(len(requests)), "object": "response", "created_at": int(time.time()),
                    "model": body["model"], "status": "completed", "output": [item],
                    "usage": {"input_tokens": 10, "output_tokens": 10, "total_tokens": 20}}
        events = [
            ("response.created", {"type": "response.created", "response": {**response, "status": "in_progress", "output": []}}),
            ("response.output_item.added", {"type": "response.output_item.added", "output_index": 0, "item": item}),
            ("response.output_item.done", {"type": "response.output_item.done", "output_index": 0, "item": item}),
            ("response.completed", {"type": "response.completed", "response": response}),
        ]
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        try:
            for event, data in events:
                self.wfile.write(("event: " + event + "\ndata: " + json.dumps(data) + "\n\n").encode())
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass


def main():
    results = {}
    threads = []
    process = monitor = None
    temporary = tempfile.TemporaryDirectory(prefix="monitor-live-", dir=REPO / "codex")
    root = Path(temporary.name)
    from fixture_runtime import isolated_codex_env
    fixture_env = isolated_codex_env(root)
    from functools import partial
    from unittest.mock import patch
    from task_monitor_questions import native_question
    # The in-process monitor must read the isolated server's authoritative logs.
    session_reader = patch("task_monitor_questions.native_question", partial(
        native_question, session_root=Path(fixture_env["CODEX_HOME"]) / "sessions"))
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    path = root / "api.sock"
    provider = {"name": "monitor-local-fixture", "base_url": f"http://127.0.0.1:{server.server_port}/v1",
                "wire_api": "responses", "requires_openai_auth": False}
    config = {"model_provider": "monitor_fixture", "model_providers.monitor_fixture": provider,
              "features.apps": False, "features.memories": False, "features.shell_tool": False,
              "features.multi_agent": False}
    command = [shutil.which("codex"), "app-server", "--listen", "unix://" + str(path)]
    for key, value in config.items():
        literal = ("{" + ",".join(k + "=" + json.dumps(v) for k, v in value.items()) + "}"
                   if isinstance(value, dict) else json.dumps(value))
        command.extend(["-c", key + "=" + literal])
    try:
        session_reader.start()
        with (root / "server.log").open("w") as log:
            process = subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=log, start_new_session=True, env=fixture_env)
        deadline = time.monotonic() + 15
        while not path.exists():
            if time.monotonic() > deadline or process.poll() is not None:
                raise RuntimeError("Local fixture did not start; see private log")
            time.sleep(.05)
        with Rpc(path) as rpc:
            def new_thread():
                response = rpc.call("thread/start", {"cwd": str(root), "model": "gpt-6-luna",
                                    "modelProvider": "monitor_fixture", "config": config,
                                    "sandbox": "read-only", "approvalPolicy": "never"})
                assert response["modelProvider"] == "monitor_fixture", "Fixture cannot use upstream provider"
                tid = response["thread"]["id"]
                threads.append(tid)
                return tid

            supervisor, source = new_thread(), new_thread()
            rpc.call("turn/start", {"threadId": supervisor, "input": [{"type": "text", "text": "SUPERVISOR_READY"}]})
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                try:
                    state = rpc.call("thread/read", {"threadId": supervisor, "includeTurns": False})["thread"]["status"]
                except RpcError:
                    time.sleep(.1)
                    continue
                if state["type"] == "idle":
                    break
                time.sleep(.1)
            runtime = root / "runtime"
            runtime.mkdir()
            monitor_config = {"runtime": str(runtime), "sockets": [str(path)], "monitor_socket": str(path),
                              "monitor_thread": supervisor, "thread_ids": [source], "allow_followups": True}
            (runtime / "config.json").write_text(json.dumps(monitor_config))
            monitor = Monitor(monitor_config)
            monitor.since = int(time.time()) - 1
            monitor.store.put("started_at", monitor.since)
            rpc.call("turn/start", {"threadId": source, "input": [{"type": "text", "text": "请完成本机fixture的全部已授权步骤。"}]})
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                monitor.tick()
                states = [r[0] for r in monitor.store.db.execute("SELECT phase FROM jobs ORDER BY created")]
                if states == ["followed_up", "done"]:
                    break
                time.sleep(.15)
            else:
                raise AssertionError("Flow incomplete: " + repr([tuple(r) for r in monitor.store.db.execute("SELECT phase,note FROM jobs")]))
            results["real_protocol_complete_review_followup_complete"] = True
            before = len(requests)
            monitor.store.db.close()
            monitor = Monitor(monitor_config)
            monitor.tick()
            assert len(requests) == before == 5, "Restart triggered duplicate work"
            results["restart_does_not_repeat_reviews_or_followups"] = True
            results["review_only_uses_local_fake_provider"] = True
            assert monitor.reviewer != supervisor
            listed = rpc.call("thread/list", {"limit": 100, "archived": False})["data"]
            assert monitor.reviewer not in {t["id"] for t in listed}, "Internal reviewer polluted sidebar"
            threads.append(monitor.reviewer)
            results["durable_reviewer_hidden_from_normal_chat_list"] = True

            recovering=new_thread()
            monitor.base_config['thread_ids'].append(recovering)
            rpc.call('turn/start',{'threadId':recovering,'input':[{'type':'text','text':'RECOVERY_FIXTURE 完成原已授权步骤。'}]})
            deadline=time.monotonic()+25
            failed_job=None
            while time.monotonic()<deadline:
                monitor.tick()
                failed_job=monitor.store.db.execute("SELECT * FROM jobs WHERE thread_id=? AND phase='retry_wait'",(recovering,)).fetchone()
                if failed_job:break
                time.sleep(.15)
            assert failed_job and failed_job['note']=='invalid_review_output','Invalid review did not enter recovery'
            failed_turn=failed_job['review_turn'];event=failed_job['id']
            monitor.store.db.close();monitor=Monitor(monitor_config)
            before=len(requests);monitor.tick()
            assert len(requests)==before,'Restart bypassed backoff'
            assert monitor.store.db.execute('SELECT attempts FROM review_recovery WHERE event_id=?',(event,)).fetchone()[0]==1
            with monitor.store.db:monitor.store.db.execute('UPDATE review_recovery SET retry_at=0 WHERE event_id=?',(event,))
            deadline=time.monotonic()+30
            while time.monotonic()<deadline:
                monitor.tick()
                states=[r[0] for r in monitor.store.db.execute('SELECT phase FROM jobs WHERE thread_id=? ORDER BY created',(recovering,))]
                if states==['followed_up','done']:break
                time.sleep(.15)
            assert states==['followed_up','done'],states
            recovered=monitor.store.db.execute('SELECT * FROM jobs WHERE id=?',(event,)).fetchone()
            assert recovered['review_turn']!=failed_turn,'Retry reused the failed review instead of a fresh dispatch'
            source_turns=rpc.call('thread/turns/list',{'threadId':recovering,'limit':8,'itemsView':'full','sortDirection':'desc'})['data']
            assert len(source_turns)==2,'Recovery duplicated original followup'
            results['invalid_review_recovers_after_restart_backoff_and_closes_without_duplicate_followup']=True

            mixed=new_thread()
            monitor.base_config['thread_ids'].append(mixed)
            rpc.call('turn/start',{'threadId':mixed,'input':[{'type':'text','text':'MIXED_DEPENDENCIES_FIXTURE 核对现有原件；人员证明缺失时保留待确认，不猜填。'}]})
            deadline=time.monotonic()+25
            invalid_job=None
            while time.monotonic()<deadline:
                monitor.tick()
                invalid_job=monitor.store.db.execute("SELECT * FROM jobs WHERE thread_id=? AND phase='retry_wait'",(mixed,)).fetchone()
                if invalid_job:break
                time.sleep(.15)
            assert invalid_job and invalid_job['note']=='invalid_review_output','Mixed blocker closed task instead of corrective review'
            assert monitor.store.db.execute('SELECT COUNT(*) FROM questions').fetchone()[0]==0,'Inconsistent blocker was delivered to user'
            with monitor.store.db:monitor.store.db.execute('UPDATE review_recovery SET retry_at=0 WHERE event_id=?',(invalid_job['id'],))
            deadline=time.monotonic()+30
            while time.monotonic()<deadline:
                monitor.tick()
                states=[r[0] for r in monitor.store.db.execute('SELECT phase FROM jobs WHERE thread_id=? ORDER BY created',(mixed,))]
                if states==['followed_up','done']:break
                time.sleep(.15)
            assert states==['followed_up','done'],states
            source_turns=rpc.call('thread/turns/list',{'threadId':mixed,'limit':8,'itemsView':'full','sortDirection':'desc'})['data']
            assert len(source_turns)==2,'Corrective review duplicated original execution'
            results['mixed_blocker_rejected_then_independent_work_continues_once']=True

            asking = new_thread()
            monitor.base_config["thread_ids"].append(asking)
            rpc.call("turn/start", {"threadId": asking, "input": [{"type": "text", "text": "QUESTION_FIXTURE 请按范围验收。"}]})
            deadline = time.monotonic() + 25
            while time.monotonic() < deadline:
                monitor.tick()
                shown = monitor.store.db.execute("SELECT * FROM questions WHERE state='shown'").fetchone()
                if shown:
                    break
                time.sleep(.15)
            else:
                raise AssertionError("No actual native question: " + repr([tuple(r) for r in monitor.store.db.execute("SELECT state FROM questions")]))
            assert monitor.latest(rpc, supervisor)["status"] == "inProgress"
            assert "waitingOnUserInput" in rpc.call("thread/read", {"threadId": supervisor, "includeTurns": False})["thread"]["status"].get("activeFlags", [])
            results["question_is_real_native_card_not_only_internal_record"] = True
            # While the manager waits, a separate completed task still gets reviewed.
            independent = new_thread()
            monitor.base_config["thread_ids"].append(independent)
            rpc.call("turn/start", {"threadId": independent, "input": [{"type": "text", "text": "请完成本机fixture的全部已授权步骤。"}]})
            deadline = time.monotonic() + 25
            while time.monotonic() < deadline:
                monitor.tick()
                done = monitor.store.db.execute("SELECT 1 FROM jobs WHERE thread_id=? AND phase='followed_up'", (independent,)).fetchone()
                if done:
                    break
                time.sleep(.15)
            assert done, "Pending human answer blocked independent review"
            assert len(question_calls) == 1, "Unanswered question was duplicated"
            results["unanswered_question_does_not_block_background_review"] = True
            monitor.store.db.close()
            monitor = Monitor(monitor_config)
            monitor.tick()
            assert monitor.store.db.execute("SELECT COUNT(*) FROM questions").fetchone()[0] == 1
            assert monitor.store.db.execute("SELECT state FROM questions").fetchone()[0] == 'shown', "Restart withdrew unanswered question unexpectedly"
            # Phone/desktop reconnect uses the same native replay and answer format.
            with Rpc(path) as reconnect:
                reconnect.send({"id": 10000, "method": "thread/resume", "params": {"threadId": supervisor, "excludeTurns": True}})
                deadline = time.monotonic() + 10
                pending = next((f for f in reconnect.notifications if f.get("method") == "item/tool/requestUserInput"), None)
                while pending is None and time.monotonic() < deadline:
                    frame = reconnect.receive(deadline)
                    if frame.get("method") == "item/tool/requestUserInput":
                        pending = frame
                        break
                assert pending, "Question not replayed on reconnect"
                qid = pending["params"]["questions"][0]["id"]
                reconnect.send({"id": pending["id"], "result": {"answers": {qid: {"answers": ["暂不处理"]}}}})
                deadline = time.monotonic() + 15
                while time.monotonic() < deadline:
                    monitor.tick()
                    if monitor.store.db.execute("SELECT state FROM questions").fetchone()[0] == "answered":
                        break
                    time.sleep(.15)
            row = monitor.store.db.execute("SELECT * FROM jobs WHERE id=?", (shown["event_id"],)).fetchone()
            assert row["phase"] == "done" and row["note"] == "human_declined", "Native reply did not resolve exact event"
            assert len(question_calls) == 1
            results["restart_reconnect_replays_question_and_real_answer_resolves_bound_event"] = True
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                if monitor.latest(rpc, supervisor)["status"] == "completed":
                    break
                time.sleep(.1)


            # Loaded sessions retain their initial tool inventory. Reload only the supervisor.
            rpc.call("thread/archive", {"threadId": supervisor})
            rpc.call("thread/unarchive", {"threadId": supervisor})
            monitor.configure_chat(rpc)
            # Opening on either client supplies no custom config. The manager's
            # dedicated project config must retain its private management tools.
            rpc.call("thread/resume", {"threadId": supervisor, "excludeTurns": True})
            inventory = rpc.call("mcpServerStatus/list", {"threadId": supervisor,
                                 "serverName": "andri_monitor", "detail": "toolsAndAuthOnly", "limit": 10})
            assert any(s.get("name") == "andri_monitor" and len(s.get("tools", {})) == 8
                       for s in inventory["data"]), "Plain resume lost manager tools"
            results["plain_client_resume_preserves_private_management_tools"] = True

            control_turn = rpc.call("turn/start", {"threadId": supervisor,
                     "input": [{"type": "text", "text": "CONTROL_PREFERENCE_FIXTURE：以后只汇报，部署前先问我。"}]})["turn"]["id"]
            deadline = time.monotonic() + 20
            while time.monotonic() < deadline:
                latest = monitor.latest(rpc, supervisor)
                if latest and latest["id"] == control_turn and latest["status"] == "completed":
                    break
                time.sleep(.1)
            preferences_path = runtime / "preferences.json"
            assert preferences_path.exists(), "Conversation tool must actually persist the preference"
            preferences = json.loads(preferences_path.read_text())
            assert preferences["values"]["allow_followups"] is False
            assert preferences["values"]["ask_before"] == ["deploy"]
            assert control_calls == 2, "A real tool result must precede the acknowledgement"
            monitor.tick()
            assert monitor.config["allow_followups"] is False
            assert monitor.config["preference_revision"] == 1
            results["natural_conversation_executes_control_and_next_tick_applies_preferences"] = True
            monitor.store.db.close()
            monitor = Monitor(monitor_config)
            assert monitor.config["allow_followups"] is False
            results["conversation_preferences_survive_restart"] = True

            busy = new_thread()
            rpc.call("turn/start", {"threadId": busy, "input": [{"type": "text", "text": "BUSY_FIXTURE"}]})
            queued = rpc.call("thread/queue/add", {"threadId": busy, "clientUserMessageId": "fixture-race",
                              "input": [{"type": "text", "text": "Do not steer the existing turn"}]})["queuedSubmission"]
            try:
                rpc.call("thread/queue/start", {"threadId": busy, "queuedSubmissionId": queued["id"]})
            except RpcError:
                results["queue_start_rejects_active_turn_instead_of_steering"] = True
            else:
                raise AssertionError("Native queue/start unexpectedly accepted an active turn")
            release.set()
        print(json.dumps(results, ensure_ascii=False, indent=2))
    finally:
        session_reader.stop()
        release.set()
        if monitor:
            monitor.store.db.close()
        if path.exists():
            try:
                with Rpc(path) as rpc:
                    for tid in reversed(threads):
                        try:
                            rpc.call("thread/archive", {"threadId": tid})
                        except (OSError, RpcError):
                            pass
            except OSError:
                pass
        if process and process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=10)
        server.shutdown()
        if (root / "server.log").exists():
            shutil.copyfile(root / "server.log", REPO / "codex/task-monitor/fixture-server.log")
        output = REPO / "codex/task-monitor/check-result.json"
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n")
        if len(results) < 6:
            # Preserve diagnostics inside codex on failure, without printing any request bodies.
            shutil.copyfile(root / "server.log", output.with_suffix(".log"))
            diagnostic = [item.get("output") for body in requests for item in body.get("input", [])
                          if "output" in item and item.get("type", "").endswith("output")]
            print("local_fixture_tool_results", json.dumps(diagnostic[-3:], ensure_ascii=False)[:5000])
        temporary.cleanup()


if __name__ == "__main__":
    main()
