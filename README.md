# PKMN Installer

An Android app that installs Pokémon TCG Pocket on phones where the Play Store
won't let you, and makes the install look like it came from the Play Store.

> [!NOTE]
> While the technical solution was hand-designed, 100% of the code was vibecoded. Use at your own risk. The app is open source, so you can read the code and see what it does.


## Why this exists

Pokémon TCG Pocket is only officially available in a handful of countries.
Players elsewhere have been playing it anyway by downloading the game from
APKMirror and installing it by hand.

That stopped working when the game added a Play Store integrity check. The game now looks
at which app installed it, and if the answer isn't the Play Store, it refuses to
start. Players in unsupported regions were locked out, even though the game
itself runs perfectly well on their phones.

This app is a workaround for that. You download the game's `.apkm` file from
APKMirror yourself, pick it in the app, and the app installs it while telling
Android that the Play Store was the one doing the installing. The game sees what
it expects and starts normally.

It is a workaround, not a fix. It doesn't change the game or your account, and
it doesn't make the game officially available in your region. It only makes the
install look like it came from the Play Store.

In order to do this, the app needs [Shizuku](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api&hl=en). Shizuku is an app which allows other apps to run ADB debug commands right on your phone. It has strong security guardrails, and you have to explicitly grant permission for each app that needs to use it.

## What it does

- Reads the `.apkm` file you picked and works out which pieces it contains.
- Checks your phone first: enough free space, a supported processor, a recent
  enough version of Android.
- Installs the game in the background, and keeps going if
  you leave the app.
- Records the Play Store as the installer, which is what the game's check looks
  at.
- Reads that record back afterwards to confirm it worked.
- Warns you if a different version of the game is already installed, and lets
  you install an older one on purpose if you want to.
- Turns confusing system errors into plain sentences.

## What you need

- An Android phone running Android 8.0 or newer.
- [Shizuku](https://shizuku.rikka.app/) installed and running. Shizuku is a free
  app that lets other apps use adb debug commands directly from your phone.
- The game's `.apkm` file, downloaded from APKMirror.

## A caveat on Android 14 and newer

On Android 14 and newer, Android no longer lets adb record the
"original" source of an app, only the installer. The app only rewrites the
installer field, which is the one the game seems to check.

## Building it yourself

Google does not publish Linux/arm64 Android SDK build tools, so the build runs
inside a linux/amd64 container (emulated on Apple Silicon). The image is
based on `thyrlian/android-sdk` and adds `platforms;android-35` and
`build-tools;35.0.0`.

### Prerequisites

- `podman` with a running machine (8 GB RAM or more recommended for Gradle). You can probably also use docker, if you alias it to `podman` and make some minor changes.
- No local JDK or Android SDK needed; everything is in the container.

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
provide. Signing is configured in `keystore.properties` at the project root,
which is tracked in git and contains no secrets: any value written as `${NAME}`
is replaced with the environment variable `NAME` at build time. Without a
complete set of values the release build still compiles, but produces an
unsigned APK.

1. Create a keystore (once, and keep it safe; losing it means you can never
   update the app under the same identity). Since there is no local JDK, run
   `keytool` inside the build container.

   To keep the password out of the command line (and out of your shell
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

   Use the same password in the next step. If you prefer to be prompted
   interactively instead, add `-it` to the `podman run` flags so stdin is
   attached and drop the `-storepass`/`-keypass` options.

2. Provide the password. The tracked `keystore.properties` already points at
   `release.keystore` with alias `pkmn` and reads the password from the
   environment:

   ```properties
   storeFile=release.keystore
   storePassword=${RELEASE_KEYSTORE_PASSWORD}
   keyAlias=pkmn
   keyPassword=${RELEASE_KEYSTORE_PASSWORD}
   ```

   Either export that one variable, or create a git-ignored
   `keystore.properties.local` next to it with the real value. Anything in the
   local file overrides the tracked one, so you can keep the password out of
   your shell history:

   ```properties
   storePassword=...
   keyPassword=...
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

### Release via GitHub Actions

Pushing a version tag (e.g. `v0.1.0`) triggers
[`.github/workflows/release.yml`](.github/workflows/release.yml), which runs the
unit tests, builds a signed release APK, uploads it as a workflow artifact, and
attaches it to a GitHub release as `pkmn-installer.apk`. No keystore or password
is stored in the repository; the tracked `keystore.properties` reads the
password from the environment, and the workflow fills it in from a repository
secret.

Configure these repository secrets (Settings → Secrets and variables →
Actions):

| Secret | Description |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | The `release.keystore` file, base64-encoded |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore password (also used as the key password) |

Encode the keystore (macOS):

```bash
base64 -i release.keystore | pbcopy   # paste into RELEASE_KEYSTORE_BASE64
```

Then cut a release:

```bash
git tag v0.1.0
git push origin v0.1.0
```