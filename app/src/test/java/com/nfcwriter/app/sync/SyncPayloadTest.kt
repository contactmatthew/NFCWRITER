package com.nfcwriter.app.sync

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPayloadTest {
    @Test
    fun `optional nulls and physical mock marker are omitted`() {
        val payload = buildJsonObject {
            put("id", JsonPrimitive("history-1"))
            put("newContentType", JsonNull)
            put("oldContentType", JsonNull)
            put("isMock", JsonPrimitive(false))
            put("syncState", JsonPrimitive("pending"))
        }.toSnakeCasePayload()

        assertEquals("history-1", payload["id"]?.jsonPrimitive?.content)
        assertFalse("new_content_type" in payload)
        assertFalse("old_content_type" in payload)
        assertFalse("is_mock" in payload)
        assertFalse("sync_state" in payload)
    }

    @Test
    fun `enabled mock marker is preserved for development sync`() {
        val payload = buildJsonObject {
            put("id", JsonPrimitive("mock-1"))
            put("isMock", JsonPrimitive(true))
        }.toSnakeCasePayload()

        assertTrue(payload["is_mock"]?.jsonPrimitive?.booleanOrNull == true)
    }
}
