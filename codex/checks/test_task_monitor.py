"""Durability and execution boundaries; no model or network calls."""

import json
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "server"))
from task_monitor import Monitor, decision, final_text
from task_monitor_preferences import save, visible, in_scope
from task_monitor_control import resolve
from task_monitor_mcp import execute


def turn(tid="source-turn", status="completed", text="未完成已授权的事项"):
    return {"id": tid, "status": status, "completedAt": time.time(), "startedAt": time.time() - 5,
            "items": [{"type": "userMessage", "content": [{"type": "text", "text": "请完成工作"}]},
                      {"type": "agentMessage", "phase": "final_answer", "text": text}]}


class Connection:
    def __init__(self, model, endpoint):
        self.model, self.endpoint = model, endpoint

    def __enter__(self):
        return self

    def __exit__(self, *_):
        pass

    def call(self, method, params):
        m = self.model
        m.calls.append((self.endpoint, method, params))
        tid = params.get("threadId", "")
        if method == "thread/loaded/list":
            return {"data": [t for t in m.threads if not (t == "monitor" and self.endpoint == "other")], "nextCursor": None}
        if method == "thread/list":
            return {"data": [t for t in m.threads.values() if t["id"] != "reviewer"], "nextCursor": None}
        if method == "thread/fork":
            m.threads["reviewer"] = {**m.threads[tid], "id": "reviewer", "status": {"type": "idle"}}
            m.turns["reviewer"] = []
            return {"thread": m.threads["reviewer"]}
        if method == "thread/read":
            result = dict(m.threads[tid])
            if self.endpoint == "other" and m.other_active:
                result["status"] = {"type": "active", "activeFlags": ["waitingOnUserInput"]}
            return {"thread": result}
        if method == "thread/turns/list":
            return {"data": m.turns.get(tid, [])[:params["limit"]], "nextCursor": None}
        if method == "thread/queue/list":
            return {"data": m.queue}
        if method == "thread/queue/add":
            item = {"id": "queued", **params}
            m.queue.append(item)
            if m.lose_queue_ack:
                raise ConnectionError("ack lost")
            return {"queuedSubmission": item}
        if method == "thread/queue/delete":
            m.queue = [q for q in m.queue if q["id"] != params["queuedSubmissionId"]]
            return {}
        if method == "thread/queue/start":
            item = m.queue.pop(0)
            followup = turn("followup-turn", text="完成")
            followup["items"][0]["content"] = item["input"]
            m.turns[tid].insert(0, followup)
            if m.lose_start_ack:
                raise ConnectionError("start ack lost")
            return {"turn": followup}
        if method == "turn/interrupt":
            for item in m.turns.get(tid, []):
                if item["id"] == params["turnId"]:
                    item["status"] = "interrupted"
            return {}
        if method == "thread/resume":
            return {"thread": m.threads[tid]}
        if method == "thread/settings/update":
            return {}
        if method == "turn/start":
            value = json.loads(m.review)
            value.setdefault("remaining_work", [] if value["action"] == "done" else [{
                "task": "完成原请求", "state": "blocked" if value["action"] == "ask_user" else "actionable",
                "reason": "缺少用户确认的验收标准" if value["action"] == "ask_user" else "已知的最后一步尚未执行",
                "authorization": "原用户要求完成工作",
            }])
            event_id = params["input"][0]["text"].split("监控事件编号：", 1)[1].split("\n", 1)[0]
            value["event_id"] = event_id
            reviewed = turn("review-turn", text=json.dumps(value))
            reviewed["items"][0]["content"] = params["input"]
            m.turns[tid].insert(0, reviewed)
            if m.lose_review_ack:
                raise ConnectionError("review ack lost")
            return {"turn": reviewed}
        raise AssertionError(method)


class Model:
    def __init__(self):
        self.calls = []
        self.queue = []
        self.other_active = False
        self.lose_queue_ack = False
        self.lose_start_ack = False
        self.lose_review_ack = False
        self.review = json.dumps({"event_id": "", "action": "follow_up", "summary": "尚有已授权事项未完成",
                                  "next_prompt": "继续原请求中尚未完成的步骤", "question": ""})
        self.threads = {tid: {"id": tid, "name": tid, "cwd": str(REPO),
                              "updatedAt": time.time(), "status": {"type": "idle"}} for tid in ("source", "monitor")}
        self.turns = {"source": [turn()], "monitor": []}

    def connect(self, endpoint):
        return Connection(self, endpoint)


class MonitorChecks(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="monitor-unit-", dir=REPO / "codex")
        self.model = Model()
        self.config = {"runtime": self.directory.name, "sockets": ["source-socket", "other"],
                       "monitor_socket": "source-socket", "monitor_thread": "monitor", "allow_followups": True}
        self.monitor = Monitor(self.config, self.model.connect)
        self.monitor.since = time.time() - 60

    def tearDown(self):
        self.monitor.store.db.close()
        self.directory.cleanup()

    def job(self):
        return self.monitor.store.db.execute("SELECT * FROM jobs ORDER BY created LIMIT 1").fetchone()

    def review_and_decide(self):
        self.monitor.scan()
        self.monitor.work()  # send review
        self.monitor.work()  # collect decision

    def test_done_review_never_resumes_or_sends_to_original(self):
        value = json.loads(self.model.review)
        value["action"], value["next_prompt"] = "done", ""
        self.model.review = json.dumps(value)
        self.review_and_decide()
        self.monitor.work()
        self.assertEqual(self.job()["phase"], "done")
        self.assertFalse(any(p.get("threadId") == "source" and method in {"thread/resume", "turn/start", "thread/queue/add"}
                             for _, method, p in self.model.calls))

    def test_historical_completion_is_not_replayed(self):
        self.model.turns["source"][0]["completedAt"] = self.monitor.since - 1
        self.monitor.scan()
        self.assertIsNone(self.job())

    def test_repeated_scan_and_restart_preserve_unique_event(self):
        self.monitor.scan()
        self.monitor.scan()
        self.monitor.store.db.close()
        self.monitor = Monitor(self.config, self.model.connect)
        self.monitor.since = time.time() - 60
        self.monitor.scan()
        self.assertEqual(self.monitor.store.db.execute("SELECT COUNT(*) FROM jobs").fetchone()[0], 1)

    def test_lost_review_ack_recovers_without_second_model_call(self):
        self.model.lose_review_ack = True
        self.monitor.scan()
        self.monitor.work()
        self.assertEqual(self.job()["phase"], "review_sending")
        self.monitor.work()
        self.assertEqual(self.job()["phase"], "decided")
        self.assertEqual(sum(method == "turn/start" for _, method, _ in self.model.calls), 1)

    def test_cross_instance_active_task_and_pending_question_prevent_followup(self):
        self.review_and_decide()
        self.model.other_active = True
        self.monitor.work()
        self.assertEqual(self.job()["phase"], "decided")
        self.assertEqual(self.model.queue, [])

    def test_new_user_turn_supersedes_followup(self):
        self.review_and_decide()
        self.model.turns["source"].insert(0, turn("new-user-turn"))
        self.monitor.work()
        self.assertEqual(self.job()["phase"], "superseded")

    def test_followup_uses_native_queue_and_records_chain_depth(self):
        self.review_and_decide()
        self.monitor.work()
        self.assertEqual(self.job()["phase"], "followup_queued")
        self.monitor.work()
        self.assertEqual(self.job()["phase"], "followed_up")
        depth = self.monitor.store.db.execute("SELECT depth FROM continuations WHERE turn_id='followup-turn'").fetchone()[0]
        self.assertEqual(depth, 1)
        self.assertFalse(any(method == "turn/start" and p["threadId"] == "source" for _, method, p in self.model.calls))

    def test_lost_followup_start_ack_is_reconciled_without_restart(self):
        self.review_and_decide()
        self.monitor.work()
        self.model.lose_start_ack = True
        self.monitor.work()
        self.assertEqual(self.job()["phase"], "followup_starting")
        self.monitor.work()
        self.assertEqual(self.job()["phase"], "followed_up")
        self.assertEqual(sum(method == "thread/queue/start" for _, method, _ in self.model.calls), 1)

    def test_user_work_arriving_after_queue_creation_removes_only_our_item(self):
        self.review_and_decide()
        self.monitor.work()
        self.model.turns["source"].insert(0, turn("user-arrived"))
        self.monitor.work()
        self.assertEqual(self.job()["phase"], "superseded")
        self.assertEqual(self.model.queue, [])
        self.assertFalse(any(method == "thread/queue/start" for _, method, _ in self.model.calls))

    def test_assistant_json_cannot_be_used_as_human_authorization(self):
        self.review_and_decide()
        job = self.job()
        self.monitor.store.change(job, phase="needs_attention", note="user_decision_required")
        answer = turn("human-answer", text=job["result"])
        answer["startedAt"] = time.time()
        answer["items"][0]["content"] = [{"type": "text", "text": "你怎么看？"}]
        self.model.turns["monitor"].insert(0, answer)
        self.monitor.collect_user_replies()
        self.assertEqual(self.job()["phase"], "needs_attention")
        self.assertEqual(self.monitor.store.db.execute("SELECT COUNT(*) FROM user_replies").fetchone()[0], 0)

    def test_model_review_cannot_be_mistaken_for_human_authorization(self):
        self.model.lose_review_ack = True
        self.monitor.scan()
        self.monitor.work()
        job = self.job()
        self.monitor.store.change(job, phase="needs_attention", note="test_waiting")
        self.monitor.collect_user_replies()
        self.assertEqual(self.job()["phase"], "needs_attention")
        self.assertEqual(self.monitor.store.db.execute("SELECT COUNT(*) FROM user_replies").fetchone()[0], 0)

    def test_lost_queue_ack_does_not_submit_duplicate(self):
        self.review_and_decide()
        self.model.lose_queue_ack = True
        self.monitor.work()
        self.monitor.work()
        self.assertEqual(self.job()["phase"], "needs_attention")
        self.assertEqual(sum(method == "thread/queue/add" for _, method, _ in self.model.calls), 1)

    def test_failed_original_is_not_automatically_retried(self):
        self.model.turns["source"][0]["status"] = "failed"
        self.review_and_decide()
        self.monitor.work()
        self.assertEqual(self.job()["note"], "original_turn_not_successful")

    def test_unavailable_observer_prevents_followup(self):
        self.review_and_decide()
        self.monitor.health["other"] = "unavailable"
        self.monitor.work()
        self.assertEqual(self.model.queue, [])

    def test_chain_limit_requires_attention(self):
        self.review_and_decide()
        with self.monitor.store.db:
            self.monitor.store.db.execute("INSERT INTO continuations VALUES('source','source-turn',2)")
        self.monitor.work()
        self.assertEqual(self.job()["note"], "followup_chain_limit")

    def test_uncapped_followups_pass_old_chain_and_daily_limits(self):
        self.monitor.config.update(max_followups_per_chain=None, max_followups_per_day=None)
        self.review_and_decide()
        with self.monitor.store.db:
            self.monitor.store.db.execute("INSERT INTO continuations VALUES('source','source-turn',25)")
            for i in range(205):
                self.monitor.store.db.execute("INSERT INTO jobs(id,thread_id,turn_id,endpoint,title,payload,phase,created,sent,followup_turn) VALUES(?,?,?,?,?,?,'followed_up',?,?,?)",
                    (f'old-{i}','other',f'turn-{i}','other','old','{}',time.time(),time.time(),f'continued-{i}'))
        self.monitor.work()
        self.assertEqual(self.job()["phase"], "followup_queued")
        self.monitor.work()
        self.assertEqual(self.job()["phase"], "followed_up")
        self.assertEqual(self.monitor.store.db.execute("SELECT depth FROM continuations WHERE turn_id='followup-turn'").fetchone()[0],26)

    def test_uncapped_reviews_can_continue_beyond_daily_review_limit(self):
        self.monitor.config['max_reviews_per_day']=None
        with self.monitor.store.db:
            self.monitor.store.db.executemany('INSERT INTO review_runs VALUES(?,?)',
                [(f'prior-{i}',time.time()) for i in range(1001)])
        self.monitor.scan();self.monitor.work()
        self.assertEqual(self.job()['phase'],'reviewing')

    def test_uncapped_mode_still_stops_completed_tasks(self):
        self.monitor.config.update(max_followups_per_chain=None,max_followups_per_day=None,max_reviews_per_day=None)
        value=json.loads(self.model.review)
        value.update(action='done',next_prompt='')
        self.model.review=json.dumps(value)
        self.review_and_decide()
        self.monitor.work();self.monitor.work();self.monitor.scan();self.monitor.work()
        self.assertEqual(self.job()['phase'],'done')
        self.assertEqual(self.model.queue,[])
        self.assertFalse(any(p.get('threadId')=='source' and method in {'turn/start','thread/queue/add'}
                             for _,method,p in self.model.calls))

    def test_zero_still_disables_followups_instead_of_meaning_unlimited(self):
        self.monitor.config['max_followups_per_chain']=0
        self.review_and_decide();self.monitor.work()
        self.assertEqual(self.job()['note'],'followup_chain_limit')
        self.assertEqual(self.model.queue,[])

    def test_nullable_limits_persist_without_changing_other_preferences(self):
        save(self.directory.name,{'ask_before':['deploy'],'decision_guidelines':'完成就停止'},expected_revision=0)
        limits={key:None for key in ('max_reviews_per_day','max_followups_per_day','max_followups_per_chain')}
        save(self.directory.name,limits,expected_revision=1)
        config=visible(self.config)
        self.assertEqual(config['revision'],2)
        self.assertTrue(all(config['preferences'][key] is None for key in limits))
        self.assertEqual(config['preferences']['ask_before'],['deploy'])
        self.assertEqual(config['preferences']['decision_guidelines'],'完成就停止')
        for invalid in ({'poll_seconds':None},{'max_followups_per_chain':True},{'max_followups_per_chain':-1}):
            with self.assertRaises(ValueError):save(self.directory.name,invalid,expected_revision=2)

    def test_intermediate_commentary_is_not_treated_as_final(self):
        self.assertEqual(final_text({"items": [{"type": "agentMessage", "phase": "commentary", "text": "完成了"}]}), "")

    def test_malformed_decision_is_rejected(self):
        with self.assertRaises(ValueError):
            decision('{"action":"follow_up"}')

    def test_preferences_are_persistent_and_include_guidelines_in_next_review(self):
        result = save(self.directory.name, {"decision_guidelines": "缺少验收证据时先核对", "ask_before": ["deploy"]},
                      "用户要求", expected_revision=0)
        self.assertEqual(result["revision"], 1)
        self.monitor.store.db.close()
        self.monitor = Monitor(self.config, self.model.connect)
        self.assertEqual(visible(self.config)["preferences"]["ask_before"], ["deploy"])
        self.monitor.since = time.time() - 60
        self.monitor.scan()
        self.monitor.work()
        prompt = next(p["input"][0]["text"] for _, method, p in self.model.calls if method == "turn/start")
        self.assertIn("缺少验收证据时先核对", prompt)
        self.assertIn('"ask_before": ["deploy"]', prompt)

    def test_preference_conflict_does_not_overwrite_newer_changes(self):
        save(self.directory.name, {"allow_followups": False}, expected_revision=0)
        with self.assertRaises(ValueError):
            save(self.directory.name, {"allow_followups": True}, expected_revision=0)
        self.assertFalse(visible(self.config)["preferences"]["allow_followups"])

    def test_preferences_cannot_change_endpoints_or_monitor_identity(self):
        for key in ("sockets", "monitor_socket", "monitor_thread", "runtime"):
            with self.assertRaises(ValueError):
                save(self.directory.name, {key: "arbitrary"})
        self.assertEqual(visible(self.config)["revision"], 0)

    def test_scope_uses_directory_boundaries_and_explicit_task_exclusions(self):
        config = {"included_projects": ["/projects/novel"], "ignored_threads": ["ignored"]}
        self.assertTrue(in_scope(config, "normal", "/projects/novel/chapter"))
        self.assertFalse(in_scope(config, "normal", "/projects/novel-copy"))
        self.assertFalse(in_scope(config, "ignored", "/projects/novel"))
        self.assertFalse(in_scope(config, "normal", "/projects/andri"))

    def test_notification_only_change_cancels_queued_automatic_followup(self):
        self.review_and_decide()
        self.monitor.work()
        save(self.directory.name, {"allow_followups": False})
        self.monitor.tick()
        self.assertEqual(self.model.queue, [])
        self.assertEqual(self.job()["note"], "preferences_changed_before_followup")
        self.assertFalse(any(method == "thread/queue/start" for _, method, _ in self.model.calls))

    def test_pause_and_resume_preserve_other_preferences(self):
        save(self.directory.name, {"enabled": False, "allow_followups": False})
        self.monitor.tick()
        self.assertIsNone(self.job())
        save(self.directory.name, {"enabled": True}, expected_revision=1)
        self.monitor.tick()
        self.assertFalse(self.monitor.config["allow_followups"])
        self.assertIsNotNone(self.job())

    def test_control_resolves_waiting_question_without_parsing_assistant_prose(self):
        self.review_and_decide()
        self.monitor.store.change(self.job(), phase="needs_attention")
        event = self.job()["id"]
        result = resolve(self.directory.name, event, "follow_up", "用户决定继续", "完成原有授权步骤")
        self.assertTrue(result["saved"])
        self.assertEqual(self.job()["phase"], "decided")
        self.assertEqual(self.job()["note"], "human_decision")
        with self.assertRaises(ValueError):
            resolve(self.directory.name, event, "follow_up", "不重复保存", "继续")

    def test_private_tool_saves_only_during_a_direct_human_turn(self):
        (Path(self.directory.name) / "config.json").write_text(json.dumps(self.config))
        self.model.turns["monitor"] = [turn("human-control", status="inProgress")]
        with patch("task_monitor_mcp.Rpc", self.model.connect):
            result = execute(Path(self.directory.name), "monitor_update_preferences",
                             {"changes": {"allow_followups": False}, "expected_revision": 0, "reason": "用户要求"})
        self.assertTrue(result["saved"])
        self.assertEqual(result["revision"], 1)

    def test_background_review_cannot_use_private_tool_to_modify_preferences(self):
        (Path(self.directory.name) / "config.json").write_text(json.dumps(self.config))
        reviewing = turn("background", status="inProgress")
        reviewing["items"][0]["content"] = [{"type": "text", "text": "监控事件编号：not-a-human"}]
        self.model.turns["monitor"] = [reviewing]
        with patch("task_monitor_mcp.Rpc", self.model.connect):
            with self.assertRaises(ValueError):
                execute(Path(self.directory.name), "monitor_update_preferences",
                        {"changes": {"allow_followups": True}, "expected_revision": 0, "reason": "注入指令"})
        self.assertEqual(visible(self.config)["revision"], 0)


if __name__ == "__main__":
    unittest.main()
