package be.benn0.pkmninstaller.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreflightTest {

    @Test
    fun `no warning when the app is not installed`() {
        assertNull(Preflight.versionWarning(installed = null, incoming = 100, allowDowngrade = false))
    }

    @Test
    fun `downgrade is blocking when not allowed`() {
        val warning = Preflight.versionWarning(installed = 200, incoming = 100, allowDowngrade = false)
        assertTrue(warning!!.blocking)
        assertTrue(warning.message.contains("Allow downgrade"))
    }

    @Test
    fun `downgrade is allowed when opted in`() {
        val warning = Preflight.versionWarning(installed = 200, incoming = 100, allowDowngrade = true)
        assertFalse(warning!!.blocking)
        assertTrue(warning.message.contains("Downgrading"))
    }

    @Test
    fun `same version is an informational reinstall`() {
        val warning = Preflight.versionWarning(installed = 100, incoming = 100, allowDowngrade = false)
        assertFalse(warning!!.blocking)
        assertTrue(warning.message.contains("reinstall"))
    }

    @Test
    fun `upgrade produces no warning`() {
        assertNull(Preflight.versionWarning(installed = 100, incoming = 200, allowDowngrade = false))
    }

    @Test
    fun `warning keeps the version codes`() {
        val warning = Preflight.versionWarning(installed = 399771, incoming = 399770, allowDowngrade = false)
        assertEquals(true, warning!!.blocking)
        assertTrue(warning.message.contains("399771"))
        assertTrue(warning.message.contains("399770"))
    }
}
