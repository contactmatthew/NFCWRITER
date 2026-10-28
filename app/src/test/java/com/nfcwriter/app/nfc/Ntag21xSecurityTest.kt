package com.nfcwriter.app.nfc

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

class Ntag21xSecurityTest {
    @Test fun exactGetVersionResponsesIdentifyOnlySupportedVariants() {
        assertEquals(Ntag21xVariant.NTAG213, Ntag21xVariant.fromGetVersion(version(0x0F)))
        assertEquals(Ntag21xVariant.NTAG215, Ntag21xVariant.fromGetVersion(version(0x11)))
        assertEquals(Ntag21xVariant.NTAG216, Ntag21xVariant.fromGetVersion(version(0x13)))
        assertNull(Ntag21xVariant.fromGetVersion(version(0x12)))
        assertNull(Ntag21xVariant.fromGetVersion(version(0x0F).also { it[1] = 0x05 }))
        assertNull(Ntag21xVariant.fromGetVersion(byteArrayOf(0, 4, 4)))
    }

    @Test fun generatedCredentialsHaveExactChipSizesAndRoundTrip() {
        val first = NtagCredentialGenerator.generate(SecureRandom())
        val second = NtagCredentialGenerator.generate(SecureRandom())
        assertEquals(4, first.password.size)
        assertEquals(2, first.pack.size)
        assertFalse(first.encoded().contentEquals(second.encoded()))
        val decoded = NtagCredential.decode(first.encoded())
        assertArrayEquals(first.password, decoded.password)
        assertArrayEquals(first.pack, decoded.pack)
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedCredentialIsRejected() { NtagCredential.decode(ByteArray(5)) }

    @Test fun customHexCredentialUsesExactPwdThenPackBytes() {
        val credential = NtagCredential.fromHex("01:23:45:67:89:AB")
        assertArrayEquals(byteArrayOf(0x01, 0x23, 0x45, 0x67), credential.password)
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0xAB.toByte()), credential.pack)
    }

    @Test(expected = IllegalArgumentException::class)
    fun customCredentialIsNeverSilentlyPadded() { NtagCredential.fromHex("1234") }

    @Test fun shortNdefMessageUsesStandardType2TlvAndPagePadding() {
        val encoded = encodeType2NdefTlv(byteArrayOf(0x11, 0x22, 0x33))
        assertArrayEquals(byteArrayOf(0x03, 0x03, 0x11, 0x22, 0x33, 0xFE.toByte(), 0, 0), encoded)
    }

    @Test fun longNdefMessageUsesExtendedType2TlvLength() {
        val message = ByteArray(300) { it.toByte() }
        val encoded = encodeType2NdefTlv(message)
        assertArrayEquals(byteArrayOf(0x03, 0xFF.toByte(), 0x01, 0x2C), encoded.copyOfRange(0, 4))
        assertArrayEquals(message, encoded.copyOfRange(4, 304))
        assertEquals(0xFE, encoded[304].toInt() and 0xFF)
        assertEquals(0, encoded.size % 4)
    }

    private fun version(size: Int) = byteArrayOf(0x00, 0x04, 0x04, 0x02, 0x01, 0x00, size.toByte(), 0x03)
}
