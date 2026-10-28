package com.nfcwriter.app.nfc

import android.content.Context
import com.nfcwriter.app.domain.NfcPayload
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MockNfcOperationsTest {
    private lateinit var operations: MockNfcOperations

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("mock_nfc_state", Context.MODE_PRIVATE).edit().clear().commit()
        operations = MockNfcOperations(context)
    }

    @Test fun writeRewriteAndErasePersistInSimulatedTag() {
        val old = NfcPayload.UriPayload("https://example.com/old")
        val replacement = NfcPayload.UriPayload("https://example.com/new")
        operations.write(old)
        assertEquals("https://example.com/old", operations.read().records.single().content)
        operations.write(replacement)
        operations.verify(replacement.message().toByteArray())
        assertEquals("https://example.com/new", operations.read().records.single().content)
        operations.erase()
        assertTrue(operations.read().records.all { it.content.isEmpty() })
        assertTrue(operations.read().writable == true)
    }

    @Test fun protectedTagRejectsWrongCredentialAndAcceptsCorrectCredential() {
        val correct = NtagCredential.fromHex("01020304A1B2")
        val wrong = NtagCredential.fromHex("FFFFFFFFFFFF")
        operations.write(NfcPayload.Text("before"))
        operations.enableWriteProtection(correct)
        assertEquals("protected", operations.read().securityState)
        assertFails { operations.authenticate(wrong) }
        operations.authenticate(correct)
        operations.writeAuthorized(NfcPayload.Text("after"), correct)
        assertEquals("after", operations.read().records.single().content)
    }

    @Test fun protectedAndLockedTagsRejectUnauthorizedWrites() {
        operations.write(NfcPayload.Text("original"))
        operations.enableWriteProtection(NtagCredential.fromHex("01020304A1B2"))
        assertTrue(operations.simulateUnauthorizedWrite().contains("rejected"))
        operations.permanentlyLock()
        assertTrue(operations.simulateUnauthorizedWrite().contains("permanently"))
        assertFails { operations.write(NfcPayload.Text("changed")) }
    }

    @Test fun capacityAndVerificationFaultsAreReportedWithoutChangingTheClaim() {
        assertFails { operations.write(NfcPayload.Text("x".repeat(MockNfcOperations.NTAG215_CAPACITY + 20))) }
        val payload = NfcPayload.Text("verified")
        operations.write(payload)
        operations.setFault(MockNfcFault.VERIFICATION_FAILURE)
        assertFails { operations.verify(payload.message().toByteArray()) }
    }

    @Test fun mockSnapshotIsExplicitAndUsesTestOnlyUid() {
        val snapshot = operations.read()
        assertTrue(snapshot.isMock)
        assertTrue(snapshot.uid!!.startsWith("MOCK-"))
        assertEquals("NTAG215 (simulated)", snapshot.tagType)
        assertEquals(MockNfcOperations.NTAG215_CAPACITY, snapshot.capacity)
        assertEquals(listOf("NfcA", "Ndef"), snapshot.technologies)
    }

    private fun assertFails(block: () -> Unit) {
        try { block(); fail("Expected simulated operation to fail") } catch (_: RuntimeException) { }
    }
}
