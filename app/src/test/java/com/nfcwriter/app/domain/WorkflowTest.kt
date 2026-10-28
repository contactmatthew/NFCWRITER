package com.nfcwriter.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkflowTest {
    @Test fun writeRequiresPhysicalVerificationScan() { var s = WriteStep.READY; s = reduceWriteStep(s, WriteEvent.TagDetected); s = reduceWriteStep(s, WriteEvent.WriteStarted); s = reduceWriteStep(s, WriteEvent.WriteCompleted); assertEquals(WriteStep.AWAITING_RESCAN, s); s = reduceWriteStep(s, WriteEvent.VerificationScan); s = reduceWriteStep(s, WriteEvent.Verified); assertEquals(WriteStep.SUCCESS, s) }
    @Test fun failureMovesAnyStepToError() { assertEquals(WriteStep.ERROR, reduceWriteStep(WriteStep.WRITING, WriteEvent.Failed("lost"))) }
    @Test fun duplicateDoesNotIncrementCompleted() { val tracker = BatchTracker(2); assertEquals("verified", tracker.record("AA", true)); assertEquals("duplicate", tracker.record("AA", true)); assertEquals(1, tracker.completed); assertEquals(1, tracker.duplicates); assertEquals("failed", tracker.record("BB", false)); assertEquals(true, tracker.finished) }
    @Test fun existingWritableContentRequiresConfirmation() { assertEquals(WritePreflightDecision.REQUIRE_REPLACE_CONFIRMATION, decideWritePreflight(true, true, 540, 48)) }
    @Test fun blankWritableTagCanBeWrittenImmediately() { assertEquals(WritePreflightDecision.WRITE_BLANK, decideWritePreflight(false, true, 540, 48)) }
    @Test fun readOnlyAndCapacityChecksBlockBeforeWrite() { assertEquals(WritePreflightDecision.BLOCK_READ_ONLY, decideWritePreflight(true, false, 540, 48)); assertEquals(WritePreflightDecision.BLOCK_CAPACITY, decideWritePreflight(false, true, 32, 48)) }
    @Test fun uidGuardAllowsUnknownUidButRejectsDifferentExposedUid() { assertEquals(true, isExpectedTag(null, "BB")); assertEquals(true, isExpectedTag("AA", "AA")); assertEquals(false, isExpectedTag("AA", "BB")) }
}
