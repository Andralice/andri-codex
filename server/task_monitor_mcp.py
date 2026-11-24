"""Private stdio MCP tools for the supervisor; no arbitrary shell or file operations."""

import argparse
import json
from pathlib import Path
import sqlite3
import sys
import time

from task_monitor_control import resolve
from task_monitor_preferences import save, visible, validate, PreferenceConflict, PreferenceValidationError
from task_monitor_rpc import Rpc, RpcError
from task_monitor_questions import MARKER


def schema(properties=None, required=None):
    return {"type": "object", "properties": properties or {}, "required": required or [], "additionalProperties": False}


TOOLS = [
    {"name": "monitor_show_preferences", "description": "读取当前持久偏好及版本；修改偏好前必须先读取。", "inputSchema": schema()},
    {"name": "monitor_update_preferences", "description": "仅按用户直接要求保存偏好，返回保存版本。后台审查事件不得调用。",
     "inputSchema": schema({"changes": {"type": "object"}, "expected_revision": {"type": "integer"},
                            "reason": {"type": "string"}}, ["changes", "expected_revision", "reason"])},
    {"name": "monitor_resolve_event", "description": "按用户对指定待处理问题的直接回答保存决定；不越过原任务权限及现行偏好。",
     "inputSchema": schema({"event_id": {"type": "string"}, "action": {"type": "string", "enum": ["done", "follow_up", "ask_user"]},
                            "summary": {"type": "string"}, "next_prompt": {"type": "string"}, "question": {"type": "string"}},
                           ["event_id", "action", "summary"])},
    {"name": "monitor_status", "description": "读取后台心跳、任务数量和已读取的偏好版本。", "inputSchema": schema()},
    {"name": "monitor_events", "description": "读取最近事件及判断，定位用户指代的待处理问题。", "inputSchema": schema()},
    {"name": "monitor_tasks", "description": "只读列出本服务器任务的标题、ID、项目目录及状态。", "inputSchema": schema()},
]


CONTROL_ERRORS = {
    "backend_unavailable": "无法核对全部已配置后端，暂不保存；连接恢复后重读状态。",
    "manager_unloaded": "管家会话当前未加载，请重新打开管家对话后再保存。",
    "owner_ambiguous": "多个后端同时持有管家会话，无法唯一确认当前用户回合，暂不保存。",
    "owner_changed": "保存期间管家会话所属后端或当前回合发生变化，请重读后重试。",
    "no_current_user_turn": "保存需要管家中正在进行的真实用户回合；历史快照不能授权写入。",
    "background_not_authorized": "后台审查、服务通知或自动跟进不能授权修改管家设置。",
    "invalid_arguments": "工具参数缺失、多余或类型不正确，请按工具定义修正。",
    "invalid_revision": "expected_revision 必须是读取到的整数版本。",
    "runtime_identity_mismatch": "管家运行目录身份不一致，已拒绝写入。",
}


class ControlError(ValueError):
    def __init__(self, code):
        self.code = code
        super().__init__(CONTROL_ERRORS[code])


def owner_endpoint(config):
    """Only live ownership authorizes reads of an in-progress turn, never a disk snapshot."""
    endpoints = list(dict.fromkeys([*config.get("sockets", []), config["monitor_socket"]]))
    owners = []
    for endpoint in endpoints:
        try:
            with Rpc(endpoint) as rpc:
                cursor = None
                while True:
                    page = rpc.call("thread/loaded/list", {"limit": 100, "cursor": cursor})
                    if config["monitor_thread"] in page["data"]:
                        owners.append(endpoint)
                        break
                    cursor = page.get("nextCursor")
                    if not cursor:
                        break
        except (OSError, RpcError):
            raise ControlError("backend_unavailable") from None
    if not owners:
        raise ControlError("manager_unloaded")
    if len(owners) != 1:
        raise ControlError("owner_ambiguous")
    return owners[0]


def current_user_turn(config, endpoint):
    try:
        with Rpc(endpoint) as rpc:
            thread = rpc.call("thread/read", {"threadId": config["monitor_thread"], "includeTurns": False})["thread"]
            if thread["status"]["type"] == "notLoaded":
                raise ControlError("owner_changed")
            page = rpc.call("thread/turns/list", {"threadId": config["monitor_thread"], "limit": 1,
                            "itemsView": "full", "sortDirection": "desc"})
    except (OSError, RpcError):
        raise ControlError("backend_unavailable") from None
    if not page["data"] or page["data"][0].get("status") != "inProgress":
        raise ControlError("no_current_user_turn")
    turn = page["data"][0]
    user = "\n".join(p.get("text", "") for item in turn.get("items", []) if item.get("type") == "userMessage"
                     for p in item.get("content", []) if p.get("type") == "text")
    if not user.strip():
        raise ControlError("no_current_user_turn")
    if any(marker in user for marker in ("监控事件编号：", MARKER, "【任务管家自动跟进")):
        raise ControlError("background_not_authorized")
    with sqlite3.connect(Path(config["runtime"]) / "state.sqlite3") as db:
        if db.execute("SELECT 1 FROM questions WHERE turn_id=? UNION ALL SELECT 1 FROM question_history WHERE turn_id=?", (turn["id"], turn["id"])).fetchone():
            raise ControlError("background_not_authorized")
        if db.execute("SELECT 1 FROM jobs WHERE review_turn=?", (turn["id"],)).fetchone():
            raise ControlError("background_not_authorized")
    return turn["id"], user


def direct_user_only(config):
    """Resolve the real owner, preserve the user-only guard, and detect handoff races."""
    endpoint = owner_endpoint(config)
    before = current_user_turn(config, endpoint)
    if owner_endpoint(config) != endpoint or current_user_turn(config, endpoint) != before:
        raise ControlError("owner_changed")
    return before[0]


def error_result(error):
    """Stable, sanitized error codes; ValueError alone is not a parameter diagnosis."""
    if isinstance(error, ControlError):
        code, message = error.code, CONTROL_ERRORS[error.code]
    elif isinstance(error, PreferenceConflict):
        code, message = "preference_conflict", "偏好已被更新，请先重新读取当前版本；没有覆盖已有设置。"
    elif isinstance(error, PreferenceValidationError):
        code, message = "invalid_preference_value", "偏好字段或值不符合支持的范围，请修正后重试。"
    elif isinstance(error, RpcError):
        code, message = "backend_unavailable", CONTROL_ERRORS["backend_unavailable"]
    elif isinstance(error, (OSError, sqlite3.Error)):
        code, message = "storage_error", "读取或保存管家运行数据失败；请核对存储状态。"
    else:
        code, message = "operation_rejected", "本次操作被拒绝；不能仅凭 ValueError 判断是参数错误，需核对会话和运行数据。"
    return {"saved": False, "error": type(error).__name__, "code": code, "message": message}


def execute(runtime, name, arguments):
    config = json.loads((runtime / "config.json").read_text())
    if Path(config["runtime"]).resolve() != runtime:
        raise ControlError("runtime_identity_mismatch")
    definition = next((t for t in TOOLS if t["name"] == name), None)
    if not definition or not isinstance(arguments, dict):
        raise ControlError("invalid_arguments")
    shape = definition["inputSchema"]
    if set(arguments) - set(shape["properties"]) or set(shape["required"]) - set(arguments):
        raise ControlError("invalid_arguments")
    if name == "monitor_show_preferences":
        return visible(config)
    if name == "monitor_update_preferences":
        if type(arguments["expected_revision"]) is not int:
            raise ControlError("invalid_revision")
        validate(arguments["changes"])
        authorization_turn = direct_user_only(config)
        result = save(runtime, arguments["changes"], arguments["reason"], arguments["expected_revision"])
        return {"saved": True, "revision": result["revision"], "current": visible(config),
                "authorization_turn_id": authorization_turn,
                "message": "偏好已持久保存，后台下一周期读取；已启动任务不会因此中断。"}
    if name == "monitor_resolve_event":
        direct_user_only(config)
        return resolve(runtime, arguments["event_id"], arguments["action"], arguments["summary"],
                       arguments.get("next_prompt", ""), arguments.get("question", ""))
    if name == "monitor_status":
        status = json.loads((runtime / "status.json").read_text())
        return {"heartbeat_age_seconds": round(time.time() - status["updated_at"], 1),
                "connections": list(status["endpoints"].values()), "jobs": status["jobs"],
                "preferences": visible(config), "applied_revision": status.get("preference_revision", 0),
                "questions": status.get("questions", {}),
                "question_delivery_error": status.get("question_delivery_error", False),
                "reviewer_available": status.get("reviewer_available", False)}
    if name == "monitor_events":
        with sqlite3.connect(runtime / "state.sqlite3") as db:
            db.row_factory = sqlite3.Row
            rows = db.execute("SELECT j.id,j.title,j.phase,j.result,j.note,j.payload,q.state AS question_state FROM jobs j LEFT JOIN questions q ON q.event_id=j.id ORDER BY j.created DESC LIMIT 30").fetchall()
            return [{"event_id": r["id"], "title": r["title"], "phase": r["phase"],
                     "decision": json.loads(r["result"]) if r["result"] else None, "note": r["note"], "pending_question": r["phase"] == "needs_attention" and bool(r["result"]) and json.loads(r["result"])["action"] == "ask_user",
                     "question_delivery": r["question_state"], "verification": json.loads(r["payload"]).get("verification")} for r in rows]
    tasks, unavailable = {}, 0
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
    return {"tasks": list(tasks.values()), "unavailable_connections": unavailable}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", required=True)
    runtime = Path(parser.parse_args().runtime).resolve()
    for line in sys.stdin:
        try:
            request = json.loads(line)
            request_id, method = request.get("id"), request.get("method")
            if request_id is None:
                continue
            if method == "initialize":
                result = {"protocolVersion": request.get("params", {}).get("protocolVersion", "2024-11-05"),
                          "capabilities": {"tools": {}}, "serverInfo": {"name": "andri-monitor-control", "version": "1"}}
            elif method == "ping":
                result = {}
            elif method == "tools/list":
                result = {"tools": TOOLS}
            elif method == "tools/call":
                params = request["params"]
                try:
                    value = execute(runtime, params["name"], params.get("arguments", {}))
                    result = {"content": [{"type": "text", "text": json.dumps(value, ensure_ascii=False)}], "isError": False}
                except (ValueError, KeyError, OSError, sqlite3.Error, RpcError) as error:
                    result = {"content": [{"type": "text", "text": json.dumps(error_result(error), ensure_ascii=False)}],
                              "isError": True}
            else:
                print(json.dumps({"jsonrpc": "2.0", "id": request_id,
                                  "error": {"code": -32601, "message": "Unknown method"}}), flush=True)
                continue
            print(json.dumps({"jsonrpc": "2.0", "id": request_id, "result": result}, ensure_ascii=False), flush=True)
        except (ValueError, KeyError, TypeError):
            print(json.dumps({"jsonrpc": "2.0", "id": None,
                              "error": {"code": -32700, "message": "Invalid request"}}), flush=True)


if __name__ == "__main__":
    main()
