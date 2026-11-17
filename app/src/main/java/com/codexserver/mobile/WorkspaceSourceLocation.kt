package com.codexserver.mobile

internal data class WorkspaceSourceLocation(val line: Int, val column: Int? = null)

internal data class WorkspaceSourceReference(val link: String, val location: WorkspaceSourceLocation? = null)

private val sourceColonLocation = Regex(":([1-9][0-9]*)(?::([1-9][0-9]*))?$")
private val sourceFragmentLocation = Regex("L([1-9][0-9]*)(?:C([1-9][0-9]*))?(?:-L?[1-9][0-9]*)?")

/** Strip source coordinates before URI parsing (Main.kt:12 would otherwise look like a URI scheme). */
internal fun workspaceSourceReference(value: String): WorkspaceSourceReference {
    val scheme = value.substringBefore(':').takeIf {
        it.matches(Regex("[A-Za-z][A-Za-z0-9+.-]*"))
    }
    if (scheme != null && !scheme.equals("file", true) && '.' !in scheme) return WorkspaceSourceReference(value)
    val path = value.substringBefore('#').substringBefore('?')
    val suffix = sourceColonLocation.find(path)
    val fragment = sourceFragmentLocation.matchEntire(value.substringAfter('#', ""))
    val coordinates = fragment ?: suffix ?: return WorkspaceSourceReference(value)
    val line = coordinates.groupValues[1].toIntOrNull() ?: return WorkspaceSourceReference(value)
    val rawColumn = coordinates.groupValues[2]
    val column = if (rawColumn.isEmpty()) null else rawColumn.toIntOrNull() ?: return WorkspaceSourceReference(value)
    val cleanPath = if (suffix != null) path.substring(0, suffix.range.first) else path
    return WorkspaceSourceReference(cleanPath, WorkspaceSourceLocation(line, column))
}

/** One-based source coordinates, clamped to the end of an existing line. */
internal fun workspaceSourceOffset(text: String, location: WorkspaceSourceLocation): Int? {
    if (location.line < 1) return null
    var start = 0
    repeat(location.line - 1) {
        val newline = text.indexOf('\n', start)
        if (newline < 0) return null
        start = newline + 1
    }
    val end = text.indexOf('\n', start).takeIf { it >= 0 } ?: text.length
    return start + ((location.column ?: 1) - 1).coerceIn(0, end - start)
}
