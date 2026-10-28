package com.nfcwriter.app.domain

enum class WriteStep { READY, DETECTED, WRITING, AWAITING_RESCAN, VERIFYING, SUCCESS, ERROR }
sealed interface WriteEvent { data object TagDetected : WriteEvent; data object WriteStarted : WriteEvent; data object WriteCompleted : WriteEvent; data object VerificationScan : WriteEvent; data object Verified : WriteEvent; data class Failed(val reason: String) : WriteEvent }
fun reduceWriteStep(current: WriteStep, event: WriteEvent): WriteStep = when (current to event) {
    WriteStep.READY to WriteEvent.TagDetected -> WriteStep.DETECTED
    WriteStep.DETECTED to WriteEvent.WriteStarted -> WriteStep.WRITING
    WriteStep.WRITING to WriteEvent.WriteCompleted -> WriteStep.AWAITING_RESCAN
    WriteStep.AWAITING_RESCAN to WriteEvent.VerificationScan -> WriteStep.VERIFYING
    WriteStep.VERIFYING to WriteEvent.Verified -> WriteStep.SUCCESS
    else -> if (event is WriteEvent.Failed) WriteStep.ERROR else current
}

class BatchTracker(private val quantity: Int) {
    private val seen = mutableSetOf<String>()
    var completed = 0; private set
    var failed = 0; private set
    var duplicates = 0; private set
    init { require(quantity > 0) }
    fun record(uid: String?, verified: Boolean): String {
        if (uid != null && !seen.add(uid)) { duplicates++; return "duplicate" }
        if (verified) completed++ else failed++
        return if (verified) "verified" else "failed"
    }
    val finished get() = completed + failed >= quantity
}
