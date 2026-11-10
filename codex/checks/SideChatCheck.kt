package com.codexserver.mobile

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SideChatCheck {
    @Test fun verifiedTemporaryForkReturnsItsOwnThreadId() {
        val result = resultFor("side-1", parent = "main-1", ephemeral = true)
        assertEquals("side-1", requireSideChatFork("main-1", result))
    }

    @Test fun aSideQuestionCannotBeRoutedToTheMainThreadOrAnUnrelatedFork() {
        assertThrows(IllegalStateException::class.java) {
            requireSideChatFork("main-1", resultFor("main-1", parent = "main-1", ephemeral = true))
        }
        assertThrows(IllegalStateException::class.java) {
            requireSideChatFork("main-1", resultFor("side-1", parent = "main-2", ephemeral = true))
        }
        assertThrows(IllegalStateException::class.java) {
            requireSideChatFork("main-1", resultFor("side-1", parent = "main-1", ephemeral = false))
        }
    }

    private fun resultFor(id: String, parent: String, ephemeral: Boolean) = JSONObject().put(
        "thread", JSONObject().put("id", id).put("forkedFromId", parent).put("ephemeral", ephemeral)
    )
}
