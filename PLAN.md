# APKM Installer with Installer-Flag Spoofing — Development Plan

An Android app that installs `.apkm` files (APKMirror bundles) and spoofs the
installer package to `com.android.vending` (Play Store), emulating
`adb install-multiple -i com.android.vending`.

---

## What we verified in the repo

- The `.apkm` is a **plain ZIP** (a signed JAR). Contents of the extracted folder:
  - `base.apk` (47 MB), `split_config.arm64_v8a.apk` (193 MB), `split_bundledtree.apk` (575 MB)
  - `info.json` → `pname=jp.pokemon.pokemontcgp`, `versioncode=399771`, `min_api=24`, `arches=[arm64-v8a]`, `apkm_version=5`
  - `icon.png`, `APKM_installer.url`, and `META-INF/` (`APKMIRRO.RSA`, `APKMIRRO.SF`, `MANIFEST.MF`)
- So an `.apkm` is exactly what you suspected: a ZIP of a base APK + split APKs + metadata.
  No decryption needed — you can read it with `java.util.zip` directly.

## Key technical findings (these shape the plan)

1. **`adb install-multiple -i com.android.vending` is really `pm install-create -i com.android.vending` + `pm install-write` (per APK) + `pm install-commit`.** That is the exact sequence to emulate.
2. **Your app cannot set the installer to `com.android.vending` by itself** — that requires `INSTALL_PACKAGES`, which only shell/system holds. **Shizuku runs your commands as the `shell` user (uid 2000)**, which *does* have it. So the install must be routed through Shizuku.
3. **Simplest robust approach: run `pm` through Shizuku** (via a Shizuku **UserService**), streaming APK bytes to `pm install-write`'s stdin. This avoids file-permission problems (shell can't read your app's private storage) and avoids loading 800 MB into RAM. (The older `Shizuku.newProcess(...)` is deprecated and slated for removal in API 14, so we use `UserService` from the start.)
4. **Android 14+ caveat:** shell can still set the **installer package** field (`-i com.android.vending` → what `getInstallSourceInfo().getInstallingPackageName()` returns), but it can **no longer set the "originating" package** field. Your goal (installer = Play Store) still works on 14+; just don't promise the originating field.
5. **Split names matter.** `pm install-write <session> <name> <path>` — use the split name from the APK. APKMirror names files after their split names, so `base.apk` → `base`, `split_config.arm64_v8a.apk` → `split_config.arm64_v8a`, etc. (Phase 1 should confirm this by reading each APK's manifest split name.)
6. **Wireless ADB:** Shizuku itself handles the wireless-debugging pairing/start flow (Android 11+). Your app only needs to detect that Shizuku is running and request permission — you don't reimplement pairing.

## Recommended stack

- Kotlin + Jetpack Compose, single module to start.
- `dev.rikka.shizuku:api` + `dev.rikka.shizuku:provider` (Shizuku API).
- `java.util.zip` for APKM parsing (no third-party needed).
- SAF (`ACTION_OPEN_DOCUMENT`) to pick the `.apkm`; stream via `ContentResolver.openInputStream`.
- A **foreground service** for the install (installs can take minutes for ~800 MB).
- `minSdk 26`, `targetSdk 35` (Shizuku works from API 23; wireless-debugging start needs API 30, but ADB start works lower).

---

## Phased plan

### Phase 0 — Scaffolding & ground truth  ✅ DONE

**Goal:** empty app that builds and runs; a repeatable way to test.

- Create the Android project (Compose, Kotlin, minSdk 26).
- Add a debug screen that just shows device info (Android version, ABI, free storage).
- **Test:** app installs and launches on your device; note your Android version (this determines the Android 14 caveat).

### Phase 1 — APKM parser (no install yet)  ✅ DONE

**Goal:** read the `.apkm` and produce an install plan.

- Pick the file via SAF; open as a ZIP stream.
- Parse `info.json` → package name, version code, min API, arches.
- Enumerate `*.apk` entries; classify `base.apk` vs splits.
- For each APK, read its split name (parse `AndroidManifest.xml` binary XML, or fall back to filename-minus-`.apk`).
- Compute total size; validate ABI against the device; warn on `min_api` mismatch.
- **Test:** point it at the provided Pokémon file and assert it lists exactly `base`, `split_config.arm64_v8a`, `split_bundledtree` with correct sizes and `pname=jp.pokemon.pokemontcgp`. Also test a small single-APK `.apkm`/`.apk` for the trivial case.

### Phase 2 — Shizuku integration  ✅ DONE

**Goal:** prove you can execute shell commands as `shell`.

- Add Shizuku deps; request permission (`Shizuku.requestPermission`).
- Detect service state (`Shizuku.checkSelfPermission`, `Shizuku.pingBinder`).
- Run a harmless command through a Shizuku **UserService** and display the output.
- **Test:** output shows `uid=2000(shell)`. Verify the "Shizuku not running" and
  "permission denied" paths show clear guidance.

> **Implementation note (future-proofing):** `Shizuku.newProcess(...)` is
> deprecated and slated for removal in Shizuku API 14. We therefore use the
> modern **UserService** approach from the start: `IUserService.aidl` defines an
> `exec(String)` method, `UserService` runs it in a separate process with
> shell/root identity, and `UserServiceClient` binds to it. This is the same
> mechanism Phase 3 will use to stream APKs to `pm install-write`.

### Phase 3 — Install engine (the core)  ✅ DONE

**Goal:** install a multi-APK set with `-i com.android.vending`.

- Implement the sequence via Shizuku:
  1. `pm install-create -i com.android.vending -r -S <totalBytes>` → parse session id from output.
  2. For each APK: `pm install-write -S <size> <sessionId> <splitName> -`, then stream the APK bytes to the process stdin.
  3. `pm install-commit <sessionId>`.
- On any failure: `pm install-abandon <sessionId>`.
- Parse `Success`/`Failure [reason]` from stdout/stderr and surface it.
- **Test order:** (a) a tiny single APK, (b) a small split bundle, (c) the full Pokémon `.apkm`. After each, verify with `pm list packages` and `dumpsys package <pkg> | grep installerPackageName` (or `getInstallSourceInfo`) that the installer is `com.android.vending`.

> **Implementation note:** the install sequence runs inside the Shizuku
> **UserService** (`IUserService.createInstallSession` / `writeApk` /
> `commitInstallSession` / `abandonInstallSession`). APK bytes are streamed from
> the `.apkm` `ZipSource` through a `ParcelFileDescriptor` pipe into
> `pm install-write`'s stdin, so the bundle is never loaded into memory and the
> shell user never needs to read the app's private storage. `InstallEngine`
> drives the sequence and reports per-APK/overall progress; `PmOutput` parses the
> `Success`/`Failure [reason]` text (unit-tested). The UI adds an install card
> that shows progress and reads the installer package back via `dumpsys`.

### Phase 4 — UI/UX  ✅ DONE

**Goal:** usable end-to-end flow.

- Screens: pick file → review parsed metadata → install with progress → result.
- Progress: per-APK and overall (bytes streamed / total).
- Foreground service + notification so the install survives backgrounding.
- Clear error states: Shizuku missing, permission denied, signature mismatch, `INSTALL_FAILED_VERSION_DOWNGRADE`, insufficient storage, ABI mismatch.
- **Test:** full run from the UI on the real file; kill/background the app mid-install and confirm it completes.

> **Implementation note:** the install is owned by a process-wide `InstallManager`
> (not the ViewModel), so it survives UI recreation. `InstallService` is a
> foreground service (`dataSync`) that keeps the process alive and mirrors
> progress into an ongoing notification; it stops itself on success/failure.
> `InstallErrors` maps raw `pm` reasons to actionable messages, and the UI runs
> pre-flight checks (free storage, ABI, min-API) that block the install button
> when they fail. The install card shows a real progress bar and a Done/Dismiss
> action to reset the state.

### Phase 5 — Verification, edge cases & hardening  ✅ DONE

**Goal:** trust the result.

- Confirm installer package via `PackageManager.getInstallSourceInfo` in-app after install.
- Handle: re-install/update (`-r`), downgrade (`-d`, optional), existing different-signature app, session cleanup on crash, very large files (streaming, no OOM), storage pre-check.
- Document the Android 14+ originating-package limitation in the UI/README.
- **Test:** matrix across at least one Android ≤13 and one ≥14 device; verify installer field on both.

> **Implementation note:** the installer package is now read back **in-app** via
> `InstallerVerifier` (`PackageManager.getInstallSourceInfo`, with a
> `getInstallerPackageName` fallback below API 30) instead of shelling out to
> `dumpsys`. The UI warns about installed-version mismatches and offers an
> opt-in **downgrade** (`-d`) toggle; the pure comparison lives in `Preflight`
> and is unit-tested. `InstallSessionStore` persists the in-flight session id so
> `InstallManager.cleanupStaleSession` can `pm install-abandon` a session left
> dangling by a crash. The Android 14+ originating-package limitation is shown
> in the success card and documented in the README. Streaming (no OOM) and the
> storage pre-check were already in place from Phases 3–4.

### Phase 6 — Optional enhancements

- Support `.xapk` (APKPure) and `.apks` (bundletool) — same ZIP+`pm` pipeline, different metadata file.
- Batch installs, install history, "uninstall then install" helper, share-sheet target for `.apkm` files.

---

## Risks / things to watch

- **Android 14+ originating package** can't be spoofed via shell — only the installer package. Set expectations accordingly.
- **Split-name correctness** is the most likely cause of `INSTALL_FAILED_INVALID_APK`; Phase 1's manifest parsing de-risks this.
- **Streaming vs. temp files:** prefer stdin streaming; if you must use temp files, write to `/data/local/tmp` via Shizuku (shell-readable), not app-private storage.
- **Shizuku availability:** it must be started (wireless debugging or ADB) before installs; the app should guide, not assume.
- **Large-file memory:** never `readBytes()` an 800 MB bundle — always stream.
