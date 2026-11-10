package com.codexserver.mobile

import java.util.Base64
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatImageFormatCheck {
    @Test fun pngWithSvgInProvenanceChunkStaysOnRasterDecoder() {
        val png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAAMmNhQlhtZXRhZGF0YSA8c3ZnIHhtbG5z" +
                "PSJodHRwOi8vd3d3LnczLm9yZy8yMDAwL3N2ZyIvPp7DJdgAAAAMSURBVHicY/jPwAAAAwEBAMn+ku8AAAAASUVORK5CYII="
        )
        assertTrue(png.toString(Charsets.UTF_8).contains("<svg "))
        assertTrue(isPngImage(png))
        assertFalse(isSvgImage(png))
    }

    @Test fun svgMustBeTheRootElement() {
        assertTrue(isSvgImage("<svg/>".toByteArray()))
        val withProlog = "\uFEFF<?xml version=\"1.0\"?>\n<!-- preview -->\n" +
            "<!DOCTYPE svg [<!ENTITY name \"test\">]>\n<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"
        assertTrue(isSvgImage(withProlog.toByteArray()))
        assertFalse(isSvgImage("<html><body><svg /></body></html>".toByteArray()))
        assertFalse(isSvgImage("<!-- <svg> --> <not-svg />".toByteArray()))
    }
}
