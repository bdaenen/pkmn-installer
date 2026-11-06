package com.bennod.pkmninstaller.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallErrorsTest {

    @Test
    fun `maps known failure to a friendly message and keeps the raw reason`() {
        val message = InstallErrors.friendly("INSTALL_FAILED_VERSION_DOWNGRADE")
        assertTrue(message.contains("newer version"))
        assertTrue(message.contains("INSTALL_FAILED_VERSION_DOWNGRADE"))
    }

    @Test
    fun `maps a failure with extra detail`() {
        val message = InstallErrors.friendly(
            "INSTALL_FAILED_INSUFFICIENT_STORAGE: Not enough space",
        )
        assertTrue(message.contains("Not enough free storage"))
        assertTrue(message.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE"))
    }

    @Test
    fun `passes through unknown failures unchanged`() {
        assertEquals("SOMETHING_ELSE", InstallErrors.friendly("SOMETHING_ELSE"))
    }
}
