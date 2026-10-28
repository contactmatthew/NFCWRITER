package com.nfcwriter.app.nfc

import android.content.Context
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.util.Base64
import com.nfcwriter.app.domain.NfcPayload
import com.nfcwriter.app.domain.decodeNdefText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

enum class MockTagState(val label: String) {
    EMPTY("Empty"),
    CONTAINS_NDEF("Contains NDEF"),
    WRITE_PROTECTED("Write protected"),
    PERMANENTLY_LOCKED("Permanently locked"),
    UNSUPPORTED("Unsupported"),
    COMMUNICATION_ERROR("Communication error"),
}

enum class MockNfcFault(val label: String) {
    NONE("None"),
    TAG_REMOVED("Tag removed during write"),
    COMMUNICATION_LOST("Communication lost"),
    UNSUPPORTED_TAG("Unsupported tag"),
    NDEF_UNSUPPORTED("NDEF unsupported"),
    INSUFFICIENT_CAPACITY("Insufficient capacity"),
    WRITE_FAILURE("Write failure"),
    VERIFICATION_FAILURE("Verification failure"),
    WRONG_PASSWORD("Wrong password"),
    AUTHENTICATION_FAILURE("Authentication failure"),
    PROTECTION_FAILURE("Protection configuration failure"),
    BACKGROUND_DURING_WRITE("App backgrounded during write"),
    BACKGROUND_DURING_VERIFY("App backgrounded during verification"),
    BACKGROUND_DURING_PROTECTION("App backgrounded during protection"),
}

enum class MockCloudFault(val label: String) {
    NONE("None"),
    SUPABASE_UNAVAILABLE("Supabase unavailable"),
    OFFLINE("Offline mode"),
    SYNC_FAILURE("Cloud sync failure"),
}

@Serializable
data class MockNfcConfiguration(
    val enabled: Boolean = false,
    val state: MockTagState = MockTagState.EMPTY,
    val fault: MockNfcFault = MockNfcFault.NONE,
    val cloudFault: MockCloudFault = MockCloudFault.NONE,
    val uid: String = newMockUid(),
    val messageBase64: String? = null,
    val credentialHex: String? = null,
    val batchVerificationFailureAt: Int = 0,
)

/**
 * Boundary shared by NFC backends. The real backend is Android's Tag/Ndef/NfcA path in
 * [NfcEngine]; this implementation is deliberately application-level and never receives a Tag.
 */
interface NfcOperations {
    val isMock: Boolean
    fun read(): TagSnapshot
    fun write(payload: NfcPayload)
    fun erase()
    fun authenticate(credential: NtagCredential)
    fun enableWriteProtection(credential: NtagCredential)
    fun removeWriteProtection(credential: NtagCredential)
    fun permanentlyLock()
}

class MockNfcOperations(context: Context) : NfcOperations {
    private val preferences = context.getSharedPreferences("mock_nfc_state", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _configuration = MutableStateFlow(load())
    val configuration: StateFlow<MockNfcConfiguration> = _configuration
    override val isMock: Boolean = true
    private var authenticatedUid: String? = null

    fun setEnabled(enabled: Boolean) = update(_configuration.value.copy(enabled = enabled))
    fun setState(state: MockTagState) {
        authenticatedUid = null
        val content = when (state) {
            MockTagState.CONTAINS_NDEF, MockTagState.WRITE_PROTECTED -> NfcPayload.UriPayload("https://example.com/old").message().toByteArray().base64()
            else -> null
        }
        update(_configuration.value.copy(state = state, messageBase64 = content, credentialHex = if (state == MockTagState.WRITE_PROTECTED) TEST_CREDENTIAL else null))
    }
    fun setFault(fault: MockNfcFault) = update(_configuration.value.copy(fault = fault))
    fun setCloudFault(fault: MockCloudFault) = update(_configuration.value.copy(cloudFault = fault))
    fun setBatchVerificationFailureAt(value: Int) = update(_configuration.value.copy(batchVerificationFailureAt = value.coerceAtLeast(0)))
    fun reset(state: MockTagState = MockTagState.EMPTY) { authenticatedUid = null; update(MockNfcConfiguration(enabled = _configuration.value.enabled, state = state)) }
    fun nextTag(state: MockTagState = MockTagState.EMPTY) { authenticatedUid = null; update(_configuration.value.copy(state = state, uid = newMockUid(), messageBase64 = null, credentialHex = null, fault = MockNfcFault.NONE)) }

    override fun read(): TagSnapshot {
        val value = _configuration.value
        failForState(value)
        val raw = value.messageBase64?.let { Base64.decode(it, Base64.NO_WRAP) }
        val message = raw?.let(::NdefMessage)
        return TagSnapshot(
            uid = value.uid,
            technologies = listOf("NfcA", "Ndef"),
            tagType = "NTAG215 (simulated)",
            capacity = NTAG215_CAPACITY,
            writable = value.state !in setOf(MockTagState.PERMANENTLY_LOCKED, MockTagState.UNSUPPORTED),
            canMakeReadOnly = value.state != MockTagState.PERMANENTLY_LOCKED,
            records = decode(message),
            rawMessage = raw,
            securityState = if (value.state == MockTagState.WRITE_PROTECTED) "protected" else "unprotected",
            securityVariant = "NTAG215",
            securityReason = "Application-level simulation only; no physical security was tested.",
            isMock = true,
        )
    }

    override fun write(payload: NfcPayload) {
        val value = _configuration.value
        failForState(value)
        fail(value.fault, setOf(MockNfcFault.TAG_REMOVED, MockNfcFault.COMMUNICATION_LOST, MockNfcFault.WRITE_FAILURE, MockNfcFault.NDEF_UNSUPPORTED, MockNfcFault.UNSUPPORTED_TAG))
        check(value.state != MockTagState.PERMANENTLY_LOCKED) { "Simulated tag is permanently locked." }
        check(value.state != MockTagState.WRITE_PROTECTED) { "Simulated authentication is required before writing." }
        val bytes = payload.message().toByteArray()
        check(value.fault != MockNfcFault.INSUFFICIENT_CAPACITY && bytes.size <= NTAG215_CAPACITY) { "Data is too large: ${bytes.size} bytes required, $NTAG215_CAPACITY available." }
        update(value.copy(state = MockTagState.CONTAINS_NDEF, messageBase64 = bytes.base64()))
    }

    fun writeAuthorized(payload: NfcPayload, credential: NtagCredential?) {
        val value = _configuration.value
        if (value.state == MockTagState.WRITE_PROTECTED) {
            if (credential != null) authenticate(credential)
            else check(authenticatedUid == value.uid) { "AUTHENTICATION REQUIRED\n\nTapTag does not have the simulated credential." }
            val bytes = payload.message().toByteArray()
            check(bytes.size <= NTAG215_CAPACITY) { "Data is too large for the simulated NTAG215." }
            update(value.copy(messageBase64 = bytes.base64()))
        } else write(payload)
    }

    override fun erase() {
        val value = _configuration.value
        failForState(value)
        check(value.state != MockTagState.PERMANENTLY_LOCKED) { "Simulated tag is permanently locked." }
        check(value.state != MockTagState.WRITE_PROTECTED) { "Simulated authentication is required before erasing." }
        val empty = NdefMessage(arrayOf(NdefRecord(NdefRecord.TNF_EMPTY, byteArrayOf(), byteArrayOf(), byteArrayOf())))
        update(value.copy(state = MockTagState.EMPTY, messageBase64 = empty.toByteArray().base64()))
    }

    fun eraseAuthorized(credential: NtagCredential?) {
        val value = _configuration.value
        if (value.state == MockTagState.WRITE_PROTECTED) {
            if (credential != null) authenticate(credential)
            else check(authenticatedUid == value.uid) { "AUTHENTICATION REQUIRED" }
            val empty = NdefMessage(arrayOf(NdefRecord(NdefRecord.TNF_EMPTY, byteArrayOf(), byteArrayOf(), byteArrayOf())))
            update(value.copy(messageBase64 = empty.toByteArray().base64()))
        } else erase()
    }

    override fun authenticate(credential: NtagCredential) {
        val value = _configuration.value
        fail(value.fault, setOf(MockNfcFault.WRONG_PASSWORD, MockNfcFault.AUTHENTICATION_FAILURE))
        val expected = value.credentialHex ?: error("The simulated tag is not password protected.")
        require(credential.encoded().toHex() == expected) { "AUTHENTICATION FAILED\n\nThe simulated credential was not accepted. No content was modified." }
        authenticatedUid = value.uid
    }

    override fun enableWriteProtection(credential: NtagCredential) {
        val value = _configuration.value
        fail(value.fault, setOf(MockNfcFault.PROTECTION_FAILURE))
        check(value.state != MockTagState.PERMANENTLY_LOCKED) { "Simulated tag is permanently locked." }
        update(value.copy(state = MockTagState.WRITE_PROTECTED, credentialHex = credential.encoded().toHex()))
    }

    override fun removeWriteProtection(credential: NtagCredential) {
        authenticate(credential)
        update(_configuration.value.copy(state = if (_configuration.value.messageBase64 == null) MockTagState.EMPTY else MockTagState.CONTAINS_NDEF, credentialHex = null))
    }

    override fun permanentlyLock() { authenticatedUid = null; update(_configuration.value.copy(state = MockTagState.PERMANENTLY_LOCKED, credentialHex = null)) }

    fun verify(expected: ByteArray) {
        val value = _configuration.value
        fail(value.fault, setOf(MockNfcFault.VERIFICATION_FAILURE))
        val actual = value.messageBase64?.let { Base64.decode(it, Base64.NO_WRAP) }
        require(actual?.contentEquals(expected) == true) { "Mock verification failed: simulated content differs from the requested payload." }
    }

    fun simulateUnauthorizedWrite(): String = when (_configuration.value.state) {
        MockTagState.WRITE_PROTECTED -> "❌ Simulated unauthorized write rejected"
        MockTagState.PERMANENTLY_LOCKED -> "❌ Simulated unauthorized write rejected permanently"
        MockTagState.UNSUPPORTED, MockTagState.COMMUNICATION_ERROR -> "❌ Simulated writer could not communicate with the tag"
        else -> "✓ Simulated unauthorized write allowed"
    }

    private fun failForState(value: MockNfcConfiguration) {
        when (value.state) {
            MockTagState.UNSUPPORTED -> error("Unsupported simulated tag")
            MockTagState.COMMUNICATION_ERROR -> error("Simulated NFC communication lost")
            else -> Unit
        }
    }

    private fun fail(actual: MockNfcFault, faults: Set<MockNfcFault>) {
        if (actual !in faults) return
        error(when (actual) {
            MockNfcFault.TAG_REMOVED -> "Simulated tag was removed during write."
            MockNfcFault.COMMUNICATION_LOST -> "Simulated NFC communication was lost."
            MockNfcFault.UNSUPPORTED_TAG -> "Unsupported simulated NFC tag."
            MockNfcFault.NDEF_UNSUPPORTED -> "The simulated tag does not support NDEF."
            MockNfcFault.INSUFFICIENT_CAPACITY -> "The simulated NTAG215 has insufficient capacity."
            MockNfcFault.WRITE_FAILURE -> "Simulated NDEF write failed."
            MockNfcFault.VERIFICATION_FAILURE -> "Simulated verification failure."
            MockNfcFault.WRONG_PASSWORD, MockNfcFault.AUTHENTICATION_FAILURE -> "AUTHENTICATION FAILED\n\nThe simulated credential was not accepted. No content was modified."
            MockNfcFault.PROTECTION_FAILURE -> "Simulated protection configuration failed."
            MockNfcFault.BACKGROUND_DURING_WRITE,
            MockNfcFault.BACKGROUND_DURING_VERIFY,
            MockNfcFault.BACKGROUND_DURING_PROTECTION -> return
            MockNfcFault.NONE -> return
        })
    }

    private fun update(value: MockNfcConfiguration) {
        _configuration.value = value
        preferences.edit().putString(KEY, json.encodeToString(value)).apply()
    }

    private fun load(): MockNfcConfiguration = preferences.getString(KEY, null)?.let {
        runCatching { json.decodeFromString<MockNfcConfiguration>(it) }.getOrNull()
    } ?: MockNfcConfiguration()

    private fun decode(message: NdefMessage?): List<DecodedRecord> = message?.records?.map { record ->
        when {
            record.tnf == NdefRecord.TNF_EMPTY -> DecodedRecord("TNF 0", "")
            record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_URI) -> DecodedRecord("URI", record.toUri()?.toString().orEmpty())
            record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_TEXT) -> {
                DecodedRecord("Text", decodeNdefText(record)?.text.orEmpty())
            }
            record.tnf == NdefRecord.TNF_MIME_MEDIA -> DecodedRecord(record.type.toString(Charsets.US_ASCII), "Stored MIME credential")
            else -> DecodedRecord("TNF ${record.tnf}", record.payload.toHex())
        }
    } ?: emptyList()

    companion object {
        const val NTAG215_CAPACITY = 504
        const val TEST_CREDENTIAL = "01020304A1B2"
        private const val KEY = "configuration"
    }
}

private fun ByteArray.base64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
private fun newMockUid(): String = "MOCK-${UUID.randomUUID().toString().take(8).uppercase()}"
