package com.nfcwriter.app.nfc

import android.app.Activity
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Build
import com.nfcwriter.app.BuildConfig
import com.nfcwriter.app.domain.NfcPayload
import com.nfcwriter.app.domain.decodeNdefText
import com.nfcwriter.app.domain.WritePreflightDecision
import com.nfcwriter.app.domain.decideWritePreflight
import com.nfcwriter.app.domain.isExpectedTag
import com.nfcwriter.app.domain.ndefMessageMatchesPayload
import com.nfcwriter.app.domain.normalizedForWrite
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.util.Locale

enum class NfcAvailability { READY, DISABLED, UNSUPPORTED }
enum class NfcStage {
    IDLE, READY, DETECTED, CHECKING, READING, AUTHENTICATING,
    AWAITING_CONFIRMATION, WRITING, VERIFYING, APPLYING_PROTECTION,
    VERIFYING_PROTECTION, ERASING, INTERRUPTED, CONNECTION_LOST,
    RECOVERY_REQUIRED, SUCCESS, SAFE_FAILURE, CANCELLED_BEFORE_WRITE, ERROR
}

@Serializable
data class TagSnapshot(
    val uid: String?, val technologies: List<String>, val tagType: String,
    val capacity: Int?, val writable: Boolean?, val canMakeReadOnly: Boolean,
    val records: List<DecodedRecord>, val rawMessage: ByteArray?,
    val securityState: String = "unknown", val securityVariant: String? = null,
    val securityReason: String? = null,
    val isMock: Boolean = false,
)
@Serializable
data class DecodedRecord(val type: String, val content: String)
internal fun previousManagedUrlForRewrite(operation: String, previous: TagSnapshot?): String? =
    if (operation == "rewrite") previous?.records?.firstOrNull {
        it.type == "URI" && Regex("/t/[A-HJ-NP-Z2-9]{7,16}(?:[/?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(it.content)
    }?.content else null
internal fun managedUrlReferenceForRewrite(operation: String, previous: TagSnapshot?, newUrl: String): String? =
    if (operation == "rewrite") previousManagedUrlForRewrite(operation, previous) ?: newUrl else null
data class VerifiedWriteResult(val tagId: String?, val customerAccessCode: String? = null, val permanentUrl: String? = null, val provisioningError: String? = null, val nextMessageUrl: String? = null, val messageOwnerCode: String? = null, val nextCardError: String? = null)
data class NfcUiState(
    val stage: NfcStage = NfcStage.IDLE,
    val message: String = "",
    val requiredBytes: Int? = null,
    val snapshot: TagSnapshot? = null,
    val previousSnapshot: TagSnapshot? = null,
    val expectedPayload: NfcPayload? = null,
    val verified: Boolean = false,
    val operation: String? = null,
    val credentialMode: String? = null,
    val isMock: Boolean = false,
    val interruptedStage: NfcStage? = null,
    val contentWritten: Boolean = false,
    val contentVerified: Boolean = false,
    val protectionRequested: Boolean = false,
    val protectionVerified: Boolean = false,
    val recoveryRetryAllowed: Boolean = false,
    val customerAccessCode: String? = null,
    val permanentUrl: String? = null,
    val provisioningError: String? = null,
    val nextMessageUrl: String? = null,
    val messageOwnerCode: String? = null,
    val nextCardError: String? = null,
)

val NfcUiState.isCriticalOperation: Boolean get() = stage in setOf(
    NfcStage.AUTHENTICATING,
    NfcStage.WRITING,
    NfcStage.VERIFYING,
    NfcStage.APPLYING_PROTECTION,
    NfcStage.VERIFYING_PROTECTION,
    NfcStage.ERASING,
)

sealed interface PendingNfcOperation {
    data object Read : PendingNfcOperation
    data class InspectThenWrite(val payload: NfcPayload, val protection: ProtectionRequest = ProtectionRequest.None) : PendingNfcOperation
    data class Write(val payload: NfcPayload, val protection: ProtectionRequest = ProtectionRequest.None) : PendingNfcOperation
    data class Verify(val expected: NfcPayload) : PendingNfcOperation
    data class VerifySnapshot(val expectedMessage: ByteArray, val expectedUid: String?) : PendingNfcOperation
    data class Erase(val expectedUid: String? = null) : PendingNfcOperation
    data class AwaitingWriteConfirmation(val expected: NfcPayload.Text, val expectedUid: String?, val inspected: TagSnapshot, val protection: ProtectionRequest = ProtectionRequest.None) : PendingNfcOperation
    data class ConfirmedWrite(val expected: NfcPayload.Text, val expectedUid: String?, val protection: ProtectionRequest = ProtectionRequest.None) : PendingNfcOperation
    data class AwaitingReplacement(val expected: NfcPayload, val previous: TagSnapshot, val protection: ProtectionRequest = ProtectionRequest.None) : PendingNfcOperation
    data class Rewrite(val expected: NfcPayload, val expectedUid: String?, val previous: TagSnapshot, val protection: ProtectionRequest = ProtectionRequest.None) : PendingNfcOperation
    data class VerifyWritten(val expected: NfcPayload, val expectedUid: String?, val previous: TagSnapshot?, val historyOperation: String, val protection: ProtectionRequest = ProtectionRequest.None) : PendingNfcOperation
    data class VerifyErased(val expectedUid: String?) : PendingNfcOperation
    /** Only armed after the UI completes all three irreversible confirmations. */
    data class PermanentReadOnly(val expectedUid: String) : PendingNfcOperation
    data class EnableProtection(val expectedUid: String) : PendingNfcOperation
    data class RemoveProtection(val expectedUid: String) : PendingNfcOperation
    data class ManualAuthenticate(val expectedUid: String, val credential: NtagCredential) : PendingNfcOperation
    data class RecoverWrite(val expected: NfcPayload, val expectedUid: String?, val previous: TagSnapshot?, val historyOperation: String, val protection: ProtectionRequest) : PendingNfcOperation
    data class RecoverProtection(val expectedUid: String, val expected: NfcPayload?, val historyOperation: String = "protection_enabled") : PendingNfcOperation
    data class RecoverErase(val expectedUid: String?) : PendingNfcOperation
}

class NfcEngine(private val activity: Activity) : NfcAdapter.ReaderCallback {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(NfcUiState())
    private val security = Ntag21xManager()
    private val mock = MockNfcOperations(activity)
    val state: StateFlow<NfcUiState> = _state
    val mockConfiguration: StateFlow<MockNfcConfiguration> = mock.configuration
    @Volatile private var pending: PendingNfcOperation? = null
    @Volatile private var activeOperationJob: Job? = null
    var onVerifiedWrite: (suspend (TagSnapshot, NfcPayload, String, TagSnapshot?) -> VerifiedWriteResult?)? = null
    var checkManagedUrlWrite: (suspend (String, String, String?) -> Unit)? = null
    var onOperationRecorded: ((String, TagSnapshot?, Boolean, Boolean, String?) -> Unit)? = null
    var credentialForUid: (suspend (String) -> NtagCredential?)? = null
    var managedTagIdForUid: (suspend (String) -> String?)? = null
    var onCredentialStored: (suspend (String, String, NtagCredential, Ntag21xVariant, String) -> Unit)? = null
    var onSecurityResult: (suspend (String, String, String, String?) -> Unit)? = null
    @Volatile var automaticCredentialRetrievalEnabled: Boolean = true

    val mockModeEnabled: Boolean get() = BuildConfig.DEBUG && mock.configuration.value.enabled
    fun setMockMode(enabled: Boolean) {
        check(BuildConfig.DEBUG) { "Mock NFC Mode is unavailable in release builds." }
        mock.setEnabled(enabled)
        if (enabled) disable() else enable()
        cancel()
    }
    fun setMockTagState(state: MockTagState) = mock.setState(state)
    fun setMockFault(fault: MockNfcFault) = mock.setFault(fault)
    fun setMockCloudFault(fault: MockCloudFault) = mock.setCloudFault(fault)
    fun setMockBatchFailureAt(index: Int) = mock.setBatchVerificationFailureAt(index)
    fun resetMockTag() = mock.reset()
    fun nextMockTag(index: Int? = null) {
        mock.nextTag()
        if (index != null && index == mock.configuration.value.batchVerificationFailureAt) mock.setFault(MockNfcFault.VERIFICATION_FAILURE)
    }
    fun simulateUnauthorizedWrite(): String = mock.simulateUnauthorizedWrite()

    fun onAppBackgrounded() {
        val current = _state.value
        if (!current.isCriticalOperation) return
        activeOperationJob?.cancel(CancellationException("App backgrounded during critical NFC operation"))
        _state.value = current.copy(
            stage = NfcStage.INTERRUPTED,
            interruptedStage = current.stage,
            message = "NFC OPERATION INTERRUPTED\n\nThe app was left before the operation could be safely confirmed.\n\nKeep the same NFC tag against your phone and scan again.",
        )
    }

    fun simulateMockBackgroundInterruption(stage: NfcStage) {
        if (!mockModeEnabled) return
        _state.value = _state.value.copy(stage = stage, isMock = true)
        onAppBackgrounded()
    }

    fun availability(): NfcAvailability = when { mockModeEnabled -> NfcAvailability.READY; adapter == null -> NfcAvailability.UNSUPPORTED; !adapter.isEnabled -> NfcAvailability.DISABLED; else -> NfcAvailability.READY }
    fun begin(operation: PendingNfcOperation) {
        val operation = operation.normalizePayloads()
        pending = operation
        val payload = when (operation) {
            is PendingNfcOperation.InspectThenWrite -> operation.payload
            is PendingNfcOperation.Write -> operation.payload
            is PendingNfcOperation.Verify -> operation.expected
            is PendingNfcOperation.AwaitingWriteConfirmation -> operation.expected
            is PendingNfcOperation.ConfirmedWrite -> operation.expected
            is PendingNfcOperation.AwaitingReplacement -> operation.expected
            is PendingNfcOperation.Rewrite -> operation.expected
            is PendingNfcOperation.VerifyWritten -> operation.expected
            else -> null
        }
        val operationName = when (operation) {
            is PendingNfcOperation.AwaitingReplacement, is PendingNfcOperation.Rewrite -> "rewrite"
            is PendingNfcOperation.InspectThenWrite, is PendingNfcOperation.Write, is PendingNfcOperation.AwaitingWriteConfirmation, is PendingNfcOperation.ConfirmedWrite, is PendingNfcOperation.VerifyWritten -> "write"
            is PendingNfcOperation.Verify, is PendingNfcOperation.VerifySnapshot -> "verify"
            is PendingNfcOperation.Erase, is PendingNfcOperation.VerifyErased -> "erase"
            is PendingNfcOperation.PermanentReadOnly -> "permanent_lock"
            is PendingNfcOperation.EnableProtection -> "protection_enabled"
            is PendingNfcOperation.RemoveProtection -> "protection_removed"
            is PendingNfcOperation.ManualAuthenticate -> "manual_authentication"
            is PendingNfcOperation.RecoverWrite -> operation.historyOperation
            is PendingNfcOperation.RecoverProtection -> operation.historyOperation
            is PendingNfcOperation.RecoverErase -> "erase"
            PendingNfcOperation.Read -> "read"
        }
        val credentialMode = when (operation) {
            is PendingNfcOperation.InspectThenWrite -> operation.protection.modeLabel()
            is PendingNfcOperation.Write -> operation.protection.modeLabel()
            is PendingNfcOperation.AwaitingWriteConfirmation -> operation.protection.modeLabel()
            is PendingNfcOperation.ConfirmedWrite -> operation.protection.modeLabel()
            is PendingNfcOperation.AwaitingReplacement -> operation.protection.modeLabel()
            is PendingNfcOperation.Rewrite -> operation.protection.modeLabel()
            is PendingNfcOperation.VerifyWritten -> operation.protection.modeLabel()
            is PendingNfcOperation.RecoverWrite -> operation.protection.modeLabel()
            else -> null
        }
        _state.value = NfcUiState(NfcStage.READY, if (mockModeEnabled) "Simulating mock NTAG215 detection" else "Hold a compatible NFC tag against the back of the phone", payload?.byteSize(), expectedPayload = payload, operation = operationName, credentialMode = credentialMode, isMock = mockModeEnabled, protectionRequested = credentialMode != null)
        if (mockModeEnabled) simulatePending(operation)
    }
    fun confirmReplacement() {
        val operation = pending as? PendingNfcOperation.AwaitingReplacement ?: return
        pending = PendingNfcOperation.Rewrite(operation.expected, operation.previous.uid, operation.previous, operation.protection)
        _state.value = _state.value.copy(stage = NfcStage.READY, message = if (operation.previous.uid == null) "Hold the NFC tag near your phone again to replace its content." else "Hold the same NFC tag near your phone again to replace its content.", previousSnapshot = operation.previous, expectedPayload = operation.expected, operation = "rewrite")
        if (mockModeEnabled) simulatePending(pending!!)
    }
    fun confirmInspectedWrite() {
        val operation = pending as? PendingNfcOperation.AwaitingWriteConfirmation ?: return
        pending = PendingNfcOperation.ConfirmedWrite(operation.expected, operation.expectedUid, operation.protection)
        _state.value = _state.value.copy(stage = NfcStage.READY, message = if (mockModeEnabled) "Continuing simulated Plain Text write" else "Capacity confirmed. Remove and tap the same NFC tag again to write.")
        if (mockModeEnabled) simulatePending(pending!!)
    }
    fun retry() {
        pending?.let { operation ->
            _state.value = _state.value.copy(stage = NfcStage.READY, message = if (mockModeEnabled) "Retrying simulated NFC operation" else "Keep the same NFC tag against the phone to continue safely.", interruptedStage = null)
            if (mockModeEnabled) simulatePending(operation)
        }
    }
    fun cancel() { pending?.clearSensitive(); pending = null; _state.value = NfcUiState() }
    fun enable() {
        if (mockModeEnabled) return
        adapter?.enableReaderMode(activity, this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,
            null)
    }
    fun disable() { runCatching { adapter?.disableReaderMode(activity) } }
    fun close() { disable(); scope.cancel() }

    override fun onTagDiscovered(tag: Tag) {
        if (mockModeEnabled) return
        val operation = pending ?: return
        if (_state.value.isCriticalOperation || _state.value.stage in setOf(NfcStage.DETECTED, NfcStage.CHECKING, NfcStage.AWAITING_CONFIRMATION)) return
        _state.value = _state.value.copy(stage = NfcStage.DETECTED, message = "Tag detected")
        activeOperationJob = scope.launch { execute(tag, operation) }
    }

    private fun simulatePending(operation: PendingNfcOperation) {
        activeOperationJob = scope.launch {
            delay(220)
            if (!mockModeEnabled || pending !== operation) return@launch
            _state.value = _state.value.copy(stage = NfcStage.DETECTED, message = "Mock tag detected", isMock = true)
            delay(180)
            try { executeMock(operation) }
            catch (_: CancellationException) { }
            catch (error: Exception) {
                if (mock.configuration.value.fault in setOf(MockNfcFault.TAG_REMOVED, MockNfcFault.COMMUNICATION_LOST)) {
                    connectionLost(operation, true)
                } else {
                    fail("MOCK ERROR\n\n${error.message ?: "Simulated NFC operation failed"}", operation)
                }
            }
        }
    }

    private suspend fun executeMock(operation: PendingNfcOperation) {
        when (operation) {
            PendingNfcOperation.Read -> {
                _state.value = _state.value.copy(stage = NfcStage.CHECKING, message = "Reading simulated NTAG215")
                pending = null
                succeed(mock.read(), "Mock tag read. No physical NFC operation was performed.", true)
            }
            is PendingNfcOperation.InspectThenWrite -> mockInspectThenWrite(operation)
            is PendingNfcOperation.Write -> mockWrite(operation.payload, null, "write", operation.protection)
            is PendingNfcOperation.AwaitingReplacement -> Unit
            is PendingNfcOperation.AwaitingWriteConfirmation -> Unit
            is PendingNfcOperation.ConfirmedWrite -> {
                require(isExpectedTag(operation.expectedUid, mock.read().uid)) { "A different simulated tag was selected." }
                mockWrite(operation.expected, null, "write", operation.protection)
            }
            is PendingNfcOperation.Rewrite -> {
                require(operation.expectedUid == mock.read().uid) { "A different simulated tag was selected." }
                mockWrite(operation.expected, operation.previous, "rewrite", operation.protection)
            }
            is PendingNfcOperation.Verify, is PendingNfcOperation.VerifySnapshot -> {
                val expected = if (operation is PendingNfcOperation.Verify) operation.expected.message().toByteArray() else (operation as PendingNfcOperation.VerifySnapshot).expectedMessage
                _state.value = _state.value.copy(stage = NfcStage.VERIFYING, message = "Verifying simulated NDEF content")
                delay(220)
                mock.verify(expected)
                val snapshot = mock.read()
                pending = null
                succeed(snapshot, "Mock content verified. No physical tag was tested.", true)
                onOperationRecorded?.invoke("verify", snapshot, true, true, null)
            }
            is PendingNfcOperation.Erase -> mockErase(operation.expectedUid)
            is PendingNfcOperation.VerifyErased -> mockErase(operation.expectedUid)
            is PendingNfcOperation.VerifyWritten -> mockVerifyWritten(operation)
            is PendingNfcOperation.PermanentReadOnly -> {
                require(operation.expectedUid == mock.read().uid) { "A different simulated tag was selected." }
                mock.permanentlyLock()
                val snapshot = mock.read()
                pending = null
                succeed(snapshot, "Mock tag marked permanently locked. No physical lock command was sent.", true)
                onOperationRecorded?.invoke("permanent_lock", snapshot, true, true, null)
            }
            is PendingNfcOperation.EnableProtection -> mockEnableProtection(operation.expectedUid)
            is PendingNfcOperation.RemoveProtection -> mockRemoveProtection(operation.expectedUid)
            is PendingNfcOperation.ManualAuthenticate -> mockManualAuthenticate(operation)
            is PendingNfcOperation.RecoverWrite -> mockRecoverWrite(operation)
            is PendingNfcOperation.RecoverProtection -> mockRecoverProtection(operation)
            is PendingNfcOperation.RecoverErase -> mockErase(operation.expectedUid)
        }
    }

    private suspend fun mockInspectThenWrite(operation: PendingNfcOperation.InspectThenWrite) {
        _state.value = _state.value.copy(stage = NfcStage.CHECKING, message = "Inspecting simulated NTAG215 content")
        val snapshot = mock.read()
        when (decideWritePreflight(snapshot.hasContent(), snapshot.writable, snapshot.capacity, operation.payload.byteSize())) {
            WritePreflightDecision.BLOCK_READ_ONLY -> error("The simulated tag is permanently read-only.")
            WritePreflightDecision.BLOCK_CAPACITY -> error("The payload exceeds simulated NTAG215 capacity.")
            WritePreflightDecision.REQUIRE_REPLACE_CONFIRMATION -> {
                pending = PendingNfcOperation.AwaitingReplacement(operation.payload, snapshot, operation.protection)
                _state.value = _state.value.copy(stage = NfcStage.AWAITING_CONFIRMATION, message = "Mock NFC tag already has content", snapshot = snapshot, previousSnapshot = snapshot, isMock = true)
            }
            WritePreflightDecision.WRITE_BLANK -> if (operation.payload is NfcPayload.Text) {
                pending = PendingNfcOperation.AwaitingWriteConfirmation(operation.payload, snapshot.uid, snapshot, operation.protection)
                _state.value = _state.value.copy(stage = NfcStage.AWAITING_CONFIRMATION, message = "Confirm Plain Text size and NFC capacity", snapshot = snapshot, expectedPayload = operation.payload, operation = "write")
            } else mockWrite(operation.payload, null, "write", operation.protection)
        }
    }

    private suspend fun mockWrite(payload: NfcPayload, previous: TagSnapshot?, historyOperation: String, protection: ProtectionRequest) {
        val expectedUid = mock.read().uid
        pending = PendingNfcOperation.RecoverWrite(payload, expectedUid, previous, historyOperation, protection)
        _state.value = _state.value.copy(stage = NfcStage.WRITING, message = "Writing simulated NDEF content", previousSnapshot = previous, requiredBytes = payload.byteSize(), protectionRequested = protection !is ProtectionRequest.None)
        if (mock.configuration.value.fault == MockNfcFault.BACKGROUND_DURING_WRITE) { simulateMockBackgroundInterruption(NfcStage.WRITING); return }
        delay(260)
        val before = mock.read()
        val storedCredential = if (before.securityState == "protected") before.uid?.let { credentialForUid?.invoke(it) } else null
        try { mock.writeAuthorized(payload, storedCredential) }
        finally { storedCredential?.let { it.password.fill(0); it.pack.fill(0) } }
        pending = PendingNfcOperation.VerifyWritten(payload, expectedUid, previous, historyOperation, protection)
        _state.value = _state.value.copy(stage = NfcStage.VERIFYING, message = "Verifying simulated read-back", contentWritten = true)
        if (mock.configuration.value.fault == MockNfcFault.BACKGROUND_DURING_VERIFY) { simulateMockBackgroundInterruption(NfcStage.VERIFYING); return }
        delay(260)
        mock.verify(payload.message().toByteArray())
        var snapshot = mock.read()
        require(ndefMessageMatchesPayload(snapshot.rawMessage, payload)) { "Mock verification failed: the NDEF record type or decoded content differs." }
        _state.value = _state.value.copy(contentVerified = true)
        val verifiedWrite = onVerifiedWrite?.invoke(snapshot, payload, historyOperation, previous)
        val tagId = verifiedWrite?.tagId
        _state.value = _state.value.copy(customerAccessCode=verifiedWrite?.customerAccessCode,permanentUrl=verifiedWrite?.permanentUrl,provisioningError=verifiedWrite?.provisioningError,nextMessageUrl=verifiedWrite?.nextMessageUrl,messageOwnerCode=verifiedWrite?.messageOwnerCode,nextCardError=verifiedWrite?.nextCardError)
        if (protection !is ProtectionRequest.None) {
            val credential = when (protection) {
                ProtectionRequest.AutoGenerated -> NtagCredentialGenerator.generate()
                is ProtectionRequest.Custom -> protection.credential
                ProtectionRequest.None -> error("Missing simulated credential")
            }
            val uid = requireNotNull(snapshot.uid)
            val id = requireNotNull(tagId) { "Mock record could not be saved before protection." }
            try {
                onCredentialStored?.invoke(uid, id, credential, Ntag21xVariant.NTAG215, if (protection is ProtectionRequest.Custom) "custom" else "auto_generated")
                pending = PendingNfcOperation.RecoverProtection(uid, payload, historyOperation)
                _state.value = _state.value.copy(stage = NfcStage.APPLYING_PROTECTION, message = "Applying simulated write protection", contentWritten = true, contentVerified = true, protectionRequested = true)
                if (mock.configuration.value.fault == MockNfcFault.BACKGROUND_DURING_PROTECTION) { simulateMockBackgroundInterruption(NfcStage.APPLYING_PROTECTION); return }
                delay(220)
                try { mock.enableWriteProtection(credential) }
                catch (error: Exception) { onSecurityResult?.invoke(uid, "protection_failed", "protection_enabled", error.message); throw error }
                _state.value = _state.value.copy(stage = NfcStage.VERIFYING_PROTECTION, message = "Verifying simulated protection")
                snapshot = mock.read()
                require(ndefMessageMatchesPayload(snapshot.rawMessage, payload)) { "Mock public NDEF read verification failed after protection." }
                require(snapshot.securityState == "protected") { "Mock protection could not be confirmed." }
                onSecurityResult?.invoke(uid, "protected", "protection_enabled", null)
            } finally { credential.password.fill(0); credential.pack.fill(0) }
        }
        pending = null
        val message = if (snapshot.securityState == "protected") "Mock content verified and mock protection enabled" else if (historyOperation == "rewrite") "Mock content replaced and verified" else "Mock NFC write verified"
        succeed(snapshot, "$message. No physical NFC operation was performed.", true, contentWritten = true, contentVerified = true, protectionVerified = snapshot.securityState == "protected")
    }

    private suspend fun mockVerifyWritten(operation: PendingNfcOperation.VerifyWritten) {
        require(isExpectedTag(operation.expectedUid, mock.read().uid)) { "A different simulated tag was selected." }
        _state.value = _state.value.copy(stage = NfcStage.VERIFYING, message = "Verifying simulated read-back", contentWritten = true)
        if (mock.configuration.value.fault == MockNfcFault.BACKGROUND_DURING_VERIFY) { simulateMockBackgroundInterruption(NfcStage.VERIFYING); return }
        delay(220)
        mock.verify(operation.expected.message().toByteArray())
        var snapshot = mock.read()
        require(ndefMessageMatchesPayload(snapshot.rawMessage, operation.expected)) { "Mock verification failed: the NDEF record type or decoded content differs." }
        val verifiedWrite = onVerifiedWrite?.invoke(snapshot, operation.expected, operation.historyOperation, operation.previous)
        val tagId = verifiedWrite?.tagId
        _state.value = _state.value.copy(customerAccessCode=verifiedWrite?.customerAccessCode,permanentUrl=verifiedWrite?.permanentUrl,provisioningError=verifiedWrite?.provisioningError,nextMessageUrl=verifiedWrite?.nextMessageUrl,messageOwnerCode=verifiedWrite?.messageOwnerCode,nextCardError=verifiedWrite?.nextCardError)
        _state.value = _state.value.copy(contentVerified = true)
        if (operation.protection !is ProtectionRequest.None && snapshot.securityState != "protected") {
            val credential = when (val request = operation.protection) {
                ProtectionRequest.AutoGenerated -> NtagCredentialGenerator.generate()
                is ProtectionRequest.Custom -> request.credential
                ProtectionRequest.None -> error("Missing simulated credential")
            }
            val uid = requireNotNull(snapshot.uid)
            try {
                onCredentialStored?.invoke(uid, requireNotNull(tagId), credential, Ntag21xVariant.NTAG215, if (operation.protection is ProtectionRequest.Custom) "custom" else "auto_generated")
                pending = PendingNfcOperation.RecoverProtection(uid, operation.expected, operation.historyOperation)
                _state.value = _state.value.copy(stage = NfcStage.APPLYING_PROTECTION, message = "Applying simulated write protection", protectionRequested = true)
                if (mock.configuration.value.fault == MockNfcFault.BACKGROUND_DURING_PROTECTION) { simulateMockBackgroundInterruption(NfcStage.APPLYING_PROTECTION); return }
                mock.enableWriteProtection(credential)
                _state.value = _state.value.copy(stage = NfcStage.VERIFYING_PROTECTION, message = "Verifying simulated protection")
                snapshot = mock.read()
                require(ndefMessageMatchesPayload(snapshot.rawMessage, operation.expected)) { "Mock public NDEF read verification failed after protection." }
                require(snapshot.securityState == "protected") { "Mock protection could not be confirmed." }
                onSecurityResult?.invoke(uid, "protected", "protection_enabled", null)
            } finally { credential.password.fill(0); credential.pack.fill(0) }
        }
        pending = null
        succeed(snapshot, "Mock content verified after interruption. No physical NFC operation was performed.", true, contentWritten = true, contentVerified = true, protectionVerified = snapshot.securityState == "protected")
    }

    private suspend fun mockErase(expectedUid: String?) {
        val before = mock.read()
        if (expectedUid != null) require(expectedUid == before.uid) { "A different simulated tag was selected." }
        pending = PendingNfcOperation.RecoverErase(before.uid)
        _state.value = _state.value.copy(stage = NfcStage.ERASING, message = "Erasing simulated NDEF content")
        val credential = if (before.securityState == "protected") before.uid?.let { credentialForUid?.invoke(it) } else null
        try { mock.eraseAuthorized(credential) }
        finally { credential?.let { it.password.fill(0); it.pack.fill(0) } }
        delay(220)
        _state.value = _state.value.copy(stage = NfcStage.VERIFYING, message = "Verifying simulated erase")
        val snapshot = mock.read()
        require(snapshot.records.isEmpty() || snapshot.records.all { it.content.isEmpty() }) { "Mock erase verification failed." }
        pending = null
        succeed(snapshot, "Mock tag erased and remains writable. No physical tag was changed.", true)
        onOperationRecorded?.invoke("erase", snapshot, true, true, null)
    }

    private suspend fun mockEnableProtection(expectedUid: String) {
        val before = mock.read()
        require(before.uid == expectedUid) { "A different simulated tag was selected." }
        require(before.securityState != "protected") { "The simulated tag is already protected." }
        val tagId = managedTagIdForUid?.invoke(expectedUid) ?: error("Save and verify the mock tag before enabling protection.")
        val credential = NtagCredentialGenerator.generate()
        try {
            onCredentialStored?.invoke(expectedUid, tagId, credential, Ntag21xVariant.NTAG215, "auto_generated")
            pending = PendingNfcOperation.RecoverProtection(expectedUid, null)
            _state.value = _state.value.copy(stage = NfcStage.APPLYING_PROTECTION, message = "Applying simulated write protection", protectionRequested = true)
            if (mock.configuration.value.fault == MockNfcFault.BACKGROUND_DURING_PROTECTION) { simulateMockBackgroundInterruption(NfcStage.APPLYING_PROTECTION); return }
            try { mock.enableWriteProtection(credential) }
            catch (error: Exception) { onSecurityResult?.invoke(expectedUid, "protection_failed", "protection_enabled", error.message); throw error }
            _state.value = _state.value.copy(stage = NfcStage.VERIFYING_PROTECTION, message = "Verifying simulated protection")
            val snapshot = mock.read()
            require(snapshot.securityState == "protected") { "Mock protection could not be confirmed." }
            onSecurityResult?.invoke(expectedUid, "protected", "protection_enabled", null)
            pending = null
            succeed(snapshot, "MOCK PROTECTION VERIFIED. Public simulated reading remains allowed.", true, protectionVerified = true)
        } finally { credential.password.fill(0); credential.pack.fill(0) }
    }

    private suspend fun mockRemoveProtection(expectedUid: String) {
        require(mock.read().uid == expectedUid) { "A different simulated tag was selected." }
        val credential = credentialForUid?.invoke(expectedUid) ?: error("The simulated managed credential is unavailable.")
        try { mock.removeWriteProtection(credential) }
        finally { credential.password.fill(0); credential.pack.fill(0) }
        val snapshot = mock.read()
        onSecurityResult?.invoke(expectedUid, "unprotected", "protection_removed", null)
        pending = null
        succeed(snapshot, "Mock protection removed. No physical tag was changed.", true)
    }

    private fun mockManualAuthenticate(operation: PendingNfcOperation.ManualAuthenticate) {
        require(mock.read().uid == operation.expectedUid) { "A different simulated tag was selected." }
        _state.value = _state.value.copy(stage = NfcStage.AUTHENTICATING, message = "Testing credential against simulated NTAG215")
        try {
            mock.authenticate(operation.credential)
            pending = null
            succeed(mock.read(), "Mock authentication successful. No physical chip was tested.", true)
        } catch (error: Exception) {
            throw IllegalStateException("AUTHENTICATION FAILED\n\nThe simulated credential was not accepted. No content was modified.", error)
        } finally { operation.credential.password.fill(0); operation.credential.pack.fill(0) }
    }

    private suspend fun mockRecoverWrite(operation: PendingNfcOperation.RecoverWrite) {
        val snapshot = mock.read()
        require(isExpectedTag(operation.expectedUid, snapshot.uid)) { "A different simulated tag was selected." }
        if (ndefMessageMatchesPayload(snapshot.rawMessage, operation.expected)) {
            val verify = PendingNfcOperation.VerifyWritten(operation.expected, operation.expectedUid, operation.previous, operation.historyOperation, operation.protection)
            pending = verify
            mockVerifyWritten(verify)
            return
        }
        val unchanged = operation.previous?.rawMessage?.contentEquals(snapshot.rawMessage) == true || (operation.previous == null && !snapshot.hasContent())
        val safelyEmpty = !snapshot.hasContent()
        pending = when {
            unchanged -> PendingNfcOperation.Write(operation.expected, operation.protection)
            safelyEmpty && operation.previous != null -> PendingNfcOperation.Rewrite(operation.expected, operation.expectedUid, operation.previous, operation.protection)
            else -> operation
        }
        _state.value = _state.value.copy(
            stage = NfcStage.RECOVERY_REQUIRED,
            snapshot = snapshot,
            message = if (unchanged) "The mock tag still has its previous content. A controlled retry is available." else if (safelyEmpty) "The mock tag is safely empty after interruption. A controlled retry is available." else "The mock tag state is uncertain. Rescan before retrying.",
            recoveryRetryAllowed = unchanged || safelyEmpty,
        )
    }

    private suspend fun mockRecoverProtection(operation: PendingNfcOperation.RecoverProtection) {
        val snapshot = mock.read()
        require(isExpectedTag(operation.expectedUid, snapshot.uid)) { "A different simulated tag was selected." }
        if (snapshot.securityState == "protected") {
            pending = null
            succeed(snapshot, "MOCK PROTECTION VERIFIED after interruption. No physical security operation occurred.", true, contentWritten = operation.expected != null, contentVerified = operation.expected != null, protectionVerified = true)
        } else {
            pending = PendingNfcOperation.EnableProtection(operation.expectedUid)
            _state.value = _state.value.copy(stage = NfcStage.SAFE_FAILURE, snapshot = snapshot, message = "Content is safe, but mock protection was not confirmed.", contentWritten = operation.expected != null, contentVerified = operation.expected != null, protectionRequested = true, protectionVerified = false, recoveryRetryAllowed = true)
        }
    }

    private suspend fun execute(tag: Tag, operation: PendingNfcOperation) {
        try {
            when (operation) {
                PendingNfcOperation.Read -> { _state.value = _state.value.copy(stage = NfcStage.READING, message = "Reading NFC tag"); succeed(read(tag), "Tag read", verified = true) }
                is PendingNfcOperation.InspectThenWrite -> inspectThenWrite(tag, operation.payload, operation.protection)
                is PendingNfcOperation.Write -> {
                    val current = read(tag)
                    write(tag, operation.payload, protection = operation.protection, protectedCredential = credentialForProtected(current))
                }
                is PendingNfcOperation.AwaitingWriteConfirmation -> Unit
                is PendingNfcOperation.ConfirmedWrite -> confirmedWrite(tag, operation)
                is PendingNfcOperation.Verify -> verify(tag, operation.expected)
                is PendingNfcOperation.VerifySnapshot -> verifySnapshot(tag, operation)
                is PendingNfcOperation.Erase -> erase(tag, operation.expectedUid)
                is PendingNfcOperation.AwaitingReplacement -> Unit
                is PendingNfcOperation.Rewrite -> rewrite(tag, operation)
                is PendingNfcOperation.VerifyWritten -> verifyWritten(tag, operation)
                is PendingNfcOperation.VerifyErased -> verifyErased(tag, operation)
                is PendingNfcOperation.PermanentReadOnly -> permanentlyLock(tag, operation.expectedUid)
                is PendingNfcOperation.EnableProtection -> enableProtection(tag, operation.expectedUid)
                is PendingNfcOperation.RemoveProtection -> removeProtection(tag, operation.expectedUid)
                is PendingNfcOperation.ManualAuthenticate -> manualAuthenticate(tag, operation)
                is PendingNfcOperation.RecoverWrite -> recoverWrite(tag, operation)
                is PendingNfcOperation.RecoverProtection -> recoverProtection(tag, operation)
                is PendingNfcOperation.RecoverErase -> recoverErase(tag, operation)
            }
        } catch (_: CancellationException) {
            // Lifecycle interruption already published a recovery state. Never overwrite it.
        } catch (e: DifferentTagException) {
            _state.value = _state.value.copy(stage = NfcStage.ERROR, message = "DIFFERENT NFC TAG\n\nThis is not the NFC tag you originally scanned.\n\nExpected: ${e.expected}\nDetected: ${e.actual}")
        } catch (e: TagLostException) { connectionLost(operation, false) }
        catch (e: SecurityException) { fail("Android denied the NFC operation: ${e.message.orEmpty()}", operation) }
        catch (e: Exception) { fail(e.message ?: "NFC communication failed (${e.javaClass.simpleName}). Keep the tag steady and try again.", operation) }
    }

    private suspend fun inspectThenWrite(tag: Tag, payload: NfcPayload, protection: ProtectionRequest) {
        _state.value = _state.value.copy(stage = NfcStage.CHECKING, message = "Inspecting existing NFC content")
        val snapshot = read(tag)
        if (protection !is ProtectionRequest.None && snapshot.securityState != "protected" && snapshot.securityVariant == null) {
            error(snapshot.securityReason ?: "Managed write protection is unavailable for this physical NFC chip.")
        }
        val enableAfterWrite = if (snapshot.securityState == "protected") ProtectionRequest.None else protection
        when (decideWritePreflight(snapshot.hasContent(), snapshot.writable, snapshot.capacity, payload.byteSize())) {
            WritePreflightDecision.BLOCK_READ_ONLY -> error("This NFC tag is read-only. Its content can be read, but it cannot be replaced or erased.")
            WritePreflightDecision.BLOCK_CAPACITY -> error("Data is too large: ${payload.byteSize()} bytes required, ${snapshot.capacity} available.")
            WritePreflightDecision.REQUIRE_REPLACE_CONFIRMATION -> {
                pending = PendingNfcOperation.AwaitingReplacement(payload, snapshot, enableAfterWrite)
                _state.value = _state.value.copy(stage = NfcStage.AWAITING_CONFIRMATION, message = "NFC tag already has content", snapshot = snapshot, previousSnapshot = snapshot, expectedPayload = payload, operation = "rewrite")
            }
            WritePreflightDecision.WRITE_BLANK -> if (payload is NfcPayload.Text) {
                pending = PendingNfcOperation.AwaitingWriteConfirmation(payload, snapshot.uid, snapshot, enableAfterWrite)
                _state.value = _state.value.copy(stage = NfcStage.AWAITING_CONFIRMATION, message = "Confirm Plain Text size and NFC capacity", snapshot = snapshot, expectedPayload = payload, operation = "write")
            } else {
                write(tag, payload, previous = null, historyOperation = "write", protection = enableAfterWrite, protectedCredential = credentialForProtected(snapshot))
            }
        }
    }

    private suspend fun confirmedWrite(tag: Tag, operation: PendingNfcOperation.ConfirmedWrite) {
        val actualUid = tag.uid()
        if (!isExpectedTag(operation.expectedUid, actualUid)) throw DifferentTagException(operation.expectedUid ?: "Unknown", actualUid ?: "Not exposed")
        val snapshot = read(tag)
        when (decideWritePreflight(snapshot.hasContent(), snapshot.writable, snapshot.capacity, operation.expected.byteSize())) {
            WritePreflightDecision.BLOCK_READ_ONLY -> error("This NFC tag is read-only. Its content can be read, but it cannot be replaced or erased.")
            WritePreflightDecision.BLOCK_CAPACITY -> error("Data is too large: ${operation.expected.byteSize()} bytes required, ${snapshot.capacity} available.")
            WritePreflightDecision.REQUIRE_REPLACE_CONFIRMATION -> error("The NFC content changed after capacity confirmation. Scan it again before replacing content.")
            WritePreflightDecision.WRITE_BLANK -> write(tag, operation.expected, protection = operation.protection, protectedCredential = credentialForProtected(snapshot))
        }
    }

    private suspend fun rewrite(tag: Tag, operation: PendingNfcOperation.Rewrite) {
        val actualUid = tag.uid()
        if (!isExpectedTag(operation.expectedUid, actualUid)) throw DifferentTagException(operation.expectedUid!!, actualUid ?: "Not exposed")
        _state.value = _state.value.copy(stage = NfcStage.CHECKING, message = "Checking tag before rewrite", previousSnapshot = operation.previous)
        val current = read(tag)
        when (decideWritePreflight(current.hasContent(), current.writable, current.capacity, operation.expected.byteSize())) {
            WritePreflightDecision.BLOCK_READ_ONLY -> error("This NFC tag is permanently read-only or protected. It cannot be replaced or erased.")
            WritePreflightDecision.BLOCK_CAPACITY -> error("Data is too large: ${operation.expected.byteSize()} bytes required, ${current.capacity} available.")
            else -> write(tag, operation.expected, current, "rewrite", operation.protection, credentialForProtected(current))
        }
    }

    private fun read(tag: Tag): TagSnapshot {
        val uid = tag.id?.joinToString(":") { "%02X".format(it) }
        val techs = tag.techList.map { it.substringAfterLast('.') }
        val ndef = Ndef.get(tag)
        val protection = security.inspect(tag)
        if (ndef == null) return TagSnapshot(uid, techs, inferType(tag, null), null, null, false, emptyList(), null, protection.state.name.lowercase(), protection.variant?.name, protection.reason)
        ndef.connect()
        return try {
            val message = ndef.ndefMessage
            TagSnapshot(uid, techs, inferType(tag, ndef), ndef.maxSize, ndef.isWritable, ndef.canMakeReadOnly(), decode(message), message?.toByteArray(), protection.state.name.lowercase(), protection.variant?.name, protection.reason)
        } finally { ndef.close() }
    }

    private suspend fun write(tag: Tag, payload: NfcPayload, previous: TagSnapshot? = null, historyOperation: String = "write", protection: ProtectionRequest = ProtectionRequest.None, protectedCredential: NtagCredential? = null) {
        val message = payload.message(); val required = message.toByteArray().size
        val uid = tag.uid()
        val managedUrl = (payload as? NfcPayload.UriPayload)?.uri?.takeIf { Regex("/t/[A-HJ-NP-Z2-9]{7,16}(?:[/?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        if (managedUrl != null) {
            _state.value = _state.value.copy(stage = NfcStage.CHECKING, message = "Checking this NFC card's unique URL")
            checkManagedUrlWrite?.invoke(managedUrl, uid ?: error("This phone did not expose the NFC card UID."), managedUrlReferenceForRewrite(historyOperation, previous, managedUrl))
                ?: error("Managed NFC assignment check is unavailable")
        }
        pending = PendingNfcOperation.RecoverWrite(payload, uid, previous, historyOperation, protection)
        _state.value = _state.value.copy(stage = NfcStage.WRITING, message = "Writing to NFC", requiredBytes = required, protectionRequested = protection !is ProtectionRequest.None)
        writeMessage(tag, message, protectedCredential)
        currentCoroutineContext().ensureActive()
        pending = PendingNfcOperation.VerifyWritten(payload, uid, previous, historyOperation, protection)
        _state.value = _state.value.copy(stage = NfcStage.VERIFYING, message = "Reading the tag back to confirm your data", previousSnapshot = previous, expectedPayload = payload, operation = historyOperation, contentWritten = true)
        verifyWritten(tag, pending as PendingNfcOperation.VerifyWritten)
    }

    private suspend fun verifyWritten(tag: Tag, operation: PendingNfcOperation.VerifyWritten) {
        val uid = tag.id?.joinToString(":") { "%02X".format(it) }
        if (!isExpectedTag(operation.expectedUid, uid)) throw DifferentTagException(operation.expectedUid!!, uid ?: "Not exposed")
        val snapshot = read(tag)
        require(ndefMessageMatchesPayload(snapshot.rawMessage, operation.expected)) { "Verification failed: the stored NDEF record type or decoded content differs from the requested payload." }
        _state.value = _state.value.copy(contentWritten = true, contentVerified = true)
        val verifiedWrite = onVerifiedWrite?.invoke(snapshot, operation.expected, operation.historyOperation, operation.previous)
        val tagId = verifiedWrite?.tagId
        _state.value = _state.value.copy(customerAccessCode=verifiedWrite?.customerAccessCode,permanentUrl=verifiedWrite?.permanentUrl,provisioningError=verifiedWrite?.provisioningError,nextMessageUrl=verifiedWrite?.nextMessageUrl,messageOwnerCode=verifiedWrite?.messageOwnerCode,nextCardError=verifiedWrite?.nextCardError)
        if (operation.protection !is ProtectionRequest.None) {
            val uidValue = requireNotNull(snapshot.uid) { "Protection requires an exposed tag identifier for managed credential lookup." }
            val variant = Ntag21xVariant.valueOf(requireNotNull(snapshot.securityVariant) { "Password protection is unavailable for this chip." })
            val id = requireNotNull(tagId) { "The verified tag could not be saved before protection." }
            val credential = when (val request = operation.protection) {
                ProtectionRequest.AutoGenerated -> NtagCredentialGenerator.generate()
                is ProtectionRequest.Custom -> request.credential
                ProtectionRequest.None -> error("Missing protection credential mode.")
            }
            val credentialMode = if (operation.protection is ProtectionRequest.Custom) "custom" else "auto_generated"
            try {
                onCredentialStored?.invoke(uidValue, id, credential, variant, credentialMode)
                pending = PendingNfcOperation.RecoverProtection(uidValue, operation.expected, operation.historyOperation)
                _state.value = _state.value.copy(stage = NfcStage.APPLYING_PROTECTION, message = "Applying write protection", contentWritten = true, contentVerified = true, protectionRequested = true)
                security.enableWriteProtection(tag, credential)
                currentCoroutineContext().ensureActive()
                _state.value = _state.value.copy(stage = NfcStage.VERIFYING_PROTECTION, message = "Verifying write protection")
                val publicSnapshot = read(tag)
                require(ndefMessageMatchesPayload(publicSnapshot.rawMessage, operation.expected)) { "Public NDEF read verification failed after protection." }
                require(publicSnapshot.securityState == "protected") { "The physical tag did not confirm active write protection." }
                onSecurityResult?.invoke(uidValue, "protected", "protection_enabled", null)
                pending = null
                val successMessage = if (operation.expected is NfcPayload.Text) "Write Successful — RTD_TEXT, exact decoded text, and managed protection verified" else "Write Successful — content, URI type, and managed protection verified"
                succeed(publicSnapshot, successMessage, true, contentWritten = true, contentVerified = true, protectionVerified = true)
            } catch (error: Exception) {
                onSecurityResult?.invoke(uidValue, "protection_failed", "protection_enabled", error.message)
                if (error is CancellationException) throw error
                pending = PendingNfcOperation.RecoverProtection(uidValue, operation.expected, operation.historyOperation)
                _state.value = _state.value.copy(stage = NfcStage.SAFE_FAILURE, snapshot = snapshot, message = "Content was written and verified, but write protection could not be confirmed.", contentWritten = true, contentVerified = true, protectionRequested = true, protectionVerified = false, recoveryRetryAllowed = true)
            } finally {
                credential.password.fill(0); credential.pack.fill(0)
            }
        } else {
            pending = null
            val successMessage = when (operation.expected) {
                is NfcPayload.Text -> "Write Successful — RTD_TEXT and exact decoded text verified"
                else -> if (operation.historyOperation == "rewrite") "Write Successful — content replaced and URI verified" else "Write Successful — NDEF record and decoded content verified"
            }
            succeed(snapshot, successMessage, true, contentWritten = true, contentVerified = true)
        }
    }

    private suspend fun verifySnapshot(tag: Tag, operation: PendingNfcOperation.VerifySnapshot) {
        val uid = tag.uid()
        if (!isExpectedTag(operation.expectedUid, uid)) throw DifferentTagException(operation.expectedUid!!, uid ?: "Not exposed")
        _state.value = _state.value.copy(stage = NfcStage.VERIFYING, message = "Reading the physical tag again")
        val snapshot = read(tag)
        val ok = snapshot.rawMessage?.contentEquals(operation.expectedMessage) == true
        if (!ok) { onOperationRecorded?.invoke("verify", snapshot, true, false, "Content mismatch"); error("Verification failed: the tag content changed.") }
        pending = null
        succeed(snapshot, "Physical content verified", true)
        onOperationRecorded?.invoke("verify", snapshot, true, true, null)
    }

    private suspend fun verify(tag: Tag, expected: NfcPayload) {
        _state.value = _state.value.copy(stage = NfcStage.VERIFYING, message = "Reading physical tag")
        val snapshot = read(tag); val ok = ndefMessageMatchesPayload(snapshot.rawMessage, expected)
        if (!ok) { onOperationRecorded?.invoke("verify", snapshot, true, false, "Content mismatch"); error("Verification failed: actual content differs from expected content.") }
        pending = null; succeed(snapshot, "Content verified", true); onOperationRecorded?.invoke("verify", snapshot, true, true, null)
    }

    private suspend fun erase(tag: Tag, expectedUid: String?) {
        val actualUid = tag.uid()
        if (!isExpectedTag(expectedUid, actualUid)) throw DifferentTagException(expectedUid!!, actualUid ?: "Not exposed")
        val before = read(tag)
        val credential = credentialForProtected(before)
        pending = PendingNfcOperation.RecoverErase(actualUid)
        _state.value = _state.value.copy(stage = NfcStage.ERASING, message = "Erasing writable NDEF content")
        val empty = NdefMessage(arrayOf(NdefRecord(NdefRecord.TNF_EMPTY, byteArrayOf(), byteArrayOf(), byteArrayOf())))
        writeMessage(tag, empty, credential)
        val uid = tag.uid()
        pending = PendingNfcOperation.VerifyErased(uid)
        _state.value = _state.value.copy(stage = NfcStage.VERIFYING, message = "Reading the tag back to verify the erase", contentWritten = true)
        verifyErased(tag, pending as PendingNfcOperation.VerifyErased)
    }

    private fun verifyErased(tag: Tag, operation: PendingNfcOperation.VerifyErased) {
        val uid = tag.id?.joinToString(":") { "%02X".format(it) }
        if (operation.expectedUid != null) require(uid == operation.expectedUid) { "A different tag was presented for erase verification." }
        val snapshot = read(tag)
        val records = snapshot.records
        require(records.isEmpty() || (records.size == 1 && records[0].content.isEmpty())) { "Erase verification failed: content remains on tag." }
        require(snapshot.writable != false) { "Tag was erased but unexpectedly reports read-only." }
        pending = null; succeed(snapshot, "Tag erased and remains rewritable", true); onOperationRecorded?.invoke("erase", snapshot, true, true, null)
    }

    private fun permanentlyLock(tag: Tag, expectedUid: String) {
        val actualUid = tag.id?.joinToString(":") { "%02X".format(it) }.orEmpty()
        require(actualUid == expectedUid) { "This is not the same physical tag confirmed in the previous step." }
        val ndef = Ndef.get(tag) ?: error("This tag is not NDEF compatible.")
        ndef.connect()
        try {
            require(ndef.isWritable) { "Tag is already read-only." }
            require(ndef.canMakeReadOnly()) { "Android reports that this tag cannot safely be made read-only." }
            require(ndef.makeReadOnly()) { "The tag rejected the permanent read-only operation." }
        } finally { ndef.close() }
        val snapshot = read(tag)
        require(snapshot.writable == false) { "Android could not verify the resulting read-only state." }
        pending = null; succeed(snapshot, "Permanently locked and verified read-only", true)
        onOperationRecorded?.invoke("permanent_lock", snapshot, true, true, null)
    }

    private suspend fun credentialForProtected(snapshot: TagSnapshot): NtagCredential? {
        if (snapshot.securityState != "protected") return null
        val uid = requireNotNull(snapshot.uid) { "Protected NFC credential lookup requires a tag UID." }
        if (!automaticCredentialRetrievalEnabled) error("AUTHENTICATION REQUIRED\n\nAutomatic credential retrieval is disabled for developer testing. Use Manual Authentication.")
        val credential = credentialForUid?.invoke(uid) ?: error("PROTECTED NFC TAG\n\nTapTag does not have the credential required to modify this tag.")
        _state.value = _state.value.copy(stage = NfcStage.AUTHENTICATING, message = "Authenticating managed NFC tag")
        return credential
    }

    private fun writeMessage(tag: Tag, message: NdefMessage, protectedCredential: NtagCredential?) {
        if (protectedCredential != null) {
            try {
                security.writeAuthenticatedNdef(tag, protectedCredential, message.toByteArray())
            } finally {
                protectedCredential.password.fill(0)
                protectedCredential.pack.fill(0)
            }
            return
        }
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            ndef.connect()
            try {
                require(ndef.isWritable) { "This tag is read-only and cannot be rewritten." }
                require(message.toByteArray().size <= ndef.maxSize) { "Data is too large: ${message.toByteArray().size} bytes required, ${ndef.maxSize} available." }
                ndef.writeNdefMessage(message)
            } finally { ndef.close() }
        } else {
            val formattable = NdefFormatable.get(tag) ?: error("This tag does not support NDEF formatting.")
            formattable.connect(); try { formattable.format(message) } finally { formattable.close() }
        }
    }

    private suspend fun enableProtection(tag: Tag, expectedUid: String) {
        val actual = tag.uid()
        if (!isExpectedTag(expectedUid, actual)) throw DifferentTagException(expectedUid, actual ?: "Not exposed")
        val inspection = security.inspect(tag)
        val variant = inspection.variant ?: error(inspection.reason ?: "Password protection is unavailable for this chip.")
        require(inspection.state == PhysicalSecurityState.UNPROTECTED) { "This tag is already protected or its security state is unknown." }
        val tagId = managedTagIdForUid?.invoke(expectedUid) ?: error("Save and verify this tag before enabling managed protection.")
        val credential = NtagCredentialGenerator.generate()
        try {
            onCredentialStored?.invoke(expectedUid, tagId, credential, variant, "auto_generated")
            pending = PendingNfcOperation.RecoverProtection(expectedUid, null)
            _state.value = _state.value.copy(stage = NfcStage.APPLYING_PROTECTION, message = "Applying write protection", protectionRequested = true)
            security.enableWriteProtection(tag, credential)
            currentCoroutineContext().ensureActive()
            _state.value = _state.value.copy(stage = NfcStage.VERIFYING_PROTECTION, message = "Verifying write protection")
            val publicSnapshot = read(tag)
            require(publicSnapshot.rawMessage != null) { "Public NDEF reading could not be verified after protection." }
            require(publicSnapshot.securityState == "protected") { "The physical tag did not confirm active write protection." }
            onSecurityResult?.invoke(expectedUid, "protected", "protection_enabled", null)
            pending = null; succeed(publicSnapshot, "Managed write protection enabled and public reading verified", true, protectionVerified = true)
        } catch (error: Exception) {
            onSecurityResult?.invoke(expectedUid, "protection_failed", "protection_enabled", error.message)
            throw error
        } finally { credential.password.fill(0); credential.pack.fill(0) }
    }

    private suspend fun removeProtection(tag: Tag, expectedUid: String) {
        val actual = tag.uid()
        if (!isExpectedTag(expectedUid, actual)) throw DifferentTagException(expectedUid, actual ?: "Not exposed")
        val credential = credentialForUid?.invoke(expectedUid) ?: error("The authorized managed credential is unavailable on this device.")
        try {
            _state.value = _state.value.copy(stage = NfcStage.AUTHENTICATING, message = "Authenticating and removing write protection")
            security.removeWriteProtection(tag, credential)
            currentCoroutineContext().ensureActive()
            val snapshot = read(tag)
            onSecurityResult?.invoke(expectedUid, "unprotected", "protection_removed", null)
            pending = null; succeed(snapshot, "Managed write protection removed and writable state verified", true)
        } finally { credential.password.fill(0); credential.pack.fill(0) }
    }

    private suspend fun recoverWrite(tag: Tag, operation: PendingNfcOperation.RecoverWrite) {
        val actualUid = tag.uid()
        if (!isExpectedTag(operation.expectedUid, actualUid)) throw DifferentTagException(operation.expectedUid ?: "Unknown", actualUid ?: "Not exposed")
        _state.value = _state.value.copy(stage = NfcStage.VERIFYING, message = "Inspecting the interrupted write without rewriting")
        val snapshot = read(tag)
        if (ndefMessageMatchesPayload(snapshot.rawMessage, operation.expected)) {
            val verify = PendingNfcOperation.VerifyWritten(operation.expected, operation.expectedUid, operation.previous, operation.historyOperation, operation.protection)
            pending = verify
            verifyWritten(tag, verify)
            return
        }
        val unchanged = operation.previous?.rawMessage?.contentEquals(snapshot.rawMessage) == true || (operation.previous == null && !snapshot.hasContent())
        val safelyEmpty = !snapshot.hasContent()
        pending = when {
            unchanged && operation.previous == null -> PendingNfcOperation.Write(operation.expected, operation.protection)
            unchanged || (safelyEmpty && operation.previous != null) -> PendingNfcOperation.Rewrite(operation.expected, operation.expectedUid, operation.previous!!, operation.protection)
            else -> operation
        }
        _state.value = _state.value.copy(
            stage = NfcStage.RECOVERY_REQUIRED,
            snapshot = snapshot,
            message = if (unchanged) "The previous content is still present. Tap controlled retry to write again." else if (safelyEmpty) "The tag is safely empty after the interrupted authenticated rewrite. Tap controlled retry to finish." else "The tag state is uncertain. Tap rescan; TapTag will not rewrite blindly.",
            recoveryRetryAllowed = unchanged || safelyEmpty,
        )
    }

    private suspend fun recoverProtection(tag: Tag, operation: PendingNfcOperation.RecoverProtection) {
        val actualUid = tag.uid()
        if (!isExpectedTag(operation.expectedUid, actualUid)) throw DifferentTagException(operation.expectedUid, actualUid ?: "Not exposed")
        _state.value = _state.value.copy(stage = NfcStage.VERIFYING_PROTECTION, message = "Inspecting protection after interruption", protectionRequested = true)
        val snapshot = read(tag)
        if (snapshot.securityState == "protected") {
            pending = null
            onSecurityResult?.invoke(operation.expectedUid, "protected", "protection_enabled", null)
            succeed(snapshot, "Write protection confirmed after recovery", true, contentWritten = operation.expected != null, contentVerified = operation.expected != null, protectionVerified = true)
        } else {
            pending = PendingNfcOperation.EnableProtection(operation.expectedUid)
            onSecurityResult?.invoke(operation.expectedUid, "unknown", "protection_enabled", "Interrupted before protection could be confirmed")
            _state.value = _state.value.copy(stage = NfcStage.SAFE_FAILURE, snapshot = snapshot, message = "Content is safe, but write protection is UNKNOWN / UNCONFIRMED.", contentWritten = operation.expected != null, contentVerified = operation.expected != null, protectionRequested = true, protectionVerified = false, recoveryRetryAllowed = snapshot.securityState == "unprotected")
        }
    }

    private fun recoverErase(tag: Tag, operation: PendingNfcOperation.RecoverErase) {
        val actualUid = tag.uid()
        if (!isExpectedTag(operation.expectedUid, actualUid)) throw DifferentTagException(operation.expectedUid ?: "Unknown", actualUid ?: "Not exposed")
        _state.value = _state.value.copy(stage = NfcStage.VERIFYING, message = "Inspecting the interrupted erase without erasing again")
        val snapshot = read(tag)
        val empty = snapshot.records.isEmpty() || (snapshot.records.size == 1 && snapshot.records[0].content.isEmpty())
        if (empty && snapshot.writable != false) {
            pending = null
            succeed(snapshot, "Erase verified after recovery", true, contentWritten = true, contentVerified = true)
        } else {
            pending = PendingNfcOperation.Erase(operation.expectedUid)
            _state.value = _state.value.copy(stage = NfcStage.RECOVERY_REQUIRED, snapshot = snapshot, message = "The erase was not confirmed. A controlled retry is available.", recoveryRetryAllowed = true)
        }
    }

    private suspend fun manualAuthenticate(tag: Tag, operation: PendingNfcOperation.ManualAuthenticate) {
        val actual = tag.uid()
        if (!isExpectedTag(operation.expectedUid, actual)) throw DifferentTagException(operation.expectedUid, actual ?: "Not exposed")
        _state.value = _state.value.copy(stage = NfcStage.AUTHENTICATING, message = "Testing the supplied credential against the physical NFC chip")
        try {
            val result = security.authenticate(tag, operation.credential)
            currentCoroutineContext().ensureActive()
            require(result.state == PhysicalSecurityState.PROTECTED) { "The tag does not report active write protection." }
            val snapshot = read(tag)
            currentCoroutineContext().ensureActive()
            pending = null
            succeed(snapshot, "Authentication successful. This credential was accepted; no content was modified.", true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw IllegalStateException("AUTHENTICATION FAILED\n\nThe NFC credential was not accepted. No NFC content was modified.", error)
        } finally {
            operation.credential.password.fill(0); operation.credential.pack.fill(0)
        }
    }

    private fun succeed(
        snapshot: TagSnapshot,
        message: String,
        verified: Boolean,
        contentWritten: Boolean = _state.value.contentWritten,
        contentVerified: Boolean = _state.value.contentVerified,
        protectionVerified: Boolean = _state.value.protectionVerified,
    ) {
        _state.value = _state.value.copy(
            stage = NfcStage.SUCCESS,
            message = message,
            snapshot = snapshot,
            verified = verified,
            contentWritten = contentWritten,
            contentVerified = contentVerified,
            protectionVerified = protectionVerified,
            interruptedStage = null,
        )
    }

    private fun connectionLost(operation: PendingNfcOperation, isMock: Boolean) {
        _state.value = _state.value.copy(
            stage = NfcStage.CONNECTION_LOST,
            message = "TAG CONNECTION LOST\n\nDo not move the NFC tag while writing.",
            isMock = isMock || _state.value.isMock,
        )
        val historyOperation = operation.historyOperationOrNull()
        historyOperation?.let { onOperationRecorded?.invoke(it, null, _state.value.contentWritten, false, "Tag connection lost") }
    }

    private fun fail(message: String, operation: PendingNfcOperation) {
        _state.value = _state.value.copy(stage = NfcStage.ERROR, message = message)
        val historyOperation = operation.historyOperationOrNull()
        historyOperation?.let { onOperationRecorded?.invoke(it, null, false, false, message) }
        operation.clearSensitive()
        if (operation is PendingNfcOperation.ManualAuthenticate) pending = null
    }

    private fun Tag.uid(): String? = id?.joinToString(":") { "%02X".format(it) }
    private fun TagSnapshot.hasContent(): Boolean = records.any { it.type != "TNF 0" || it.content.isNotEmpty() }
    private class DifferentTagException(val expected: String, val actual: String) : IllegalStateException()

    private fun inferType(tag: Tag, ndef: Ndef?): String = when {
        ndef?.type == Ndef.NFC_FORUM_TYPE_2 -> "NFC Forum Type 2"
        ndef?.type == Ndef.NFC_FORUM_TYPE_4 -> "NFC Forum Type 4"
        ndef?.type == Ndef.MIFARE_CLASSIC -> "MIFARE Classic"
        tag.techList.any { it.endsWith("NfcA") } -> "ISO 14443 Type A"
        else -> ndef?.type ?: "Unknown"
    }

    private fun decode(message: NdefMessage?): List<DecodedRecord> = message?.records?.map { record ->
        when {
            record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_URI) -> DecodedRecord("URI", record.toUri()?.toString().orEmpty())
            record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_TEXT) -> {
                DecodedRecord("Text", decodeNdefText(record)?.text.orEmpty())
            }
            record.tnf == NdefRecord.TNF_MIME_MEDIA -> DecodedRecord(record.type.toString(Charsets.US_ASCII), record.payload.toString(Charsets.UTF_8))
            else -> DecodedRecord("TNF ${record.tnf}", record.payload.joinToString("") { "%02X".format(it) })
        }
    } ?: emptyList()
}

private fun PendingNfcOperation.clearSensitive() {
    val request = when (this) {
        is PendingNfcOperation.InspectThenWrite -> protection
        is PendingNfcOperation.Write -> protection
        is PendingNfcOperation.AwaitingWriteConfirmation -> protection
        is PendingNfcOperation.ConfirmedWrite -> protection
        is PendingNfcOperation.AwaitingReplacement -> protection
        is PendingNfcOperation.Rewrite -> protection
        is PendingNfcOperation.VerifyWritten -> protection
        is PendingNfcOperation.RecoverWrite -> protection
        else -> null
    }
    (request as? ProtectionRequest.Custom)?.credential?.let { it.password.fill(0); it.pack.fill(0) }
    (this as? PendingNfcOperation.ManualAuthenticate)?.credential?.let { it.password.fill(0); it.pack.fill(0) }
}

private fun PendingNfcOperation.normalizePayloads(): PendingNfcOperation = when (this) {
    is PendingNfcOperation.InspectThenWrite -> copy(payload = payload.normalizedForWrite())
    is PendingNfcOperation.Write -> copy(payload = payload.normalizedForWrite())
    is PendingNfcOperation.AwaitingWriteConfirmation -> copy(expected = expected.normalizedForWrite() as NfcPayload.Text)
    is PendingNfcOperation.ConfirmedWrite -> copy(expected = expected.normalizedForWrite() as NfcPayload.Text)
    is PendingNfcOperation.Verify -> copy(expected = expected.normalizedForWrite())
    is PendingNfcOperation.AwaitingReplacement -> copy(expected = expected.normalizedForWrite())
    is PendingNfcOperation.Rewrite -> copy(expected = expected.normalizedForWrite())
    is PendingNfcOperation.VerifyWritten -> copy(expected = expected.normalizedForWrite())
    is PendingNfcOperation.RecoverWrite -> copy(expected = expected.normalizedForWrite())
    is PendingNfcOperation.RecoverProtection -> copy(expected = expected?.normalizedForWrite())
    else -> this
}

private fun PendingNfcOperation.historyOperationOrNull(): String? = when (this) {
    PendingNfcOperation.Read, is PendingNfcOperation.ManualAuthenticate, is PendingNfcOperation.AwaitingWriteConfirmation -> null
    is PendingNfcOperation.InspectThenWrite, is PendingNfcOperation.Write, is PendingNfcOperation.ConfirmedWrite -> "write"
    is PendingNfcOperation.AwaitingReplacement, is PendingNfcOperation.Rewrite -> "rewrite"
    is PendingNfcOperation.VerifyWritten -> historyOperation
    is PendingNfcOperation.RecoverWrite -> historyOperation
    is PendingNfcOperation.Verify, is PendingNfcOperation.VerifySnapshot -> "verify"
    is PendingNfcOperation.Erase, is PendingNfcOperation.VerifyErased, is PendingNfcOperation.RecoverErase -> "erase"
    is PendingNfcOperation.PermanentReadOnly -> "permanent_lock"
    is PendingNfcOperation.EnableProtection, is PendingNfcOperation.RecoverProtection -> "protection_enabled"
    is PendingNfcOperation.RemoveProtection -> "protection_removed"
}

private fun ProtectionRequest.modeLabel(): String? = when (this) {
    ProtectionRequest.None -> null
    ProtectionRequest.AutoGenerated -> "Auto-generated"
    is ProtectionRequest.Custom -> "Custom"
}
