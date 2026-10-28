package com.nfcwriter.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC") fun projects(): Flow<List<ProjectEntity>>
    @Query("SELECT * FROM nfc_tags ORDER BY updatedAt DESC") fun tags(): Flow<List<NfcTagEntity>>
    @Query("SELECT * FROM write_history ORDER BY createdAt DESC") fun history(): Flow<List<HistoryEntity>>
    @Query("SELECT * FROM batches ORDER BY createdAt DESC") fun batches(): Flow<List<BatchEntity>>
    @Query("SELECT * FROM batch_items WHERE batchId=:batchId ORDER BY createdAt") fun batchItems(batchId: String): Flow<List<BatchItemEntity>>
    @Query("SELECT COUNT(*) FROM pending_mutations WHERE state != 'synced' AND (:includeMock OR payloadJson NOT LIKE '%\"isMock\":true%')")
    fun pendingCount(includeMock: Boolean): Flow<Int>
    @Query("SELECT * FROM pending_mutations WHERE state='pending' ORDER BY createdAt LIMIT :limit") suspend fun pending(limit: Int = 50): List<PendingMutationEntity>
    @Query("SELECT COUNT(*) FROM batch_items WHERE batchId=:batchId AND tagUid=:uid") suspend fun batchUidCount(batchId: String, uid: String): Int
    @Query("SELECT * FROM nfc_tags WHERE userId=:userId AND tagUid=:uid ORDER BY updatedAt DESC LIMIT 1") suspend fun tagByUid(userId: String, uid: String): NfcTagEntity?
    @Query("SELECT * FROM nfc_credentials WHERE userId=:userId AND tagUid=:uid LIMIT 1") suspend fun credentialByUid(userId: String, uid: String): NfcCredentialEntity?
    @Query("""
        SELECT * FROM nfc_credentials
        WHERE tagUid=:uid
        ORDER BY CASE WHEN userId=:preferredUserId THEN 0 ELSE 1 END, updatedAt DESC
    """)
    suspend fun credentialCandidatesByUid(preferredUserId: String, uid: String): List<NfcCredentialEntity>
    @Query("DELETE FROM nfc_credentials WHERE userId=:userId AND tagUid=:uid") suspend fun forgetCredentialLocally(userId: String, uid: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertProject(value: ProjectEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun importProject(value: ProjectEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTag(value: NfcTagEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun importTag(value: NfcTagEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertCredential(value: NfcCredentialEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertHistory(value: HistoryEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertBatch(value: BatchEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun importBatch(value: BatchEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertBatchItem(value: BatchItemEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun enqueue(value: PendingMutationEntity): Long
    @Query("UPDATE pending_mutations SET payloadJson=:payload, operation=:operation, state='pending', lastError=NULL WHERE syncId=:syncId") suspend fun refreshQueuedMutation(syncId: String, operation: String, payload: String): Int
    @Query("UPDATE pending_mutations SET state=:state, attempts=attempts+1, lastError=:error WHERE id=:id") suspend fun updateMutation(id: String, state: String, error: String?)
    @Query("DELETE FROM pending_mutations WHERE id=:id") suspend fun deleteMutation(id: String)
    @Query("DELETE FROM pending_mutations WHERE state='synced'") suspend fun clearSynced()
    @Query("DELETE FROM projects WHERE id=:id") suspend fun deleteProject(id: String)
    @Query("DELETE FROM nfc_tags WHERE id=:id") suspend fun deleteTag(id: String)
}
