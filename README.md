# PKMN Installer

An Android app that installs `.apkm` bundles (APKMirror) and spoofs the installer
package to `com.android.vending` (Play Store), emulating
`adb install-multiple -i com.android.vending`.

See [PLAN.md](PLAN.md) for the full phased development plan.

## Status

- **Phase 0 — Scaffolding:** done. App builds and shows a device-info screen.
- **Phase 1 — APKM parser:** done. Parses `info.json` and each APK's split name
  from its binary `AndroidManifest.xml`; covered by unit tests.
- **Phase 2 — Shizuku integration:** done. Detects Shizuku state, requests
  permission, and runs shell commands as `shell` through a Shizuku **UserService**
  (the future-proof replacement for the deprecated `Shizuku.newProcess`). The UI
  also lets you pick an `.apkm` and shows the parsed metadata.
- **Phase 3 — Install engine:** done. Installs a parsed `.apkm` as a multi-APK
  session (`pm install-create` / `install-write` / `install-commit`) through the
  Shizuku UserService, spoofing the installer package to `com.android.vending`.
  APK bytes are streamed from the bundle into `pm install-write`'s stdin via a
  pipe (no full-file buffering). The UI shows progress and reads the installer
  package back to confirm the spoof.
- **Phase 4 — UI/UX:** done. The install is owned by a process-wide
  `InstallManager` and kept alive by a foreground `InstallService` (with a
  progress notification), so it survives backgrounding and UI recreation. The UI
  shows a real progress bar, pre-flight checks (storage / ABI / min-API) that
  block the install button, and friendly error messages for common `pm`
  failures.
- **Phase 5 — Verification & hardening:** done. The installer package is read
  back **in-app** via `PackageManager.getInstallSourceInfo` (not `dumpsys`), the
  UI warns about installed-version mismatches and offers an opt-in downgrade
  (`-d`), and a stale `pm` session left by a crash is abandoned on next launch.
  The Android 14+ originating-package limitation is documented in the UI.
- Phase 6: not started.

## Theming

The UI uses a Pokémon-themed Material 3 colour scheme (`ui/theme/`) that follows
the **system light/dark setting** (`isSystemInDarkTheme()`), with matching
`values-night` resources for the window background and status bar. Technical
details (uid, split names, installer read-back, device info) are tucked behind a
collapsible "Show technical details" section so the default view stays
user-friendly.

## Android 14+ caveat

The shell user can set the **installer package** (`-i com.android.vending`, what
`getInstallSourceInfo().getInstallingPackageName()` returns) on all supported
versions. On **Android 14+** it can no longer set the **originating package**
(`getOriginatingPackageName()`), so that field stays `null` there even when the
spoof worked. The app only promises the installer field.

## Build environment

Google does not publish Linux/arm64 Android SDK build tools, so the build runs
inside a **linux/amd64** container (emulated on Apple Silicon). The image is
based on `thyrlian/android-sdk` and adds `platforms;android-35` and
`build-tools;35.0.0`.

### Prerequisites

- `podman` with a running machine (recommend ≥ 8 GB RAM for Gradle).
- No local JDK or Android SDK needed — everything is in the container.

### Build & test

Use the wrapper script, which builds the image on first use and runs Gradle
inside the container:

```bash
./gradlew.sh testDebugUnitTest   # run unit tests
./gradlew.sh assembleDebug       # build the debug APK
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

To rebuild the image after changing the `Dockerfile`:

```bash
podman build --platform linux/amd64 -t pkmn-installer-android .
```

### Install on a device

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Release build

The `release` build type enables R8 code shrinking (`isMinifyEnabled`) and
resource shrinking (`isShrinkResources`), and is signed with a keystore you
provide. Signing is read from a **git-ignored** `keystore.properties` at the
project root, so no secrets live in version control. Without that file the
release build still compiles, but produces an unsigned APK.

1. Create a keystore (once, and keep it safe — losing it means you can never
   update the app under the same identity). Since there is no local JDK, run
   `keytool` inside the build container.

   To keep the password **out of the command line** (and out of your shell
   history), put it in a temporary file and point `keytool` at it with the
   `-storepass:file` / `-keypass:file` modifiers. A plain `podman run` does not
   attach stdin, so the password must be supplied this way (or via `-it` to be
   prompted):

   ```bash
   # Write the password to a temp file (no trailing newline), then delete it.
   # Keep it under $HOME: podman's VM only shares your home directory, so a
   # path like /tmp/keystore.pw is not visible to the container and mounting it
   # fails with "statfs /tmp/keystore.pw: no such file or directory".
   printf '%s' 'your-password' > "$HOME/keystore.pw"

   podman run --rm --platform linux/amd64 -v "$PWD:/workspace" -w /workspace \
     -v "$HOME/keystore.pw:/keystore.pw:ro" \
     pkmn-installer-android \
     keytool -genkeypair -v \
       -keystore release.keystore \
       -alias pkmn \
       -keyalg RSA -keysize 2048 -validity 10000 \
       -storepass:file /keystore.pw -keypass:file /keystore.pw \
       -dname "CN=PKMN Installer, O=PKMN, C=BE"

   rm -f "$HOME/keystore.pw"
   ```

   Use the same password in `keystore.properties` below. If you prefer to be
   prompted interactively instead, add `-it` to the `podman run` flags so stdin
   is attached and drop the `-storepass`/`-keypass` options.

2. Create `keystore.properties` at the project root (git-ignored):

   ```properties
   storeFile=release.keystore
   storePassword=…
   keyAlias=pkmn
   keyPassword=…
   ```

3. Build and install:

   ```bash
   ./gradlew.sh assembleRelease
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```

Notes:

- A release APK signed with a different key than the debug build cannot be
  installed over it (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`); uninstall the debug
  app first.
- R8 keeps the Shizuku user-service classes via `app/proguard-rules.pro`. Re-test
  the Shizuku bind and the install flow after changing those rules.
- Bump `versionCode`/`versionName` in `app/build.gradle.kts` for each release.

## Project layout

```
app/src/main/java/com/bennod/pkmninstaller/
  MainActivity.kt              # Compose UI (device info, Shizuku, APKM, install)
  MainViewModel.kt             # Shizuku state + APKM parsing + install state
  apkm/
    ApkmModels.kt              # data model (ApkmInfo, ApkEntry, ApkmBundle)
    ApkmParser.kt              # reads info.json + enumerates APKs
    BinaryXml.kt               # minimal binary AndroidManifest.xml parser
    ZipSource.kt               # ZIP abstraction (file / seekable channel)
    ChannelZipSource.kt        # ZIP reader over a SAF file descriptor
  install/
    InstallEngine.kt           # pm install-create/write/commit sequence
    InstallManager.kt          # process-wide install owner (survives UI)
    InstallService.kt          # foreground service + progress notification
    InstallState.kt            # install state model
    InstallErrors.kt           # maps pm failures to friendly messages
    InstallerVerifier.kt       # reads install source via getInstallSourceInfo
    InstallSessionStore.kt     # persists the session id for crash cleanup
    Preflight.kt               # pure pre-flight checks (version/downgrade)
    PmOutput.kt                # parses Success/Failure output (unit-tested)
  shizuku/
    ShizukuManager.kt          # Shizuku state + permission handling
    UserService.kt             # runs commands + install steps as shell/root
    UserServiceClient.kt       # binds to the user service
    ShellRunner.kt             # thin exec wrapper over the user service
  util/
    Bytes.kt                   # human-readable byte formatting
  ui/
    PokeBall.kt                # Compose-drawn Pokéball
    theme/                     # Pokémon Material 3 theme (light + dark)
app/src/main/aidl/...          # IUserService.aidl (user-service contract)
app/src/test/...               # unit tests + binary-manifest fixtures
```
