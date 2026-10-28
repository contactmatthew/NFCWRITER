package com.nfcwriter.app.domain

import android.nfc.NdefRecord
import android.nfc.NdefMessage
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NfcPayloadTest {
    @Test fun validatesSupportedUris() { assertTrue(validateUri("https://example.com/review")); assertTrue(validateUri("tel:+15551234")); assertFalse(validateUri("javascript:alert(1)")); assertFalse(validateUri("example.com")) }
    @Test fun uriProducesUriRecordAndExactSize() { val payload = NfcPayload.UriPayload("https://example.com"); val message = payload.message(); assertTrue(message.records.single().tnf == NdefRecord.TNF_WELL_KNOWN); assertEquals(message.toByteArray().size, payload.byteSize()) }
    @Test fun webUrlsDefaultToHttpsAndPreserveExplicitSchemes() {
        assertEquals("https://example.com", normalizeWebUrl(" example.com "))
        assertEquals("https://example.com/path", normalizeWebUrl("https://example.com/path"))
        assertEquals("http://example.com", normalizeWebUrl("http://example.com"))
        assertNull(normalizeWebUrl("mailto:hello@example.com"))
    }
    @Test fun webPayloadNormalizationIsAppliedByTypeOnly() {
        assertEquals("https://example.com", (NfcPayload.UriPayload("example.com").normalizedForWrite() as NfcPayload.UriPayload).uri)
        assertEquals("tel:+15551234", (NfcPayload.UriPayload("tel:+15551234", "phone").normalizedForWrite() as NfcPayload.UriPayload).uri)
    }
    @Test fun verificationRequiresUriRecordAndExactDecodedUri() {
        val expected = NfcPayload.UriPayload("https://example.com")
        assertTrue(ndefMessageMatchesPayload(expected.message().toByteArray(), expected))
        val textMessage = NdefMessage(arrayOf(NdefRecord.createTextRecord("en", "https://example.com")))
        assertFalse(ndefMessageMatchesPayload(textMessage.toByteArray(), expected))
        assertFalse(ndefMessageMatchesPayload(NfcPayload.UriPayload("https://example.org").message().toByteArray(), expected))
    }
    @Test fun plainTextIsUtf8RtdTextAndRoundTripsExactly() {
        val original = "Hello! Ito yung message ko ❤️\nSalamat sa pag-tap! — ñ, ₱, 😊"
        val payload = NfcPayload.Text(original, "fil")
        val record = payload.records().single()
        assertEquals(NdefRecord.TNF_WELL_KNOWN, record.tnf)
        assertArrayEquals(NdefRecord.RTD_TEXT, record.type)
        val decoded = requireNotNull(decodeNdefText(record))
        assertTrue(decoded.isUtf8)
        assertEquals("fil", decoded.language)
        assertEquals(original, decoded.text)
        assertTrue(ndefMessageMatchesPayload(payload.message().toByteArray(), payload))
    }
    @Test fun plainTextVerificationRejectsMimeTextAndChangedText() {
        val expected = NfcPayload.Text("Eksaktong mensahe ❤️\nIkalawang linya")
        val mime = NdefMessage(arrayOf(NdefRecord.createMime("text/plain", expected.text.toByteArray(Charsets.UTF_8))))
        assertFalse(ndefMessageMatchesPayload(mime.toByteArray(), expected))
        assertFalse(ndefMessageMatchesPayload(NfcPayload.Text("Ibang mensahe").message().toByteArray(), expected))
    }
    @Test fun uriPayloadSerializesWithoutTypeDiscriminatorConflict() { val json = NfcPayload.UriPayload("https://example.com").redactedJson(); assertTrue(json.contains("https://example.com")); assertTrue(json.contains("\"kind\":\"uri\"")) }
    @Test fun wifiSecretIsRedactedFromCloudJson() { val json = NfcPayload.Wifi("Home", "WPA2", "super-secret").redactedJson(); assertFalse(json.contains("super-secret")); assertTrue(json.contains("__DEVICE_SECRET__")) }
    @Test fun wpa3UsesSaeCredentialWithoutPretendingToBeWpa2() {
        val record = NfcPayload.Wifi("Hotspot", "WPA3", "safe;password").records().single()
        assertEquals(NdefRecord.TNF_WELL_KNOWN, record.tnf)
        val text = record.payload.copyOfRange(3, record.payload.size).toString(Charsets.UTF_8)
        assertEquals("WIFI:T:SAE;S:Hotspot;P:safe\\;password;;", text)
    }
    @Test fun transitionModeRetainsStandardWscMimeRecord() {
        val record = NfcPayload.Wifi("Hotspot", "WPA2_WPA3", "safe-password").records().single()
        assertEquals(NdefRecord.TNF_MIME_MEDIA, record.tnf)
        assertEquals("application/vnd.wfa.wsc", record.type.toString(Charsets.US_ASCII))
    }
    @Test fun multiplePayloadContainsAllRecords() { val payload = NfcPayload.Multiple(listOf(NfcPayload.UriPayload("https://example.com"), NfcPayload.Text("hello"))); assertEquals(2, payload.message().records.size) }
}
