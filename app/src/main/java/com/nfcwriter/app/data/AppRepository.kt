package com.nfcwriter.app.data

import com.nfcwriter.app.BuildConfig
import com.nfcwriter.app.data.local.*
import com.nfcwriter.app.data.remote.SupabaseRemote
import com.nfcwriter.app.data.remote.cloudRowToLocal
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

class AppRepository(private val dao: AppDao, private val json: Json) {
    val projects: Flow<List<ProjectEntity>> = dao.projects()
    val tags: Flow<List<NfcTagEntity>> = dao.tags()
    val history: Flow<List<HistoryEntity>> = dao.history()
    val batches: Flow<List<BatchEntity>> = dao.batches()
    val pendingCount: Flow<Int> = dao.pendingCount(BuildConfig.MOCK_SYNC_ENABLED)

    suspend fun importWorkspace(remote: SupabaseRemote) {
        remote.readWorkspaceRows("projects").forEach { dao.importProject(json.decodeFromJsonElement(cloudRowToLocal(it))) }
        remote.readWorkspaceRows("nfc_tags").forEach { dao.importTag(json.decodeFromJsonElement(cloudRowToLocal(it))) }
        remote.readWorkspaceRows("batches").forEach { dao.importBatch(json.decodeFromJsonElement(cloudRowToLocal(it))) }
        remote.readWorkspaceRows("nfc_write_history").forEach { dao.insertHistory(json.decodeFromJsonElement(cloudRowToLocal(it))) }
    }

    suspend fun saveProject(project: ProjectEntity) { dao.upsertProject(project); queue("projects", project.id, json.encodeToString(project)) }
    suspend fun tagByUid(userId: String, uid: String): NfcTagEntity? = dao.tagByUid(userId, uid)
    suspend fun credentialByUid(userId: String, uid: String): NfcCredentialEntity? = dao.credentialByUid(userId, uid)
    suspend fun credentialCandidatesByUid(preferredUserId: String, uid: String): List<NfcCredentialEntity> =
        dao.credentialCandidatesByUid(preferredUserId, uid)
    suspend fun forgetCredentialLocally(userId: String, uid: String) = dao.forgetCredentialLocally(userId, uid)
    suspend fun saveTag(tag: NfcTagEntity) { dao.upsertTag(tag); if (shouldSync(tag.isMock)) queue("nfc_tags", tag.id, json.encodeToString(tag)) }
    suspend fun saveCredential(value: NfcCredentialEntity) { dao.upsertCredential(value); if (shouldSync(value.isMock)) queue("nfc_credentials", value.id, json.encodeToString(value)) }
    suspend fun addHistory(history: HistoryEntity) {
        if (dao.insertHistory(history) != -1L && shouldSync(history.isMock)) queue("nfc_write_history", history.syncId, json.encodeToString(history))
    }
    suspend fun saveBatch(batch: BatchEntity) { dao.upsertBatch(batch); if (shouldSync(batch.isMock)) queue("batches", batch.id, json.encodeToString(batch)) }
    suspend fun addBatchItem(item: BatchItemEntity): Boolean {
        if (item.tagUid != null && dao.batchUidCount(item.batchId, item.tagUid) > 0) return false
        return runCatching { dao.insertBatchItem(item); true }.getOrDefault(false)
    }
    private suspend fun queue(table: String, syncId: String, payload: String) {
        if (dao.refreshQueuedMutation(syncId, "upsert", payload) == 0) {
            dao.enqueue(PendingMutationEntity(syncId = syncId, tableName = table, operation = "upsert", payloadJson = payload))
        }
    }
    // Mock records remain device-local unless a development build explicitly enables
    // mock cloud sync. Local-only records must never look like stalled real uploads.
    private fun shouldSync(isMock: Boolean): Boolean = !isMock || BuildConfig.MOCK_SYNC_ENABLED
}
