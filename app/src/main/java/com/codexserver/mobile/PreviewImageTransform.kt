package com.codexserver.mobile

import kotlin.math.min

internal data class PreviewImageTransform(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f)

/** Keep the point under the fingers still while zooming, then constrain panning to the fitted image. */
internal fun transformPreviewImage(current: PreviewImageTransform, zoom: Float, panX: Float, panY: Float,
    focusX: Float, focusY: Float, viewportWidth: Float, viewportHeight: Float,
    imageWidth: Float, imageHeight: Float): PreviewImageTransform {
    if (viewportWidth <= 0f || viewportHeight <= 0f || imageWidth <= 0f || imageHeight <= 0f) return current
    val scale = (current.scale * zoom.takeIf { it.isFinite() && it > 0f }.let { it ?: 1f }).coerceIn(1f, 6f)
    if (scale == 1f) return PreviewImageTransform()
    val fit = min(viewportWidth / imageWidth, viewportHeight / imageHeight)
    val maxX = ((imageWidth * fit * scale - viewportWidth) / 2f).coerceAtLeast(0f)
    val maxY = ((imageHeight * fit * scale - viewportHeight) / 2f).coerceAtLeast(0f)
    val factor = scale / current.scale
    val x = (current.x + viewportWidth / 2f - focusX) * factor + focusX - viewportWidth / 2f + panX
    val y = (current.y + viewportHeight / 2f - focusY) * factor + focusY - viewportHeight / 2f + panY
    return PreviewImageTransform(scale, x.coerceIn(-maxX, maxX), y.coerceIn(-maxY, maxY))
}
