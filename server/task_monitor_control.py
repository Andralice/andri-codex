"""Conversation tool: inspect/update validated preferences and resolve waiting events."""

import argparse
import json
from pathlib import Path
import sqlite3
import time

from task_monitor_preferences import save, visible
from task_monitor_rpc import Rpc, RpcError


def resolve(runtime, event_id, action, summary, next_prompt="", question=""):
    if action not in {"done", "follow_up", "ask_user"}:
        raise ValueError("Invalid action")
    if not isinstance(summary, str) or not summary.strip() or len(summary) > 12000:
        raise ValueError("A concise summary is required")
    if action == "follow_up" and (not next_prompt.strip() or len(next_prompt) > 12000):
        raise ValueError("A concrete follow-up is required")
    if action == "ask_user" and not question.strip():
        raise ValueError("A concrete question is required")
    result = {"event_id": event_id, "action": action, "summary": summary,
              "next_prompt": next_prompt, "question": question}
    with sqlite3.connect(Path(runtime) / "state.sqlite3") as db:
        # A direct user may act on a completed independent inspection of a closed
        # event. Automatic reviewers cannot use this control tool, and execution
        # still checks source freshness, scope, approvals and follow-up limits.
        eligible = db.execute("SELECT 1 FROM jobs j JOIN inspections i ON i.event_id=j.id AND i.thread_id=j.thread_id AND i.source_turn=j.turn_id "
                              "WHERE j.id=? AND j.phase='done' AND i.state='completed'", (event_id,)).fetchone() if action == 'follow_up' else None
        changed = db.execute("UPDATE jobs SET phase='decided',result=?,note='human_decision' "
                             "WHERE id=? AND (phase='needs_attention' OR (phase='done' AND ?))",
                             (json.dumps(result, ensure_ascii=False), event_id, bool(eligible))).rowcount
        if not changed:
            raise ValueError("Event is not waiting for a user decision; inspect current events first")
    return {"saved": True, "event_id": event_id, "action": action,
            "message": "决定已保存；执行仍受当前监控偏好及原任务权限约束。"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", required=True)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("show")
    commands.add_parser("events")
    commands.add_parser("status")
    commands.add_parser("tasks")
    edit = commands.add_parser("set")
    edit.add_argument("--changes", required=True, help="JSON object containing only changed preference keys")
    edit.add_argument("--reason", default="")
    edit.add_argument("--expected-revision", type=int, required=True)
    reply = commands.add_parser("resolve")
    reply.add_argument("--event", required=True)
    reply.add_argument("--action", required=True, choices=["done", "follow_up", "ask_user"])
    reply.add_argument("--summary", required=True)
    reply.add_argument("--next-prompt", default="")
    reply.add_argument("--question", default="")
    args = parser.parse_args()
    runtime = Path(args.runtime).resolve()
    config = json.loads((runtime / "config.json").read_text())
    if Path(config["runtime"]).resolve() != runtime:
        parser.error("Runtime identity mismatch")
    try:
        if args.command == "show":
            output = visible(config)
        elif args.command == "set":
            result = save(runtime, json.loads(args.changes), args.reason, args.expected_revision)
            output = {"saved": True, "revision": result["revision"], "changed": json.loads(args.changes),
                      "effective": visible(config), "message": "已持久保存，后台在下一个周期读取。已启动任务不会因此被中断。"}
        elif args.command == "resolve":
            output = resolve(runtime, args.event, args.action, args.summary, args.next_prompt, args.question)
        elif args.command == "events":
            with sqlite3.connect(runtime / "state.sqlite3") as db:
                db.row_factory = sqlite3.Row
                rows = db.execute("SELECT id,title,phase,result,note FROM jobs ORDER BY created DESC LIMIT 30").fetchall()
                output = [{"event_id": r["id"], "title": r["title"], "phase": r["phase"],
                           "decision": json.loads(r["result"]) if r["result"] else None, "note": r["note"]} for r in rows]
        elif args.command == "status":
            status = json.loads((runtime / "status.json").read_text())
            output = {"heartbeat_age_seconds": round(time.time() - status["updated_at"], 1),
                      "connections": list(status["endpoints"].values()), "jobs": status["jobs"],
                      "preferences": visible(config), "applied_revision": status.get("preference_revision", 0)}
        else:
            tasks = {}
            unavailable = 0
            for endpoint in config["sockets"]:
                try:
                    with Rpc(endpoint) as rpc:
                        cursor = None
                        while True:
                            page = rpc.call("thread/list", {"cursor": cursor, "limit": 100, "archived": False})
                            for thread in page["data"]:
                                if not thread.get("ephemeral") and thread["id"] != config["monitor_thread"]:
                                    tasks[thread["id"]] = {"id": thread["id"], "title": thread.get("name"),
                                                          "project": thread.get("cwd"), "status": thread["status"]}
                            cursor = page.get("nextCursor")
                            if not cursor:
                                break
                except (OSError, RpcError):
                    unavailable += 1
            output = {"tasks": list(tasks.values()), "unavailable_connections": unavailable}
        print(json.dumps(output, ensure_ascii=False))
    except (ValueError, OSError, sqlite3.Error) as error:
        # Do not echo arguments, upstream error messages, file paths or user text on failures.
        print(json.dumps({"saved": False, "error": type(error).__name__}))
        raise SystemExit(1)


if __name__ == "__main__":
    main()
