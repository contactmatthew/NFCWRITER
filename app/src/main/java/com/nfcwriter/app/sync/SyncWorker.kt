package com.nfcwriter.app.sync

import android.content.Context
import androidx.work.*
import com.nfcwriter.app.BuildConfig
import com.nfcwriter.app.NfcWriterApplication
import com.nfcwriter.app.nfc.MockCloudFault
import com.nfcwriter.app.nfc.MockNfcConfiguration
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as NfcWriterApplication
        if (!app.auth.currentUserId().isNullOrBlank()) {
            for (mutation in app.database.dao().pending()) {
                try {
                    val payload = app.json.parseToJsonElement(mutation.payloadJson).jsonObject.toSnakeCasePayload()
                    val isMock = payload["is_mock"]?.jsonPrimitive?.booleanOrNull == true
                    if (isMock && BuildConfig.DEBUG) {
                        if (!BuildConfig.MOCK_SYNC_ENABLED) {
                            // This can exist after upgrading from a build that queued local-only
                            // mock records. Remove only the queue entry; keep the local test data.
                            app.database.dao().deleteMutation(mutation.id)
                            continue
                        }
                        val configuration = applicationContext
                            .getSharedPreferences("mock_nfc_state", Context.MODE_PRIVATE)
                            .getString("configuration", null)
                            ?.let { runCatching { app.json.decodeFromString<MockNfcConfiguration>(it) }.getOrNull() }
                        val reason = when {
                            configuration?.enabled != true -> null
                            configuration.cloudFault == MockCloudFault.OFFLINE -> "Mock offline mode"
                            configuration.cloudFault == MockCloudFault.SUPABASE_UNAVAILABLE -> "Mock Supabase unavailable"
                            configuration.cloudFault == MockCloudFault.SYNC_FAILURE -> "Mock cloud sync failure"
                            else -> null
                        }
                        if (reason != null) {
                            app.database.dao().updateMutation(mutation.id, "pending", reason)
                            return Result.success()
                        }
                    }
                    if (mutation.tableName == "nfc_write_history") app.remote.insertHistory(payload) else app.remote.upsert(mutation.tableName, payload)
                    app.database.dao().updateMutation(mutation.id, "synced", null)
                } catch (e: Exception) {
                    app.database.dao().updateMutation(mutation.id, "pending", e.message)
                    return if (runAttemptCount >= 8) Result.failure() else Result.retry()
                }
            }
            app.database.dao().clearSynced()
            try {
                app.repository.importWorkspace(app.remote)
            } catch (e: Exception) {
                return if (runAttemptCount >= 8) Result.failure() else Result.retry()
            }
        }
        return Result.success()
    }
    companion object {
        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("supabase-sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }
        fun now(context: Context) { WorkManager.getInstance(context).enqueueUniqueWork("supabase-sync-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()) }
    }
}

internal fun JsonObject.toSnakeCasePayload(): JsonObject = JsonObject(entries.mapNotNull { (key, original) ->
    // PostgREST validates every requested column, including explicit nulls. Leaving optional
    // null fields out keeps ordinary records compatible while additive migrations roll out.
    if (original is JsonNull) return@mapNotNull null
    var remoteKey = key.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
    if (remoteKey == "sync_state") return@mapNotNull null
    if (remoteKey == "is_mock" && original is JsonPrimitive && original.booleanOrNull == false) return@mapNotNull null
    if (remoteKey in setOf("content_json", "previous_content_json", "new_content_json")) remoteKey = remoteKey.removeSuffix("_json")
    val value = when {
        remoteKey in setOf("created_at", "updated_at", "last_written_at", "last_verified_at", "last_security_verified_at", "completed_at") && original is JsonPrimitive && original.longOrNull != null -> JsonPrimitive(Instant.ofEpochMilli(original.long).toString())
        remoteKey in setOf("content", "previous_content", "new_content") && original is JsonPrimitive && original.isString -> runCatching { Json.parseToJsonElement(original.content) }.getOrElse { JsonNull }
        else -> original
    }
    remoteKey to value
}.toMap())
