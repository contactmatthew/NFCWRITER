package com.nfcwriter.app.domain

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.net.Uri
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

@Serializable
sealed interface NfcPayload {
    val type: String
    fun records(): List<NdefRecord>

    @Serializable @SerialName("uri") data class UriPayload(val uri: String, override val type: String = "uri") : NfcPayload {
        override fun records() = listOf(NdefRecord.createUri(Uri.parse(uri)))
    }
    @Serializable @SerialName("wifi") data class Wifi(val ssid: String, val security: String, val password: String? = null, override val type: String = "wifi") : NfcPayload {
        override fun records(): List<NdefRecord> {
            val normalizedSecurity = security.uppercase()

            // The legacy Wi-Fi Simple Configuration NDEF credential has no
            // WPA3-SAE authentication value. Preserve WPA3 honestly as the
            // widely understood Wi-Fi credential text instead of claiming it
            // is WPA2. Compatible credential readers can import T:SAE.
            if (normalizedSecurity in setOf("WPA3", "SAE")) {
                val credential = "WIFI:T:SAE;S:${wifiEscape(ssid)};P:${wifiEscape(password.orEmpty())};;"
                return listOf(NdefRecord.createTextRecord("en", credential))
            }

            val auth = when (normalizedSecurity) {
                "NOPASS", "OPEN" -> 0x0001
                "WEP" -> 0x0004
                "WPA" -> 0x0002
                else -> 0x0020 // WPA2 and WPA2/WPA3 transition mode
            }
            val encryption = when (normalizedSecurity) {
                "NOPASS", "OPEN" -> 0x0001
                "WEP" -> 0x0002
                "WPA" -> 0x0004
                else -> 0x0008
            }
            fun attr(id: Int, value: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> DataOutputStream(out).use { data -> data.writeShort(id); data.writeShort(value.size); data.write(value) } }.toByteArray()
            fun short(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())
            val credential = ByteArrayOutputStream().apply {
                write(attr(0x1026, byteArrayOf(1))) // Network Index
                write(attr(0x1045, ssid.toByteArray(StandardCharsets.UTF_8)))
                write(attr(0x1003, short(auth)))
                write(attr(0x100F, short(encryption)))
                write(attr(0x1027, password.orEmpty().toByteArray(StandardCharsets.UTF_8)))
                write(attr(0x1020, byteArrayOf(-1, -1, -1, -1, -1, -1)))
            }.toByteArray()
            return listOf(NdefRecord.createMime("application/vnd.wfa.wsc", attr(0x100E, credential)))
        }
    }
    @Serializable @SerialName("contact") data class Contact(val name: String, val phone: String = "", val email: String = "", val company: String = "", val website: String = "", val address: String = "", val notes: String = "", override val type: String = "contact") : NfcPayload {
        override fun records(): List<NdefRecord> {
            val vcard = buildString { appendLine("BEGIN:VCARD"); appendLine("VERSION:3.0"); appendLine("FN:${vEscape(name)}"); if (phone.isNotBlank()) appendLine("TEL:${vEscape(phone)}"); if (email.isNotBlank()) appendLine("EMAIL:${vEscape(email)}"); if (company.isNotBlank()) appendLine("ORG:${vEscape(company)}"); if (website.isNotBlank()) appendLine("URL:${vEscape(website)}"); if (address.isNotBlank()) appendLine("ADR:;;${vEscape(address)};;;;"); if (notes.isNotBlank()) appendLine("NOTE:${vEscape(notes)}"); append("END:VCARD") }
            return listOf(NdefRecord.createMime("text/vcard", vcard.toByteArray(StandardCharsets.UTF_8)))
        }
    }
    @Serializable @SerialName("text") data class Text(val text: String, val language: String = "en", override val type: String = "text") : NfcPayload {
        override fun records() = listOf(NdefRecord.createTextRecord(language, text))
    }
    @Serializable @SerialName("multiple") data class Multiple(val items: List<NfcPayload>, override val type: String = "multiple") : NfcPayload {
        override fun records() = items.flatMap { it.records() }
    }

    fun message() = NdefMessage(records().toTypedArray())
    fun byteSize() = message().toByteArray().size
    fun redactedJson(): String = when (this) {
        is Wifi -> Json.encodeToString(copy(password = if (password.isNullOrEmpty()) null else "__DEVICE_SECRET__"))
        else -> payloadJson.encodeToString(serializer(), this)
    }

    companion object {
        private val payloadJson = Json { classDiscriminator = "kind"; encodeDefaults = true }
        private fun vEscape(value: String) = value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")
        private fun wifiEscape(value: String) = value
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace(":", "\\:")
            .replace("\"", "\\\"")
    }
}

fun validateUri(value: String): Boolean = runCatching {
    val u = Uri.parse(value.trim()); u.scheme?.lowercase() in setOf("https", "http", "mailto", "tel", "sms", "smsto", "geo") && !u.scheme.isNullOrBlank()
}.getOrDefault(false)

/** Normalizes a web destination without changing an explicitly supplied HTTP(S) scheme. */
fun normalizeWebUrl(value: String): String? = runCatching {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    val suppliedScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:").find(trimmed)?.value
    if (suppliedScheme != null && !trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) return null
    val normalized = if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
        trimmed
    } else {
        "https://$trimmed"
    }
    val uri = Uri.parse(normalized)
    normalized.takeIf {
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
    }
}.getOrNull()

private val webPayloadTypes = setOf("uri", "google_review", "social")

fun NfcPayload.normalizedForWrite(): NfcPayload = when (this) {
    is NfcPayload.UriPayload -> if (type in webPayloadTypes) {
        normalizeWebUrl(uri)?.let { copy(uri = it) } ?: this
    } else this
    is NfcPayload.Multiple -> copy(items = items.map(NfcPayload::normalizedForWrite))
    else -> this
}

data class DecodedNdefText(val language: String, val text: String, val isUtf8: Boolean)

fun decodeNdefText(record: NdefRecord): DecodedNdefText? = runCatching {
    if (record.tnf != NdefRecord.TNF_WELL_KNOWN || !record.type.contentEquals(NdefRecord.RTD_TEXT)) return null
    val payload = record.payload
    if (payload.isEmpty()) return null
    val status = payload[0].toInt() and 0xFF
    val languageLength = status and 0x3F
    if (payload.size < 1 + languageLength) return null
    val isUtf8 = status and 0x80 == 0
    val language = payload.copyOfRange(1, 1 + languageLength).toString(StandardCharsets.US_ASCII)
    val charset = if (isUtf8) StandardCharsets.UTF_8 else StandardCharsets.UTF_16
    val text = payload.copyOfRange(1 + languageLength, payload.size).toString(charset)
    DecodedNdefText(language, text, isUtf8)
}.getOrNull()

/**
 * Confirms both the serialized NDEF bytes and URI semantics. The semantic check prevents a
 * Text record containing the same visible URL from being accepted as a verified URI write.
 */
fun ndefMessageMatchesPayload(rawMessage: ByteArray?, expected: NfcPayload): Boolean = runCatching {
    if (rawMessage == null) return false
    val expectedMessage = expected.message()
    if (!rawMessage.contentEquals(expectedMessage.toByteArray())) return false
    val actualRecords = NdefMessage(rawMessage).records
    val expectedRecords = expectedMessage.records
    if (actualRecords.size != expectedRecords.size) return false
    expectedRecords.indices.all { index ->
        val wanted = expectedRecords[index]
        val actual = actualRecords[index]
        if (wanted.tnf == NdefRecord.TNF_WELL_KNOWN && wanted.type.contentEquals(NdefRecord.RTD_URI)) {
            actual.tnf == NdefRecord.TNF_WELL_KNOWN &&
                actual.type.contentEquals(NdefRecord.RTD_URI) &&
                actual.toUri()?.toString() == wanted.toUri()?.toString()
        } else if (wanted.tnf == NdefRecord.TNF_WELL_KNOWN && wanted.type.contentEquals(NdefRecord.RTD_TEXT)) {
            val wantedText = decodeNdefText(wanted)
            val actualText = decodeNdefText(actual)
            actualText != null && wantedText != null &&
                actualText.language == wantedText.language &&
                actualText.text == wantedText.text &&
                actualText.isUtf8 == wantedText.isUtf8
        } else {
            true
        }
    }
}.getOrDefault(false)
