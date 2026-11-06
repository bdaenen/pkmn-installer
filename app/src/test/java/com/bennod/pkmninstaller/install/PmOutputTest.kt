package com.bennod.pkmninstaller.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PmOutputTest {

    @Test
    fun `parses session id from install-create output`() {
        val stdout = "Success: created install session [1234]"
        assertEquals(1234, PmOutput.parseSessionId(stdout))
    }

    @Test
    fun `returns null when no session id is present`() {
        assertNull(PmOutput.parseSessionId("Failure [INSTALL_FAILED_INVALID_APK]"))
    }

    @Test
    fun `detects success`() {
        assertTrue(PmOutput.isSuccess("Success"))
        assertTrue(PmOutput.isSuccess("Success: streamed 1234 bytes"))
        assertFalse(PmOutput.isSuccess("Failure [INSTALL_FAILED_ALREADY_EXISTS]"))
    }

    @Test
    fun `extracts failure reason`() {
        val reason = PmOutput.failureReason(
            stdout = "Failure [INSTALL_FAILED_INVALID_APK: Package couldn't be installed]",
            stderr = "",
        )
        assertEquals("INSTALL_FAILED_INVALID_APK: Package couldn't be installed", reason)
    }

    @Test
    fun `falls back to combined output when no failure marker`() {
        val reason = PmOutput.failureReason(stdout = "", stderr = "adb: error")
        assertEquals("adb: error", reason)
    }

    @Test
    fun `falls back to unknown error when output is blank`() {
        assertEquals("Unknown error", PmOutput.failureReason(stdout = "", stderr = ""))
    }
}
