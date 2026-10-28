package com.nfcwriter.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class WriterManagedUrlTest {
    private fun link(name: String, code: String, source: String, recipient: String? = null) = WriterManagedUrl(
        nfcId = code, linkId = code, name = name, publicCode = code,
        permanentPath = "/t/$code", pageTitle = name, status = "provisioning",
        createdAt = "2026-10-19T00:00:00Z", sourceType = source, recipientName = recipient,
    )

    @Test
    fun filtersWithinChosenLinkTypeByNameRecipientOrCode() {
        val urls = listOf(
            link("Birthday card", "MSG1234", "messages", "Mika"),
            link("Business card", "BIZ1234", "business"),
            link("Other greeting", "MSG5678", "messages", "Ana"),
        )
        assertEquals(listOf("MSG1234"), filterWriterManagedUrls(urls, "messages", "mika").map { it.publicCode })
        assertEquals(listOf("MSG5678"), filterWriterManagedUrls(urls, "messages", "msg5678").map { it.publicCode })
        assertEquals(listOf("MSG1234", "BIZ1234", "MSG5678"),
            filterWriterManagedUrls(urls, null, "").map { it.publicCode })
    }

    @Test
    fun usedMessageUrlMustAdvanceBeforeItCanBeWrittenAgain() {
        val used = link("Birthday card", "MSG1234", "messages").copy(status = "active", physicalUid = "04AABBCC")
        val next = link("Birthday card", "MSG5678", "messages")
        val business = link("Business card", "BIZ1234", "business").copy(status = "active")
        assertEquals(false, writerUrlReadyToWrite(used))
        assertEquals(true, writerUrlReadyToWrite(next))
        assertEquals(true, writerUrlReadyToWrite(business))
    }

    @Test
    fun dashboardUrlsMissingFromWriterListRemainVisibleButRetiredOnesCannotBeWritten() {
        val active = link("Active card", "ACTIVE1", "business")
        val retired = AdminManagedLink(
            id = "retired-id", name = "Video Tribute URL", code = "CJRYFX3",
            status = "disabled", createdAt = "2026-10-24T00:00:00Z",
            page = AdminManagedPage("Video Tribute", "published", "memorial"),
            managedNfc = AdminManagedNfc("retired-nfc", "retired", null),
        )
        val activeAdmin = AdminManagedLink(
            id = "ACTIVE1", name = "Active card", code = "ACTIVE1", status = "active",
            createdAt = "2026-10-19T00:00:00Z",
            page = AdminManagedPage("Active card", "published", "business"),
            managedNfc = AdminManagedNfc("ACTIVE1", "provisioning", null),
        )
        val result = mergeWriterManagedUrls(listOf(active), listOf(retired, activeAdmin))
        assertEquals(listOf("CJRYFX3", "ACTIVE1"), result.map { it.publicCode })
        assertEquals("memorials", result.first().sourceType)
        assertEquals(false, writerUrlReadyToWrite(result.first()))
        assertEquals("This URL was retired after a card rewrite. Reactivate it in the Dashboard to use the same URL again.", writerUrlUnavailableReason(result.first()))
        val reactivated = mergeWriterManagedUrls(
            emptyList(), listOf(retired.copy(status = "active", managedNfc = retired.managedNfc?.copy(status = "provisioning"))),
        ).single()
        assertEquals("CJRYFX3", reactivated.publicCode)
        assertEquals(true, writerUrlReadyToWrite(reactivated))
        assertEquals(true, writerUrlReadyToWrite(result.last()))
    }

    @Test
    fun joinsRelativeManagedPathToConfiguredSiteOrigin() {
        assertEquals(
            "https://taptagdirect.tech/t/B45MP58",
            resolveWriterUrl("https://taptagdirect.tech/", "/t/B45MP58"),
        )
    }

    @Test
    fun keepsAbsoluteManagedUrlUnchanged() {
        assertEquals(
            "https://tap.example/t/B45MP58",
            resolveWriterUrl("http://localhost:3000", "https://tap.example/t/B45MP58"),
        )
    }

    @Test
    fun replacesLegacyLocalhostManagedUrlWithConfiguredSiteOrigin() {
        assertEquals(
            "https://taptagdirect.tech/t/B45MP58?preview=1",
            resolveWriterUrl(
                "https://taptagdirect.tech",
                "http://localhost:3000/t/B45MP58?preview=1",
            ),
        )
    }
}
