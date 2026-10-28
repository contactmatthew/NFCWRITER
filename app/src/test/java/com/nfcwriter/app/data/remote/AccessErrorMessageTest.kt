package com.nfcwriter.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AccessErrorMessageTest {
    @Test
    fun `anonymous provider error is converted to safe setup guidance`() {
        val rawError = IllegalStateException(
            "anonymous_provider_disabled: Anonymous sign-ins are disabled; " +
                "Headers: Authorization=Bearer secret-token; apikey=secret-key",
        )

        val message = accessErrorMessage(rawError)

        assertEquals(
            "TapTag access is not enabled on this server yet. Ask the administrator to enable Anonymous Sign-Ins in Supabase Authentication.",
            message,
        )
        assertFalse(message.contains("Bearer"))
        assertFalse(message.contains("apikey"))
    }

    @Test
    fun `unknown remote errors never expose implementation details`() {
        val message = accessErrorMessage(IllegalStateException("POST /auth/v1/signup token=private"))

        assertEquals("Access could not be verified. Please try again.", message)
    }
}
