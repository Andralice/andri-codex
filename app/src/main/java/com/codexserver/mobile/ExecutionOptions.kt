package com.codexserver.mobile

import org.json.JSONObject

internal const val WORKSPACE_ROOT = "/home/ubuntu/repos"

enum class ExecutionMode(val label: String, val description: String, val policy: String, val reviewer: String, val sandbox: String) {
    ASK("请求审批", "工作区内执行，需要额外权限时由你确认。", "on-request", "user", "workspace-write"),
    AUTO("替我审批", "工作区内执行，由 Andri 自动审查额外权限；仍可能拒绝请求。", "on-request", "auto_review", "workspace-write"),
    FULL("完全访问", "以 SSH 登录用户的权限执行，不限制在工作区，也不请求执行审批。", "never", "user", "danger-full-access")
}

data class ExecutionOptions(val cwd: String, val mode: ExecutionMode = ExecutionMode.ASK, val effort: String = "medium") {
    fun params(turn: Boolean = false): JSONObject {
        require(cwd.startsWith("/") && '\u0000' !in cwd && '\n' !in cwd) { "工作区必须是服务器绝对路径" }
        require(effort.matches(Regex("[A-Za-z0-9_-]{1,32}"))) { "推理强度无效" }
        return JSONObject().put("cwd", cwd).put("approvalPolicy", mode.policy).put("approvalsReviewer", mode.reviewer).apply {
            if (turn) put("sandboxPolicy", JSONObject().put("type", if (mode == ExecutionMode.FULL) "dangerFullAccess" else "workspaceWrite"))
            else put("sandbox", mode.sandbox).put("config", JSONObject().put("model_reasoning_effort", effort))
            if (turn) put("effort", effort)
        }
    }
}

internal fun executionOptions(result: JSONObject, fallback: ExecutionOptions): ExecutionOptions {
    val access = result.optJSONObject("sandboxPolicy")?.text("type").orEmpty()
        .ifBlank { result.optJSONObject("sandbox")?.text("type").orEmpty().ifBlank { result.text("sandbox") } }
    val mode = when {
        access in listOf("dangerFullAccess", "danger-full-access") && result.text("approvalPolicy") == "never" -> ExecutionMode.FULL
        result.text("approvalsReviewer") in listOf("auto_review", "guardian_subagent") -> ExecutionMode.AUTO
        result.has("approvalPolicy") -> ExecutionMode.ASK
        else -> fallback.mode
    }
    val effort = result.text("effort").ifBlank { result.text("reasoningEffort") }.ifBlank { result.text("modelReasoningEffort") }
        .ifBlank { result.optJSONObject("thread")?.text("reasoningEffort").orEmpty() }
        .ifBlank { fallback.effort }
    return ExecutionOptions(result.text("cwd").ifBlank { result.optJSONObject("thread")?.text("cwd").orEmpty().ifBlank { fallback.cwd } }, mode, effort)
}

data class RemoteDirectory(val path: String, val children: List<String>, val files: List<String> = emptyList())
