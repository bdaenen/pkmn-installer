package com.bennod.pkmninstaller.install

/**
 * Parses the textual output of the `pm install-*` commands.
 *
 * These helpers are pure so they can be unit-tested without a device.
 */
object PmOutput {

    private val SESSION_ID = Regex("""\[(\d+)]""")

    /**
     * Extracts the session id from `pm install-create` output, e.g.
     * `Success: created install session [1234]`.
     */
    fun parseSessionId(stdout: String): Int? =
        SESSION_ID.find(stdout)?.groupValues?.get(1)?.toIntOrNull()

    /** Whether the command reported success. */
    fun isSuccess(stdout: String): Boolean =
        stdout.contains("Success", ignoreCase = true)

    /**
     * Extracts a human-readable failure reason from the command output, e.g.
     * `Failure [INSTALL_FAILED_INVALID_APK: ...]`.
     */
    fun failureReason(stdout: String, stderr: String): String {
        val combined = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n")
        val failure = Regex("""Failure\s*\[(.*)]""", RegexOption.DOT_MATCHES_ALL)
            .find(combined)?.groupValues?.get(1)?.trim()
        return failure?.takeIf { it.isNotEmpty() }
            ?: combined.ifBlank { "Unknown error" }
    }
}
