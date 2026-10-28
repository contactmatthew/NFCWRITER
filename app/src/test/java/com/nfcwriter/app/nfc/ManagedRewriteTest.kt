package com.nfcwriter.app.nfc

import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedRewriteTest {
    private fun snapshot(vararg records: DecodedRecord) = TagSnapshot(
        uid = "04:AA:BB:CC", technologies = emptyList(), tagType = "NTAG213",
        capacity = 144, writable = true, canMakeReadOnly = true,
        records = records.toList(), rawMessage = null,
    )

    @Test fun rewritePassesTheUrlActuallyReadFromTheSameCard() {
        val old = snapshot(DecodedRecord("URI", "https://taptagdirect.tech/t/ABCDEFG"))
        assertEquals("https://taptagdirect.tech/t/ABCDEFG", previousManagedUrlForRewrite("rewrite", old))
        assertEquals("https://taptagdirect.tech/t/ABCDEFG",
            managedUrlReferenceForRewrite("rewrite", old, "https://taptagdirect.tech/t/NEWCARD7"))
        assertEquals(null, previousManagedUrlForRewrite("write", old))
        assertEquals(null, previousManagedUrlForRewrite("rewrite", snapshot(DecodedRecord("Text", "hello"))))
    }

    @Test fun rewriteOfAssignedCardWithoutReadableOldUrlUsesNewUrlForUidBasedTransfer() {
        val scanned = snapshot(DecodedRecord("Text", "previous content"))
        assertEquals(
            "https://taptagdirect.tech/t/NEWCARD7",
            managedUrlReferenceForRewrite("rewrite", scanned, "https://taptagdirect.tech/t/NEWCARD7"),
        )
        assertEquals(null, managedUrlReferenceForRewrite("write", scanned, "https://taptagdirect.tech/t/NEWCARD7"))
    }
}
