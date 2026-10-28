package com.nfcwriter.app.data.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant

internal fun cloudRowToLocal(row: JsonObject): JsonObject = buildJsonObject {
    row.forEach { (key, value) ->
        val localKey = when (key) {
            "content" -> "contentJson"
            "previous_content" -> "previousContentJson"
            "new_content" -> "newContentJson"
            else -> key.replace(Regex("_([a-z])")) { match -> match.groupValues[1].uppercase() }
        }
        val localValue = when {
            key.endsWith("_at") && value is JsonPrimitive && value.isString ->
                JsonPrimitive(Instant.parse(value.content).toEpochMilli())
            key in setOf("content", "previous_content", "new_content") && value !is JsonNull ->
                JsonPrimitive(value.toString())
            else -> value
        }
        put(localKey, localValue)
    }
    put("syncState", JsonPrimitive("synced"))
}
