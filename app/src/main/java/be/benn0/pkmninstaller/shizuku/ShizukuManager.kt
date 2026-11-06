package be.benn0.pkmninstaller.shizuku

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Snapshot of the current Shizuku state, used to drive the UI.
 */
sealed interface ShizukuState {
    /** Shizuku (or Sui) is not installed / not running. */
    data object NotRunning : ShizukuState

    /** The binder is alive but we have not been granted permission yet. */
    data object PermissionRequired : ShizukuState

    /** The user permanently denied the request ("deny and don't ask again"). */
    data object PermissionDenied : ShizukuState

    /** Ready to run commands. [uid] is 2000 for ADB, 0 for root. */
    data class Ready(val uid: Int) : ShizukuState
}

/**
 * Thin wrapper around the Shizuku API.
 *
 * All calls are guarded so the UI can query state without crashing when Shizuku
 * is absent. The binder must be alive before most [Shizuku] methods are called,
 * so [state] checks [Shizuku.pingBinder] first.
 */
object ShizukuManager {

    const val PERMISSION_REQUEST_CODE = 1001

    /** Returns the current state without triggering a permission request. */
    fun state(): ShizukuState {
        if (!Shizuku.pingBinder()) return ShizukuState.NotRunning
        if (Shizuku.isPreV11()) return ShizukuState.NotRunning

        return when {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                ShizukuState.Ready(Shizuku.getUid())

            Shizuku.shouldShowRequestPermissionRationale() ->
                ShizukuState.PermissionDenied

            else -> ShizukuState.PermissionRequired
        }
    }

    /** Requests Shizuku permission; the result arrives via the listener. */
    fun requestPermission() {
        if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        }
    }

    fun addPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.addRequestPermissionResultListener(listener)
    }

    fun removePermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.removeRequestPermissionResultListener(listener)
    }

    fun addBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener) {
        Shizuku.addBinderReceivedListenerSticky(listener)
    }

    fun removeBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener) {
        Shizuku.removeBinderReceivedListener(listener)
    }

    fun addBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
        Shizuku.addBinderDeadListener(listener)
    }

    fun removeBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
        Shizuku.removeBinderDeadListener(listener)
    }
}
