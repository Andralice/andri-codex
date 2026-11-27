"""Durable, project-owned scheduled prompts for persistent Codex conversations."""

from datetime import datetime, time as local_time, timedelta, timezone
import json
import os
from pathlib import Path
import sqlite3
import time
import uuid
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from task_monitor_rpc import Rpc, RpcError


MAX_PROMPT = 12_000
MAX_TITLE = 160
MIN_DELAY = 60


class ScheduleError(ValueError):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


class ScheduleStore:
    def __init__(self, runtime):
        self.path = Path(runtime) / "schedules.sqlite3"
        self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.db = sqlite3.connect(self.path, timeout=5)
        # This database contains user prompts and conversation identifiers.
        # Keep it private even when the service starts under a permissive umask.
        os.chmod(self.path, 0o600)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=FULL")
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS scheduled_tasks(
                id TEXT PRIMARY KEY, thread_id TEXT NOT NULL, title TEXT NOT NULL,
                prompt TEXT NOT NULL, timezone TEXT NOT NULL, repeat_daily INTEGER NOT NULL,
                daily_time TEXT NOT NULL, next_run_at REAL NOT NULL, state TEXT NOT NULL,
                created_at REAL NOT NULL, updated_at REAL NOT NULL, last_run_at REAL,
                active_turn_id TEXT, queued_id TEXT, marker TEXT NOT NULL,
                detail TEXT NOT NULL DEFAULT '', last_status TEXT NOT NULL DEFAULT '');
            CREATE INDEX IF NOT EXISTS scheduled_tasks_due ON scheduled_tasks(state,next_run_at);
        """)
        self.db.commit()

    def close(self):
        self.db.close()

    def list(self, limit=100):
        return [dict(row) for row in self.db.execute(
            "SELECT * FROM scheduled_tasks WHERE state!='cancelled' ORDER BY next_run_at,id LIMIT ?",
            (max(1, min(int(limit), 100)),))]

    def create(self, thread_id, prompt, due_at, timezone_name, repeat_daily, title=""):
        now = time.time()
        if not isinstance(thread_id, str) or not thread_id.strip():
            raise ScheduleError("invalid_thread")
        if not isinstance(prompt, str) or not prompt.strip() or len(prompt) > MAX_PROMPT:
            raise ScheduleError("invalid_prompt")
        if not isinstance(due_at, (int, float)) or isinstance(due_at, bool) or due_at < now + MIN_DELAY:
            raise ScheduleError("invalid_time")
        if not isinstance(timezone_name, str):
            raise ScheduleError("invalid_timezone")
        try:
            zone = ZoneInfo(timezone_name)
        except (ZoneInfoNotFoundError, ValueError):
            raise ScheduleError("invalid_timezone") from None
        if not isinstance(repeat_daily, bool):
            raise ScheduleError("invalid_repeat")
        if not isinstance(title, str):
            raise ScheduleError("invalid_title")
        title = (title.strip() or prompt.strip().splitlines()[0])[:MAX_TITLE]
        task_id = str(uuid.uuid4())
        date = datetime.fromtimestamp(due_at, zone)
        marker = f"【Andri 定时任务 {task_id}】"
        with self.db:
            self.db.execute("""INSERT INTO scheduled_tasks
                (id,thread_id,title,prompt,timezone,repeat_daily,daily_time,next_run_at,state,
                 created_at,updated_at,marker) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
                (task_id, thread_id.strip(), title, prompt.strip(), timezone_name, int(repeat_daily),
                 date.strftime("%H:%M"), float(due_at), "scheduled", now, now, marker))
        return dict(self.db.execute("SELECT * FROM scheduled_tasks WHERE id=?", (task_id,)).fetchone())

    def change(self, task_id, **fields):
        allowed = {"state", "updated_at", "last_run_at", "active_turn_id", "queued_id", "detail", "last_status", "next_run_at", "marker"}
        if not fields or not fields.keys() <= allowed:
            raise ValueError("Invalid schedule state change")
        values = dict(fields)
        values.setdefault("updated_at", time.time())
        with self.db:
            self.db.execute("UPDATE scheduled_tasks SET " + ",".join(k + "=?" for k in values) + " WHERE id=?",
                            (*values.values(), task_id))


def validate_thread(config, thread_id, connect=Rpc):
    endpoints = list(dict.fromkeys(config.get("sockets", [])))
    if not endpoints:
        raise ScheduleError("scheduler_unavailable")
    found = []
    for endpoint in endpoints:
        try:
            with connect(endpoint) as rpc:
                thread = rpc.call("thread/read", {"threadId": thread_id, "includeTurns": False}).get("thread", {})
                if thread.get("id") == thread_id:
                    found.append(thread)
        except (OSError, RpcError, KeyError):
            continue
    if not found:
        raise ScheduleError("thread_unavailable")
    if any(row.get("ephemeral") for row in found):
        raise ScheduleError("ephemeral_unsupported")
    return found[0]


def api(runtime, config, method, params, connect=Rpc):
    store = ScheduleStore(runtime)
    try:
        if method == "andri/schedules/list":
            return {"available": True, "items": store.list()}
        if method == "andri/schedules/create":
            thread_id = params.get("threadId")
            thread = validate_thread(config, thread_id, connect)
            row = store.create(thread_id, params.get("prompt"), params.get("runAt"),
                               params.get("timezone"), params.get("repeatDaily", False),
                               params.get("title", thread.get("name", "")))
            row["thread_title"] = thread.get("name") or "未命名对话"
            return {"saved": True, "item": row}
        if method == "andri/schedules/cancel":
            task_id = params.get("id")
            row = store.db.execute("SELECT * FROM scheduled_tasks WHERE id=?", (task_id,)).fetchone()
            if not row or row["state"] == "cancelled":
                raise ScheduleError("schedule_missing")
            if row["state"] == "queued" and row["queued_id"]:
                endpoint = _owner_endpoint(config, row["thread_id"], connect)
                with connect(endpoint) as rpc:
                    queued = rpc.call("thread/queue/list", {"threadId": row["thread_id"], "limit": 100}).get("data", [])
                    if any(item.get("id") == row["queued_id"] for item in queued):
                        rpc.call("thread/queue/delete", {"threadId": row["thread_id"],
                                 "queuedSubmissionId": row["queued_id"]})
            store.change(task_id, state="cancelled", detail="用户已取消")
            return {"saved": True, "id": task_id}
        raise ScheduleError("unknown_method")
    finally:
        store.close()


def _pages(rpc, method, thread_id=None):
    cursor = None
    seen = set()
    while True:
        params = {"limit": 100, "cursor": cursor}
        if thread_id:
            params["threadId"] = thread_id
        page = rpc.call(method, params)
        yield from page.get("data", [])
        cursor = page.get("nextCursor")
        if not cursor or cursor in seen:
            break
        seen.add(cursor)


def _owner_endpoint(config, thread_id, connect=Rpc):
    endpoints = list(dict.fromkeys(config.get("sockets", [])))
    owners = []
    for endpoint in endpoints:
        with connect(endpoint) as rpc:
            if thread_id in set(_pages(rpc, "thread/loaded/list")):
                owners.append(endpoint)
    if len(owners) > 1:
        raise ScheduleError("owner_ambiguous")
    if owners:
        return owners[0]
    preferred = config.get("monitor_socket")
    if preferred in endpoints:
        return preferred
    return endpoints[0]


def _read_turns(rpc, thread_id):
    cursor = None
    result = []
    seen = set()
    for _ in range(4):
        params = {"threadId": thread_id, "limit": 32, "sortDirection": "desc", "itemsView": "full"}
        if cursor:
            params["cursor"] = cursor
        page = rpc.call("thread/turns/list", params)
        result.extend(page.get("data", []))
        cursor = page.get("nextCursor")
        if not cursor or cursor in seen:
            break
        seen.add(cursor)
    return result


def _find_marker(rpc, thread_id, marker):
    for turn in _read_turns(rpc, thread_id)[:100]:
        items = turn.get("items", [])
        text = "\n".join(item.get("text", "") or "\n".join(
            part.get("text", "") for part in item.get("content", []) if part.get("type") == "text")
            for item in items if item.get("type") == "userMessage")
        if marker in text:
            return turn
    return None


def _next_daily(due_at, timezone_name, daily_time, now):
    zone = ZoneInfo(timezone_name)
    due_local = datetime.fromtimestamp(due_at, zone)
    hour, minute = map(int, daily_time.split(":"))
    candidate_date = due_local.date() + timedelta(days=1)
    candidate = datetime.combine(candidate_date, local_time(hour, minute), zone)
    while candidate.timestamp() <= now:
        candidate_date += timedelta(days=1)
        candidate = datetime.combine(candidate_date, local_time(hour, minute), zone)
    return candidate.timestamp()


def _finish_run(store, row, turn):
    now = time.time()
    if row["repeat_daily"]:
        next_run = _next_daily(row["next_run_at"], row["timezone"], row["daily_time"], now)
        marker = f"【Andri 定时任务 {row['id']}:{int(next_run)}】"
        store.change(row["id"], state="scheduled", next_run_at=next_run, marker=marker,
            active_turn_id="", queued_id="", last_run_at=now, last_status=turn.get("status", "unknown"),
            detail="上次执行" + ("已完成" if turn.get("status") == "completed" else "未成功；请检查会话"))
    else:
        store.change(row["id"], state="completed" if turn.get("status") == "completed" else "needs_attention",
            active_turn_id="", queued_id="", last_run_at=now, last_status=turn.get("status", "unknown"),
            detail="已完成" if turn.get("status") == "completed" else "执行未成功，请检查会话")


class ScheduledTaskRunner:
    def __init__(self, config, connect=Rpc):
        self.config = config
        self.connect = connect
        self.runtime = config["runtime"]

    def tick(self, now=None):
        now = time.time() if now is None else now
        store = ScheduleStore(self.runtime)
        try:
            rows = store.db.execute("""SELECT * FROM scheduled_tasks WHERE state IN
                ('scheduled','dispatching','queue_sending','queued','running','delivery_unknown')
                ORDER BY next_run_at LIMIT 100""").fetchall()
            for row in rows:
                try:
                    self._advance(store, row, now)
                except (OSError, RpcError, TimeoutError):
                    current = store.db.execute("SELECT * FROM scheduled_tasks WHERE id=?", (row["id"],)).fetchone()
                    if current and current["state"] in ("dispatching", "queue_sending"):
                        store.change(row["id"], state="delivery_unknown", detail="发送结果未确认；不会自动重复发送")
                    # Transient observer or backend failures leave a not-yet-sent task due for retry.
        finally:
            store.close()

    def _advance(self, store, row, now):
        if row["state"] in ("dispatching", "queue_sending", "queued", "running", "delivery_unknown"):
            endpoint = _owner_endpoint(self.config, row["thread_id"], self.connect)
            with self.connect(endpoint) as rpc:
                turn = _find_marker(rpc, row["thread_id"], row["marker"])
                if turn:
                    if turn.get("status") in ("inProgress", "running"):
                        store.change(row["id"], state="running", active_turn_id=turn["id"], queued_id="")
                    elif turn.get("status") in ("completed", "failed", "interrupted", "cancelled"):
                        _finish_run(store, row, turn)
                    return
                if row["state"] == "delivery_unknown":
                    queued = rpc.call("thread/queue/list", {"threadId": row["thread_id"], "limit": 100}).get("data", [])
                    if any(row["marker"] in json.dumps(item, ensure_ascii=False) for item in queued):
                        match = next(item for item in queued
                                     if row["marker"] in json.dumps(item, ensure_ascii=False))
                        store.change(row["id"], state="queued", queued_id=match.get("id", ""),
                                     detail="已在队列中，等待当前回合完成")
                    return
                if row["state"] in ("dispatching", "queue_sending"):
                    store.change(row["id"], state="delivery_unknown", detail="发送结果未确认；不会自动重复发送")
                    return
                if row["state"] == "queued":
                    queued = rpc.call("thread/queue/list", {"threadId": row["thread_id"], "limit": 100}).get("data", [])
                    if any(item.get("id") == row["queued_id"] for item in queued):
                        return
                    store.change(row["id"], state="needs_attention", detail="队列项目已消失，未找到对应执行回合")
                    return
                return
        if row["state"] != "scheduled" or row["next_run_at"] > now:
            return
        endpoint = _owner_endpoint(self.config, row["thread_id"], self.connect)
        with self.connect(endpoint) as rpc:
            thread = rpc.call("thread/resume", {"threadId": row["thread_id"], "excludeTurns": True}).get("thread", {})
            if thread.get("ephemeral"):
                store.change(row["id"], state="unsupported", detail="临时会话不能用于持久定时任务")
                return
            state = thread.get("status", {}).get("type")
            queue = rpc.call("thread/queue/list", {"threadId": row["thread_id"], "limit": 100}).get("data", [])
            marked_prompt = row["prompt"].rstrip() + "\n\n" + row["marker"]
            if state == "active":
                store.change(row["id"], state="queue_sending")
                result = rpc.call("thread/queue/add", {"threadId": row["thread_id"],
                    "clientUserMessageId": str(uuid.uuid5(uuid.NAMESPACE_URL, row["id"] + ":" + str(row["next_run_at"]))),
                    "input": [{"type": "text", "text": marked_prompt}]})
                queued_id = result.get("queuedSubmission", {}).get("id")
                if not queued_id:
                    store.change(row["id"], state="delivery_unknown", detail="服务器未确认队列项目；不会自动重发")
                    return
                store.change(row["id"], state="queued", queued_id=queued_id, detail="等待当前回合完成")
                return
            if state != "idle" or queue:
                return
            # Persist before the externally visible turn/start; reconcile by marker after a lost ACK.
            store.change(row["id"], state="dispatching")
            result = rpc.call("turn/start", {"threadId": row["thread_id"],
                "input": [{"type": "text", "text": marked_prompt}]})
            turn_id = result.get("turn", {}).get("id", "")
            if not turn_id:
                store.change(row["id"], state="delivery_unknown", detail="服务器没有返回回合编号；不会自动重发")
                return
            store.change(row["id"], state="running", active_turn_id=turn_id, detail="已启动")


def schedule_state_label(state):
    return {
        "scheduled": "待执行", "dispatching": "正在提交", "queue_sending": "正在排队",
        "queued": "等待当前任务完成", "running": "执行中", "completed": "已完成",
        "needs_attention": "需要检查", "delivery_unknown": "发送状态待核实",
        "unsupported": "会话不支持", "cancelled": "已取消",
    }.get(state, "状态待核实")
