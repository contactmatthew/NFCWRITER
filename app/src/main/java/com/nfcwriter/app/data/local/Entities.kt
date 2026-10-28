package com.nfcwriter.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
@Entity(tableName = "projects", indices = [Index("userId"), Index("syncState")])
data class ProjectEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val name: String,
    val description: String? = null,
    val category: String = "other",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val syncState: String = "pending"
)

@Serializable
@Entity(tableName = "nfc_tags", indices = [Index("userId"), Index("projectId"), Index("tagUid"), Index("ndefType"), Index("syncState")])
data class NfcTagEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val projectId: String? = null,
    val name: String? = null,
    val physicalType: String? = null,
    val tagUid: String? = null,
    val tagType: String? = null,
    val technology: String? = null,
    val capacity: Int? = null,
    val ndefType: String,
    val contentJson: String,
    val status: String = "active",
    val securityStatus: String = "rewritable",
    val credentialId: String? = null,
    val credentialMode: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastWrittenAt: Long? = null,
    val lastVerifiedAt: Long? = null,
    val lastSecurityVerifiedAt: Long? = null,
    val syncState: String = "pending",
    val isMock: Boolean = false,
)

@Serializable
@Entity(
    tableName = "nfc_credentials",
    indices = [Index("userId"), Index("tagId"), Index(value = ["userId", "tagUid"], unique = true), Index("syncState")],
)
data class NfcCredentialEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val tagId: String,
    val tagUid: String,
    val credentialType: String = "ntag21x_pwd_pack",
    val credentialMode: String,
    val ciphertext: String,
    val nonceOrIv: String,
    val keyVersion: Int = 1,
    val credentialVersion: Int = 1,
    val chipVariant: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val syncState: String = "pending",
    val isMock: Boolean = false,
)

@Serializable
@Entity(tableName = "write_history", indices = [Index("userId"), Index("tagId"), Index("projectId"), Index(value = ["syncId"], unique = true), Index("createdAt")])
data class HistoryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val tagId: String? = null,
    val projectId: String? = null,
    val batchId: String? = null,
    val operation: String,
    val payloadType: String,
    val oldContentType: String? = null,
    val newContentType: String? = null,
    val previousContentJson: String? = null,
    val newContentJson: String? = null,
    val tagUid: String? = null,
    val writeSuccess: Boolean = false,
    val verificationSuccess: Boolean = false,
    val errorMessage: String? = null,
    val syncId: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val syncState: String = "pending",
    val isMock: Boolean = false,
)

@Serializable
@Entity(tableName = "batches", indices = [Index("userId"), Index("projectId"), Index("syncState")])
data class BatchEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val projectId: String? = null,
    val payloadType: String,
    val contentJson: String,
    val quantity: Int,
    val completed: Int = 0,
    val failed: Int = 0,
    val duplicates: Int = 0,
    val status: String = "pending",
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val syncState: String = "pending",
    val isMock: Boolean = false,
)

@Entity(tableName = "batch_items", indices = [Index("batchId"), Index("userId"), Index(value = ["batchId", "tagUid"], unique = true)])
data class BatchItemEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val batchId: String,
    val userId: String,
    val tagId: String? = null,
    val tagUid: String? = null,
    val status: String,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val isMock: Boolean = false,
)

@Entity(tableName = "pending_mutations", indices = [Index(value = ["syncId"], unique = true), Index("state"), Index("createdAt")])
data class PendingMutationEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val syncId: String,
    val tableName: String,
    val operation: String,
    val payloadJson: String,
    val attempts: Int = 0,
    val state: String = "pending",
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
