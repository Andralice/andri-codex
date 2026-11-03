package com.codexserver.mobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GlobalInstructionsCheck {
    @Test fun usesServerUserConfigInsteadOfAssumingSshHome() {
        fun config(path: String) = JSONObject().put("layers", JSONArray()
            .put(JSONObject().put("name", JSONObject().put("type", "project").put("dotCodexFolder", "/work/.codex")))
            .put(JSONObject().put("name", JSONObject().put("type", "user").put("file", path))))
        assertEquals("/mnt/private/custom-codex", globalInstructionsHome(config("/mnt/private/custom-codex/config.toml")))
        for (bad in listOf("relative/config.toml", "/home/../config.toml", "/home/other.txt"))
            assertTrue(runCatching { globalInstructionsHome(config(bad)) }.isFailure)
        assertTrue(runCatching { globalInstructionsHome(JSONObject()) }.isFailure)
    }

    @Test fun editsEffectiveOverrideAndHandlesClearOrMissingFile() {
        val override = selectGlobalInstructions("/custom", "覆盖要求", "项目默认")
        assertEquals("/custom/AGENTS.override.md", override.path)
        assertEquals("覆盖要求", override.content)
        assertTrue(override.exists)
        val fallback = selectGlobalInstructions("/custom", "", "原有内容\n")
        assertEquals("/custom/AGENTS.md", fallback.path)
        assertEquals("原有内容\n", fallback.content)
        assertFalse(selectGlobalInstructions("/custom", null, null).exists)
        assertTrue(selectGlobalInstructions("/custom", null, "").exists)
    }

    @Test fun unicodeRoundTripIsExactAndSizeLimitCountsBytes() {
        val text = "默认中文，保留修改。🐻\n原样保留 $ ` ' \"\n"
        assertEquals(text, decodeGlobalInstructions(encodeGlobalInstructions(text)))
        assertEquals("", decodeGlobalInstructions(encodeGlobalInstructions("")))
        assertEquals(MAX_GLOBAL_INSTRUCTION_BYTES, encodeGlobalInstructions("a".repeat(MAX_GLOBAL_INSTRUCTION_BYTES)).size)
        assertTrue(runCatching { encodeGlobalInstructions("中".repeat(MAX_GLOBAL_INSTRUCTION_BYTES / 3 + 1)) }.isFailure)
        assertTrue(runCatching { decodeGlobalInstructions(byteArrayOf(0xc3.toByte(), 0x28)) }.isFailure)
        assertTrue(runCatching { encodeGlobalInstructions("bad\u0000text") }.isFailure)
    }

    @Test fun rejectsConcurrentChangesIncludingDeletionCreationAndNewOverride() {
        val original = selectGlobalInstructions("/custom", null, "原始内容")
        checkGlobalInstructionsUnchanged(original, original.copy())
        val changes = listOf(original.copy(content = "其他人改了"), original.copy(exists = false),
            selectGlobalInstructions("/custom", "新覆盖", original.content), original.copy(home = "/other"))
        changes.forEach { assertTrue(runCatching { checkGlobalInstructionsUnchanged(original, it) }.isFailure) }
        val missing = selectGlobalInstructions("/custom", null, null)
        assertTrue(runCatching { checkGlobalInstructionsUnchanged(missing, missing.copy(exists = true)) }.isFailure)
    }
}
