// AIDL contract for the Shizuku user service.
//
// The service runs in a separate process with the identity of the Shizuku
// backend (uid 2000 / shell for ADB, uid 0 for root). This is the modern,
// future-proof replacement for the deprecated `Shizuku.newProcess(...)`.
//
// `destroy()` is a reserved Shizuku method: the transaction code must be
// 16777114 in AIDL (16777115 on the wire). Shizuku calls it when the service is
// stopped, and the implementation is expected to clean up and exit.
package com.bennod.pkmninstaller.shizuku;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface IUserService {
    void destroy() = 16777114;

    /**
     * Runs [command] through `sh -c` as the shell/root user and returns a
     * [Bundle] with keys [UserService.KEY_EXIT_CODE], [UserService.KEY_STDOUT]
     * and [UserService.KEY_STDERR].
     */
    Bundle exec(String command) = 1;

    /**
     * Runs `pm install-create -i <installerPackage> [-r] [-d] -S <totalSize>`
     * and returns the raw command result. The caller parses the session id from
     * stdout.
     *
     * @param replace pass `-r` to update an existing app.
     * @param downgrade pass `-d` to allow installing an older version code.
     */
    Bundle createInstallSession(String installerPackage, long totalSize, boolean replace, boolean downgrade) = 2;

    /**
     * Runs `pm install-write -S <size> <sessionId> <splitName> -`, streaming the
     * APK bytes from [source] into the command's stdin. The descriptor is
     * consumed (and closed) by the service.
     */
    Bundle writeApk(int sessionId, String splitName, long size, in ParcelFileDescriptor source) = 3;

    /** Runs `pm install-commit <sessionId>`. */
    Bundle commitInstallSession(int sessionId) = 4;

    /** Runs `pm install-abandon <sessionId>`. */
    Bundle abandonInstallSession(int sessionId) = 5;
}
