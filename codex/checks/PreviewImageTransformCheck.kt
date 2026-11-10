package com.codexserver.mobile

import org.junit.Assert.*
import org.junit.Test

class PreviewImageTransformCheck {
    private fun gesture(current: PreviewImageTransform = PreviewImageTransform(), zoom: Float = 1f,
        x: Float = 0f, y: Float = 0f, focusX: Float = 200f, focusY: Float = 400f,
        imageWidth: Float = 400f, imageHeight: Float = 800f) =
        transformPreviewImage(current, zoom, x, y, focusX, focusY, 400f, 800f, imageWidth, imageHeight)

    @Test fun offCenterPinchKeepsThePointUnderTheFingersStill() {
        val result = gesture(zoom = 2f, focusX = 100f, focusY = 300f)
        assertEquals(2f, result.scale, .001f)
        assertEquals(100f, result.x, .001f)
        assertEquals(100f, result.y, .001f)
        // The pixel that was at (100, 300) stays at (100, 300) after scaling about the center.
        assertEquals(100f, (100f - 200f) * result.scale + 200f + result.x, .001f)
        assertEquals(300f, (300f - 400f) * result.scale + 400f + result.y, .001f)
    }

    @Test fun draggingCannotMoveTheImageBeyondItsEdges() {
        val enlarged = gesture(zoom = 3f)
        val rightDown = gesture(enlarged, x = 100_000f, y = 100_000f)
        assertEquals(400f, rightDown.x, .001f)
        assertEquals(800f, rightDown.y, .001f)
        val leftUp = gesture(rightDown, x = -100_000f, y = -100_000f)
        assertEquals(-400f, leftUp.x, .001f)
        assertEquals(-800f, leftUp.y, .001f)
    }

    @Test fun letterboxedImagesOnlyPanWhereTheScaledImageExceedsTheScreen() {
        val landscape = gesture(zoom = 2f, x = 1000f, y = 1000f, imageWidth = 1600f, imageHeight = 900f)
        assertEquals(200f, landscape.x, .001f)
        assertEquals(0f, landscape.y, .001f)
        val portrait = gesture(zoom = 2f, x = 1000f, y = 1000f, imageWidth = 100f, imageHeight = 800f)
        assertEquals(0f, portrait.x, .001f)
        assertEquals(400f, portrait.y, .001f)
    }

    @Test fun zoomingBackToFitResetsOffsetsAndZoomCannotExceedSixTimes() {
        val enlarged = gesture(zoom = 100f, x = 1000f, y = 1000f)
        assertEquals(6f, enlarged.scale, .001f)
        assertEquals(PreviewImageTransform(), gesture(enlarged, zoom = .001f))
        assertEquals(PreviewImageTransform(), gesture(zoom = Float.NaN))
    }

    @Test fun rotatingTheViewportClampsAnExistingPanToTheNewImageBounds() {
        val before = gesture(zoom = 3f, x = 400f, y = 800f)
        val after = transformPreviewImage(before, 1f, 0f, 0f, 400f, 200f, 800f, 400f, 400f, 800f)
        assertEquals(3f, after.scale, .001f)
        assertEquals(0f, after.x, .001f)
        assertEquals(400f, after.y, .001f)
    }
}
