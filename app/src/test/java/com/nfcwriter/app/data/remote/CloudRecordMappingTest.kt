package com.nfcwriter.app.data.remote

import com.nfcwriter.app.data.local.HistoryEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudRecordMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun mapsCloudHistoryToLocalHistoryWithoutLosingPayloadOrTimestamp() {
        val row = json.parseToJsonElement("""{
          "id":"001", "user_id":"owner", "operation":"write", "payload_type":"uri",
          "sync_id":"sync-001", "new_content":{"kind":"uri","uri":"https://example.com"},
          "created_at":"2026-10-24T00:00:00Z", "write_success":true,
          "verification_success":true
        }""").jsonObject
        val history = json.decodeFromJsonElement<HistoryEntity>(cloudRowToLocal(row))
        assertEquals("owner", history.userId)
        assertEquals("https://example.com", json.parseToJsonElement(history.newContentJson!!).jsonObject["uri"]?.toString()?.trim('"'))
        assertEquals(1792800000000L, history.createdAt)
        assertEquals("synced", history.syncState)
    }
}
