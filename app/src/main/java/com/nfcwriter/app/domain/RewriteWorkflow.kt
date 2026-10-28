package com.nfcwriter.app.domain

enum class WritePreflightDecision {
    WRITE_BLANK,
    REQUIRE_REPLACE_CONFIRMATION,
    BLOCK_READ_ONLY,
    BLOCK_CAPACITY,
}

fun decideWritePreflight(hasContent: Boolean, writable: Boolean?, capacity: Int?, requiredBytes: Int): WritePreflightDecision = when {
    writable == false -> WritePreflightDecision.BLOCK_READ_ONLY
    capacity != null && requiredBytes > capacity -> WritePreflightDecision.BLOCK_CAPACITY
    hasContent -> WritePreflightDecision.REQUIRE_REPLACE_CONFIRMATION
    else -> WritePreflightDecision.WRITE_BLANK
}

fun isExpectedTag(expectedUid: String?, detectedUid: String?): Boolean =
    expectedUid == null || expectedUid == detectedUid
