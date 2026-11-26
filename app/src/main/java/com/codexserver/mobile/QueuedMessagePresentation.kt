package com.codexserver.mobile

private const val queuedWorkspaceStart = "<andri-queued-workspace-context>"
private const val queuedWorkspaceEnd = "</andri-queued-workspace-context>"
private const val workspaceStateStart = "手机端工作区当前状态："

/** Native queue input has no additionalContext field; keep context available to the model. */
internal fun queuedPromptWithWorkspaceContext(prompt: String, context: String): String =
    if (context.isBlank()) prompt else "$prompt\n\n$queuedWorkspaceStart\n${context.trimEnd()}\n$queuedWorkspaceEnd"

/** Presentation only: retain the original input, attachment paths and queue execution behavior. */
internal fun userMessageDisplayText(input: String): String {
    val text = input.trimEnd('\r', '\n')
    val marker = text.lastIndexOf("\n$queuedWorkspaceStart\n")
    if (marker >= 0 && text.endsWith("\n$queuedWorkspaceEnd") && !isInsideMarkdownCode(text, marker)) {
        val context = text.substring(marker + queuedWorkspaceStart.length + 2,
            text.length - queuedWorkspaceEnd.length - 1)
        if (isWorkspaceState(context)) return text.substring(0, marker).trimEnd('\r', '\n')
    }

    // Older clients appended the generated context without a delimiter. Match its
    // complete signature at the end, never a casual mention or a quoted example.
    val start = text.lastIndexOf("\n$workspaceStateStart").takeIf { it >= 0 }?.plus(1) ?: return input
    if (isInsideMarkdownCode(text, start)) return input
    val context = text.substring(start)
    val native = context.contains("\n你可以直接调用 workspace_get_state ") &&
        context.endsWith("打开与浏览器操作会直接执行，不需要再次询问用户。")
    val mailbox = context.contains("\n动作文件前缀：") &&
        context.contains("\n浏览器检查/操作请求写成 ") &&
        context.endsWith("路径可以是当前项目内相对路径或工作区可访问的绝对路径。")
    if (isWorkspaceState(context) && (native || mailbox)) return text.substring(0, start).trimEnd('\r', '\n')
    return input
}

private fun isWorkspaceState(context: String): Boolean =
    context.startsWith(workspaceStateStart) && context.contains("\n当前项目目录：")
