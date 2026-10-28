package com.nfcwriter.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nfcwriter.app.NfcWriterApplication
import com.nfcwriter.app.data.EncryptedSecret
import com.nfcwriter.app.data.local.*
import com.nfcwriter.app.domain.NfcPayload
import com.nfcwriter.app.nfc.TagSnapshot
import com.nfcwriter.app.nfc.Ntag21xVariant
import com.nfcwriter.app.nfc.NtagCredential
import com.nfcwriter.app.nfc.VerifiedWriteResult
import com.nfcwriter.app.nfc.previousManagedUrlForRewrite
import com.nfcwriter.app.nfc.managedUrlReferenceForRewrite
import com.nfcwriter.app.sync.SyncWorker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.UUID

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as NfcWriterApplication
    val projects = app.repository.projects.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tags = app.repository.tags.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val history = app.repository.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val batches = app.repository.batches.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pending = app.repository.pendingCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    fun userId() = app.auth.currentUserId() ?: "offline-${UUID.nameUUIDFromBytes("local-user".toByteArray())}"
    fun accessLabel() = app.auth.label()
    fun isAuthenticated() = app.auth.isAuthenticated()
    fun isSuperAdmin() = app.auth.isSuperAdmin()
    fun isAccessManager() = app.auth.isAccessManager()
    fun accessRole() = app.auth.role()
    suspend fun restoreAccessSession() = app.auth.restoreSession()
    fun configured() = com.nfcwriter.app.data.remote.SupabaseServices.configured
    fun writerSiteOrigin() = com.nfcwriter.app.BuildConfig.TAPTAG_SITE_URL
    fun saveProject(name: String, description: String, category: String) = viewModelScope.launch { app.repository.saveProject(ProjectEntity(userId = userId(), name = name.trim(), description = description.trim().ifBlank { null }, category = category)) }
    suspend fun recordVerifiedWrite(snapshot: TagSnapshot, payload: NfcPayload, operation: String, previous: TagSnapshot?, name: String? = null, projectId: String? = null): VerifiedWriteResult {
        val now = System.currentTimeMillis()
        val existing = snapshot.uid?.let { app.repository.tagByUid(userId(), it) }
        val effectiveProjectId = projectId ?: existing?.projectId
        val oldContentType = existing?.ndefType ?: previous?.let(::snapshotContentType)
        val oldContentJson = existing?.contentJson ?: previous?.let { app.json.encodeToString(it.records) }
        val tag = existing?.copy(
            projectId = effectiveProjectId,
            name = name ?: existing.name,
            tagType = snapshot.tagType,
            technology = snapshot.technologies.joinToString(","),
            capacity = snapshot.capacity,
            ndefType = payload.type,
            contentJson = payload.redactedJson(),
            securityStatus = if (snapshot.writable == false) "permanently_locked" else if (snapshot.securityState == "protected") "protected" else "unprotected",
            updatedAt = now,
            lastWrittenAt = now,
            lastVerifiedAt = now,
            syncState = "pending",
            isMock = snapshot.isMock,
        ) ?: NfcTagEntity(userId = userId(), projectId = effectiveProjectId, name = name ?: snapshot.records.firstOrNull()?.content?.take(48), tagUid = snapshot.uid, tagType = snapshot.tagType, technology = snapshot.technologies.joinToString(","), capacity = snapshot.capacity, ndefType = payload.type, contentJson = payload.redactedJson(), securityStatus = if (snapshot.writable == false) "permanently_locked" else if (snapshot.securityState == "protected") "protected" else "unprotected", lastWrittenAt = now, lastVerifiedAt = now, isMock = snapshot.isMock)
        if (payload is NfcPayload.Wifi && !payload.password.isNullOrEmpty()) app.secrets.put("wifi:${tag.id}", payload.password.toCharArray())
        app.repository.saveTag(tag)
        app.repository.addHistory(HistoryEntity(userId = userId(), tagId = tag.id, projectId = effectiveProjectId, operation = operation, payloadType = payload.type, oldContentType = if (operation == "rewrite") oldContentType else null, newContentType = payload.type, previousContentJson = if (operation == "rewrite") oldContentJson else null, newContentJson = payload.redactedJson(), tagUid = snapshot.uid, writeSuccess = true, verificationSuccess = true, isMock = snapshot.isMock))
        SyncWorker.now(app)
        val managedUrl = (payload as? NfcPayload.UriPayload)?.uri?.takeIf { Regex("/t/[A-HJ-NP-Z2-9]{7,16}(?:[/?#]|$)",RegexOption.IGNORE_CASE).containsMatchIn(it) }
        val previousManagedUrl = previousManagedUrlForRewrite(operation, previous)
        if (snapshot.isMock || (managedUrl == null && previousManagedUrl == null)) return VerifiedWriteResult(tag.id)
        if (!isSuperAdmin()) return VerifiedWriteResult(tag.id,provisioningError="NFC content was verified, but customer access requires a super-admin session.")
        val uid = snapshot.uid ?: return VerifiedWriteResult(tag.id,provisioningError="NFC content was verified, but this phone did not expose the card UID.")
        if (managedUrl == null) {
            return runCatching { app.auth.retireAfterVerifiedRewrite(requireNotNull(previousManagedUrl), uid) }
                .fold(onSuccess = { VerifiedWriteResult(tag.id) },
                    onFailure = { VerifiedWriteResult(tag.id,provisioningError=it.message ?: "The old TapTag URL could not be retired.") })
        }
        return runCatching {
            val rewriteReference = managedUrlReferenceForRewrite(operation, previous, managedUrl)
            if (rewriteReference != null) app.auth.provisionAfterVerifiedRewrite(managedUrl,uid,snapshot.tagType,rewriteReference)
            else app.auth.provisionAfterVerifiedWrite(managedUrl,uid,snapshot.tagType)
        }
            .fold(
                onSuccess={ provisioned ->
                    val next = runCatching { app.auth.prepareNextMessageCard(managedUrl, uid) }
                    VerifiedWriteResult(
                        tagId = tag.id,
                        customerAccessCode = provisioned.accessCode,
                        permanentUrl = provisioned.permanentUrl,
                        nextMessageUrl = next.getOrNull()?.nextUrl,
                        messageOwnerCode = next.getOrNull()?.currentOwnerCode,
                        nextCardError = next.exceptionOrNull()?.message,
                    )
                },
                onFailure={failure->VerifiedWriteResult(tag.id,provisioningError=failure.message?:"Customer access could not be generated.")},
            )
    }

    suspend fun managedTagIdForUid(uid: String): String? = app.repository.tagByUid(userId(), uid)?.id

    suspend fun storeNfcCredential(uid: String, tagId: String, credential: NtagCredential, variant: Ntag21xVariant, mode: String) {
        val existing = app.repository.credentialByUid(userId(), uid)
        val credentialId = existing?.id ?: UUID.randomUUID().toString()
        val encrypted = app.secrets.encrypt(credential.encoded(), credentialId)
        val value = NfcCredentialEntity(
            id = credentialId,
            userId = userId(),
            tagId = tagId,
            tagUid = uid,
            credentialMode = mode,
            ciphertext = encrypted.ciphertext,
            nonceOrIv = encrypted.nonceOrIv,
            keyVersion = encrypted.keyVersion,
            credentialVersion = (existing?.credentialVersion ?: 0) + 1,
            chipVariant = variant.name,
            isMock = uid.startsWith("MOCK-"),
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        )
        app.repository.saveCredential(value)
        app.repository.tagByUid(userId(), uid)?.let { app.repository.saveTag(it.copy(credentialId = credentialId, credentialMode = mode, syncState = "pending")) }
    }

    suspend fun credentialForUid(uid: String): NtagCredential? {
        // Access/auth upgrades can change the local owner ID (for example, from the stable
        // offline ID to a Supabase anonymous user). The credential is still device-bound by
        // Android Keystore, so prefer the current owner but recover any locally decryptable
        // credential for this exact physical UID left by an earlier app version/session.
        for (stored in app.repository.credentialCandidatesByUid(userId(), uid)) {
            val decoded = runCatching {
                val bytes = app.secrets.decrypt(
                    EncryptedSecret(stored.ciphertext, stored.nonceOrIv, stored.keyVersion),
                    stored.id,
                )
                try { NtagCredential.decode(bytes) } finally { bytes.fill(0) }
            }.getOrNull()
            if (decoded != null) return decoded
        }
        return null
    }

    fun forgetLocalCredential(uid: String) = viewModelScope.launch { app.repository.forgetCredentialLocally(userId(), uid) }

    suspend fun recordSecurityResult(uid: String, status: String, operation: String, error: String?) {
        val now = System.currentTimeMillis()
        val tag = app.repository.tagByUid(userId(), uid)
        if (tag != null) app.repository.saveTag(tag.copy(securityStatus = status, lastSecurityVerifiedAt = if (error == null) now else tag.lastSecurityVerifiedAt, updatedAt = now, syncState = "pending"))
        app.repository.addHistory(HistoryEntity(userId = userId(), tagId = tag?.id, projectId = tag?.projectId, operation = operation, payloadType = tag?.ndefType ?: "unknown", tagUid = uid, writeSuccess = error == null, verificationSuccess = error == null, errorMessage = error, isMock = uid.startsWith("MOCK-")))
        SyncWorker.now(app)
    }
    fun recordOperation(operation: String, snapshot: TagSnapshot?, write: Boolean, verified: Boolean, error: String?) = viewModelScope.launch {
        app.repository.addHistory(HistoryEntity(userId = userId(), operation = operation, payloadType = snapshot?.records?.firstOrNull()?.type ?: "unknown", tagUid = snapshot?.uid, writeSuccess = write, verificationSuccess = verified, errorMessage = error, isMock = snapshot?.isMock == true))
    }
    fun createBatch(payload: NfcPayload, quantity: Int, isMock: Boolean = false) = viewModelScope.launch { app.repository.saveBatch(BatchEntity(userId = userId(), payloadType = payload.type, contentJson = payload.redactedJson(), quantity = quantity, status = "in_progress", isMock = isMock)) }
    fun payloadFor(tag: NfcTagEntity): NfcPayload? = runCatching {
        val value = app.json.decodeFromString<NfcPayload>(tag.contentJson)
        if (value is NfcPayload.Wifi && value.password == "__DEVICE_SECRET__") value.copy(password = app.secrets.get("wifi:${tag.id}")?.concatToString()) else value
    }.getOrNull()
    fun syncNow() = SyncWorker.now(app)
    suspend fun unlock(accessKey: String) = app.auth.authenticate(accessKey)
    suspend fun createAccessKey(label: String, role: String) = app.auth.createAccessKey(label, role)
    suspend fun listAccessKeys() = app.auth.listAccessKeys()
    suspend fun listWriterManagedUrls() = app.auth.listWriterManagedUrls()
    suspend fun prepareNextMessageCard(url: String, uid: String) = app.auth.prepareNextMessageCard(url, uid)
    suspend fun checkManagedUrlWrite(url: String, uid: String, previousUrl: String?) {
        if (previousUrl != null) app.auth.checkManagedUrlRewrite(url, uid, previousUrl)
        else app.auth.checkManagedUrlWrite(url, uid)
    }
    suspend fun manageAccessKey(keyId: String, action: String) = app.auth.manageAccessKey(keyId, action)
    suspend fun editAccessKey(keyId: String, label: String, role: String) = app.auth.editAccessKey(keyId, label, role)
    suspend fun rotateAccessKey(keyId: String) = app.auth.rotateAccessKey(keyId)
    suspend fun signOut() = app.auth.signOut()

    private fun snapshotContentType(snapshot: TagSnapshot): String = when (snapshot.records.firstOrNull()?.type?.lowercase()) {
        "uri" -> "uri"
        "text" -> "text"
        "text/vcard" -> "contact"
        "application/vnd.wfa.wsc" -> "wifi"
        else -> "unknown"
    }
}
