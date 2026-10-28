package com.nfcwriter.app.nfc

import android.app.Activity
import com.nfcwriter.app.domain.NfcPayload
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NfcCriticalOperationTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val engine = NfcEngine(activity)

    @After fun close() = engine.close()

    @Test fun onlyPhysicalMutationStagesLockInAppNavigation() {
        listOf(
            NfcStage.AUTHENTICATING,
            NfcStage.WRITING,
            NfcStage.VERIFYING,
            NfcStage.APPLYING_PROTECTION,
            NfcStage.VERIFYING_PROTECTION,
            NfcStage.ERASING,
        ).forEach { assertTrue(NfcUiState(stage = it).isCriticalOperation) }

        listOf(NfcStage.READY, NfcStage.INTERRUPTED, NfcStage.CONNECTION_LOST, NfcStage.SUCCESS, NfcStage.SAFE_FAILURE)
            .forEach { assertFalse(NfcUiState(stage = it).isCriticalOperation) }
    }

    @Test fun mockBackgroundDuringWritePublishesRecoverableInterruption() {
        engine.setMockMode(true)
        engine.setMockFault(MockNfcFault.BACKGROUND_DURING_WRITE)
        engine.begin(PendingNfcOperation.Write(NfcPayload.Text("critical")))

        waitFor { engine.state.value.stage == NfcStage.INTERRUPTED }

        assertEquals(NfcStage.WRITING, engine.state.value.interruptedStage)
        assertTrue(engine.state.value.isMock)
        assertTrue(engine.state.value.message.contains("NFC OPERATION INTERRUPTED"))
    }

    @Test fun mockTagLossNeverReportsSuccess() {
        engine.setMockMode(true)
        engine.setMockFault(MockNfcFault.TAG_REMOVED)
        engine.begin(PendingNfcOperation.Write(NfcPayload.Text("critical")))

        waitFor { engine.state.value.stage == NfcStage.CONNECTION_LOST }

        assertFalse(engine.state.value.verified)
        assertTrue(engine.state.value.message.contains("TAG CONNECTION LOST"))
    }

    private fun waitFor(predicate: () -> Boolean) {
        repeat(40) {
            if (predicate()) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for NFC state. Last state: ${engine.state.value}")
    }
}
