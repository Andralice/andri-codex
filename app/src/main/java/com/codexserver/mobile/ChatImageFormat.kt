package com.codexserver.mobile

private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

internal fun isPngImage(bytes: ByteArray): Boolean =
    bytes.size >= pngSignature.size && pngSignature.indices.all { bytes[it] == pngSignature[it] }

private val svgDocumentHeader = Regex(
    """\A\s*+(?:(?><\?.*?\?>|<!--.*?-->|<!DOCTYPE\s+svg\b[^>\[]*(?:\[.*?]\s*)?>)\s*+)*+<svg(?=[\s/>])""",
    RegexOption.DOT_MATCHES_ALL
)

/** Only recognize an SVG document root. Raster metadata can contain embedded SVG. */
internal fun isSvgImage(bytes: ByteArray): Boolean {
    if (bytes.isEmpty() || isPngImage(bytes)) return false
    val header = String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8).removePrefix("\uFEFF")
    return svgDocumentHeader.containsMatchIn(header)
}
