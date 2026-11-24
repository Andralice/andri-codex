package com.codexserver.mobile

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

private enum class SourceTokenStyle { KEYWORD, LITERAL, STRING, COMMENT, NUMBER, TYPE, FUNCTION, ANNOTATION, ATTRIBUTE }

/** Lightweight, dependency-free syntax coloring for the mobile source reader. */
internal fun highlightWorkspaceSource(source: String, extension: String): AnnotatedString {
    val language = extension.lowercase()
    if (language !in supportedSourceLanguages) return AnnotatedString(source)

    val keywords = when (language) {
        "kt", "kts" -> kotlinKeywords
        "java" -> javaKeywords
        "js", "jsx", "ts", "tsx" -> jsKeywords
        "py" -> pythonKeywords
        "sh", "bash", "zsh" -> shellKeywords
        "json" -> emptySet()
        "xml", "html", "htm", "svg" -> xmlKeywords
        "css", "scss" -> cssKeywords
        "sql" -> sqlKeywords
        "yaml", "yml", "toml" -> configKeywords
        "go" -> goKeywords
        "rs" -> rustKeywords
        else -> cKeywords
    }
    val lineComments = when (language) {
        "py", "sh", "bash", "zsh", "yaml", "yml", "toml" -> setOf('#')
        "sql" -> setOf('-')
        else -> emptySet()
    }
    val supportsSlashComments = language in setOf("kt", "kts", "java", "js", "jsx", "ts", "tsx", "css", "scss", "go", "rs", "c", "h", "cpp", "hpp")
    val xml = language in setOf("xml", "html", "htm", "svg")
    val styles = mapOf(
        SourceTokenStyle.KEYWORD to SpanStyle(color = Color(0xFF984575), fontWeight = FontWeight.Medium),
        SourceTokenStyle.LITERAL to SpanStyle(color = Color(0xFFB26B26), fontWeight = FontWeight.Medium),
        SourceTokenStyle.STRING to SpanStyle(color = Color(0xFF4E7B3E)),
        SourceTokenStyle.COMMENT to SpanStyle(color = Color(0xFF858B78), fontStyle = FontStyle.Italic),
        SourceTokenStyle.NUMBER to SpanStyle(color = Color(0xFF287C98)),
        SourceTokenStyle.TYPE to SpanStyle(color = Color(0xFF287D78)),
        SourceTokenStyle.FUNCTION to SpanStyle(color = Color(0xFF7153A1)),
        SourceTokenStyle.ANNOTATION to SpanStyle(color = Color(0xFFB07829)),
        SourceTokenStyle.ATTRIBUTE to SpanStyle(color = Color(0xFF9A6248))
    )
    val builder = AnnotatedString.Builder(source)
    fun paint(start: Int, end: Int, style: SourceTokenStyle) {
        if (end > start) builder.addStyle(styles.getValue(style), start, end)
    }
    fun scanQuoted(start: Int, delimiter: String, escapes: Boolean = true): Int {
        var cursor = start + delimiter.length
        while (cursor < source.length) {
            if (source.startsWith(delimiter, cursor)) return cursor + delimiter.length
            if (escapes && source[cursor] == '\\') cursor += 2 else cursor++
        }
        return source.length
    }
    fun previousNonSpace(before: Int): Char? {
        var cursor = before - 1
        while (cursor >= 0 && source[cursor].isWhitespace()) cursor--
        return source.getOrNull(cursor)
    }
    fun nextNonSpace(from: Int): Char? {
        var cursor = from
        while (cursor < source.length && source[cursor].isWhitespace()) cursor++
        return source.getOrNull(cursor)
    }
    var index = 0
    while (index < source.length) {
        val char = source[index]
        val lineComment = (char == '#' && '#' in lineComments) ||
            (char == '-' && language == "sql" && source.startsWith("--", index)) ||
            (supportsSlashComments && source.startsWith("//", index))
        if (xml && source.startsWith("<!--", index)) {
            val end = source.indexOf("-->", index + 4).let { if (it < 0) source.length else it + 3 }
            paint(index, end, SourceTokenStyle.COMMENT)
            index = end
            continue
        }
        if (lineComment) {
            val end = source.indexOf('\n', index).let { if (it < 0) source.length else it }
            paint(index, end, SourceTokenStyle.COMMENT)
            index = end
            continue
        }
        if (supportsSlashComments && source.startsWith("/*", index) || language == "sql" && source.startsWith("/*", index)) {
            val end = source.indexOf("*/", index + 2).let { if (it < 0) source.length else it + 2 }
            paint(index, end, SourceTokenStyle.COMMENT)
            index = end
            continue
        }
        val tripleDelimiter = when {
            source.startsWith("\"\"\"", index) -> "\"\"\""
            source.startsWith("'''", index) -> "'''"
            else -> null
        }
        if (tripleDelimiter != null) {
            val end = scanQuoted(index, tripleDelimiter)
            paint(index, end, SourceTokenStyle.STRING)
            index = end
            continue
        }
        if (char == '\"' || char == '\'' || char == '`') {
            val end = scanQuoted(index, char.toString())
            paint(index, end, SourceTokenStyle.STRING)
            index = end
            continue
        }
        if (char == '@' && language in setOf("kt", "kts", "java", "py")) {
            var end = index + 1
            while (end < source.length && (source[end].isLetterOrDigit() || source[end] == '_' || source[end] == '.')) end++
            paint(index, end, SourceTokenStyle.ANNOTATION)
            index = end
            continue
        }
        if (char.isDigit()) {
            var end = index + 1
            while (end < source.length && (source[end].isLetterOrDigit() || source[end] in "._")) end++
            paint(index, end, SourceTokenStyle.NUMBER)
            index = end
            continue
        }
        if (char.isLetter() || char == '_' || char == '$') {
            var end = index + 1
            while (end < source.length && (source[end].isLetterOrDigit() || source[end] == '_' || source[end] == '$')) end++
            val word = source.substring(index, end)
            val previous = previousNonSpace(index)
            val next = nextNonSpace(end)
            val style = when {
                word.lowercase() in literals -> SourceTokenStyle.LITERAL
                word in keywords || language == "sql" && word.uppercase() in keywords -> SourceTokenStyle.KEYWORD
                xml && previous == '<' -> SourceTokenStyle.TYPE
                xml && next == '=' -> SourceTokenStyle.ATTRIBUTE
                word.firstOrNull()?.isUpperCase() == true -> SourceTokenStyle.TYPE
                next == '(' -> SourceTokenStyle.FUNCTION
                else -> null
            }
            if (style != null) paint(index, end, style)
            index = end
            continue
        }
        index++
    }
    return builder.toAnnotatedString()
}

internal fun highlightTimelineContent(source: String, command: String, toolType: String): AnnotatedString {
    val readCommand = Regex("(?:^|[\\s;&|])(?:cat|head|tail|sed|less|more)\\b", RegexOption.IGNORE_CASE)
        .containsMatchIn(command)
    val commandExtension = if (readCommand) {
        Regex("\\.([A-Za-z0-9]+)(?:[:'\"\\s]|$)").findAll(command).lastOrNull()?.groupValues?.getOrNull(1)?.lowercase()
            ?.takeIf { it in supportedSourceLanguages }
    } else null
    val detected = commandExtension ?: when {
        Regex("(?m)^\\s*(?:package\\s+[\\w.]+|data\\s+class\\s+|(?:public\\s+)?(?:class|interface|enum)\\s+|(?:internal\\s+|private\\s+)?fun\\s+|(?:val|var)\\s+\\w+").containsMatchIn(source) -> "kt"
        Regex("(?m)^\\s*(?:def\\s+\\w+|from\\s+\\w+\\s+import|import\\s+\\w+)").containsMatchIn(source) -> "py"
        Regex("(?m)^\\s*(?:export\\s+(?:default\\s+)?|(?:const|let|var)\\s+\\w+\\s*=|function\\s+\\w+|async\\s+function)").containsMatchIn(source) -> "js"
        else -> null
    }
    return when {
        detected != null -> highlightWorkspaceSource(source, detected)
        toolType == "commandExecution" -> highlightWorkspaceSource(source, "sh")
        else -> AnnotatedString(source)
    }
}

private val supportedSourceLanguages = setOf(
    "kt", "kts", "java", "js", "jsx", "ts", "tsx", "py", "sh", "bash", "zsh", "json", "xml", "html", "htm", "svg",
    "css", "scss", "sql", "yaml", "yml", "toml", "go", "rs", "c", "h", "cpp", "hpp"
)
private val literals = setOf("true", "false", "null", "none", "undefined", "nan")
private val kotlinKeywords = setOf("package", "import", "as", "class", "interface", "object", "data", "sealed", "enum", "annotation", "fun", "val", "var", "const", "lateinit", "override", "open", "abstract", "private", "protected", "public", "internal", "inline", "suspend", "return", "if", "else", "when", "for", "while", "do", "break", "continue", "try", "catch", "finally", "throw", "is", "in", "out", "this", "super", "by", "where", "typealias", "expect", "actual")
private val javaKeywords = setOf("package", "import", "class", "interface", "enum", "record", "extends", "implements", "new", "return", "if", "else", "switch", "case", "for", "while", "do", "break", "continue", "try", "catch", "finally", "throw", "throws", "this", "super", "void", "boolean", "byte", "short", "int", "long", "float", "double", "char", "public", "protected", "private", "static", "final", "abstract", "synchronized", "volatile", "transient", "native", "instanceof", "var")
private val jsKeywords = setOf("import", "export", "from", "as", "class", "extends", "new", "this", "super", "function", "return", "if", "else", "switch", "case", "for", "while", "do", "break", "continue", "try", "catch", "finally", "throw", "const", "let", "var", "async", "await", "yield", "typeof", "instanceof", "in", "of", "delete", "void", "interface", "type", "implements", "public", "private", "protected", "static", "readonly")
private val pythonKeywords = setOf("import", "from", "as", "class", "def", "return", "if", "elif", "else", "for", "while", "break", "continue", "try", "except", "finally", "raise", "with", "yield", "lambda", "pass", "global", "nonlocal", "assert", "del", "async", "await", "and", "or", "not", "is", "in")
private val shellKeywords = setOf("if", "then", "elif", "else", "fi", "for", "while", "until", "do", "done", "case", "esac", "function", "in", "select", "time", "coproc", "export", "local", "readonly", "return", "exit", "source")
private val xmlKeywords = setOf("doctype", "cdata", "true", "false", "null")
private val cssKeywords = setOf("import", "media", "supports", "keyframes", "important", "inherit", "initial", "unset", "none", "block", "flex", "grid", "absolute", "relative", "fixed")
private val sqlKeywords = setOf("SELECT", "FROM", "WHERE", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON", "AS", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "ALTER", "DROP", "TABLE", "INDEX", "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "NOT", "NULL", "AND", "OR", "ORDER", "BY", "GROUP", "HAVING", "LIMIT", "OFFSET", "DISTINCT", "UNION", "ALL", "CASE", "WHEN", "THEN", "ELSE", "END", "BEGIN", "COMMIT", "ROLLBACK", "TRANSACTION")
private val configKeywords = setOf("true", "false", "null", "yes", "no", "on", "off")
private val goKeywords = setOf("package", "import", "func", "return", "if", "else", "switch", "case", "for", "range", "break", "continue", "go", "defer", "select", "chan", "var", "const", "type", "struct", "interface", "map", "make", "new", "fallthrough")
private val rustKeywords = setOf("use", "mod", "pub", "crate", "self", "super", "fn", "let", "mut", "const", "static", "struct", "enum", "trait", "impl", "type", "where", "match", "if", "else", "loop", "while", "for", "in", "break", "continue", "return", "move", "async", "await", "dyn", "ref", "unsafe", "extern", "as")
private val cKeywords = setOf("include", "define", "ifdef", "ifndef", "endif", "class", "struct", "enum", "union", "namespace", "using", "template", "typename", "auto", "const", "static", "extern", "inline", "virtual", "public", "private", "protected", "return", "if", "else", "switch", "case", "for", "while", "do", "break", "continue", "try", "catch", "throw", "new", "delete", "void", "int", "long", "short", "float", "double", "char", "bool", "unsigned", "signed")
