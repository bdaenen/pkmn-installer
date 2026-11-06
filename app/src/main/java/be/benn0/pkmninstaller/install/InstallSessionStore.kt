package be.benn0.pkmninstaller.install

import android.content.Context

/**
 * Persists the id of the in-flight `pm` install session.
 *
 * If the app process is killed mid-install (crash, low memory, force-stop), the
 * `pm` session is left dangling on the system. On the next launch we read the
 * stored id and run `pm install-abandon` to clean it up, so stale sessions do
 * not accumulate.
 *
 * Backed by [android.content.SharedPreferences] because it must survive process
 * death.
 */
class InstallSessionStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Records [sessionId] as the active session. */
    fun save(sessionId: Int) {
        prefs.edit().putInt(KEY_SESSION_ID, sessionId).apply()
    }

    /** Clears the active session (after commit/abandon). */
    fun clear() {
        prefs.edit().remove(KEY_SESSION_ID).apply()
    }

    /** Returns the last recorded session id, or `null` if there is none. */
    fun load(): Int? = prefs.getInt(KEY_SESSION_ID, NO_SESSION).takeIf { it != NO_SESSION }

    private companion object {
        const val PREFS_NAME = "install_session"
        const val KEY_SESSION_ID = "session_id"
        const val NO_SESSION = -1
    }
}
