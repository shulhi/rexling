# Building Rexling

Run commands below from the `rexling/` repository root unless noted otherwise.
Android builds use macOS or x86_64 Linux (the Linux NDK toolchain is x86_64).
The verified local environment is Apple Silicon macOS with Android Studio's JDK 25. The APK targets
arm64-v8a Android 9/API 28 or newer; other Android ABIs are not built yet.

## Prerequisites

- Rust 1.93+ and rustup, with Cargo available on PATH or under `~/.cargo/bin`.
- Go with automatic toolchain downloads enabled (the embedded network module pins Go 1.27.1).
- Python 3.9+ and curl for the native build helper.
- Android Studio, or an Android SDK plus JDK 25.
- SDK platform `platforms;android-37`, Build Tools `build-tools;36.0.0`, NDK
  `ndk;30.0.16248370`, and `platform-tools` for ADB.

Install SDK components through Android Studio's SDK Manager or its command-line
tools, accepting licenses interactively:

```sh
sdkmanager 'platforms;android-37' 'build-tools;36.0.0' \
  'ndk;30.0.16248370' 'platform-tools'
sdkmanager --licenses
rustup target add aarch64-linux-android
```

Set `ANDROID_HOME` to your SDK if it is outside `~/Library/Android/sdk` on macOS.
On Linux set `ANDROID_HOME` and `JAVA_HOME` explicitly. The native helper also
accepts `ANDROID_NDK_HOME`. SDK paths in `rexling-android/local.properties` are
machine-local and ignored by Git. No toolchain or SDK installation is committed.

The wrapper selects Android Studio's bundled JDK on macOS and changes into the
Android project. To select another JDK on macOS, run `./gradlew` directly from
`rexling-android/` with the desired `JAVA_HOME`.

## Android APK

```sh
./rexling-android/scripts/gradle.sh :app:assembleDebug
./rexling-android/scripts/gradle.sh :app:assembleRelease
```

Open `rexling-android/` in Android Studio. Gradle runs the native build helper
automatically. libghostty-vt is pinned to release 1.0.2. Ghostty revision, source checksums, Zig downloads and NDK version
are pinned in `rexling-core/native/ghostty-source.json`. Rust dependencies share
`rexling-core/Cargo.lock`. SSH and built-in Tailscale dependencies are pinned in
`rexling-network/go.mod` and `go.sum`; the helper builds their Android shared library with the NDK. The AGP, Kotlin/Compose, Gradle and AndroidX versions
remain pinned by the Android project.

Release output: `rexling-android/app/build/outputs/apk/release/app-release.apk`.
Debug output: `rexling-android/app/build/outputs/apk/debug/app-debug.apk`.

For a shareable release with its build identity in the filename:

```sh
./rexling-android/scripts/gradle.sh :app:shareRelease
```

This builds the release and copies it to
`rexling-android/dist/Rexling-<date>-build<code>-<commit>-arm64-release.apk`,
with a matching `.apk.sha256` file. Version, build code and commit match About.
Tracked local edits add `-modified` after the commit; source archives use `unknown`.
The standard Gradle output remains available at `app-release.apk`.

The visible app version uses the build's UTC date (`YYYY.MM.DD`). **⋮ → Preferences → About
Rexling** shows that version, Android's numeric build code, build type and Git
revision, with **Copy build info** for bug reports. Tracked local edits are marked
as local changes; source archives without Git show `unknown` for the revision.
Increase `versionCode` in `rexling-android/app/build.gradle.kts` for each distributed
update, including multiple releases on the same day. For reproducible version
dates, set `SOURCE_DATE_EPOCH` to the release's Unix timestamp before building.

Release uses R8, resource shrinking and packaged ART profiles. It currently signs
with the existing local development key so it can update this prototype without
clearing data. For Play Store or long-term public distribution, configure a
dedicated signing key in the Android project; keep keys and passwords outside Git.
The package and JNI ABI were intentionally retained during the repository move.

Connect a USB-debugging-authorized phone, or launch an arm64 emulator:

```sh
adb devices -l
adb -s <serial> install -r rexling-android/app/build/outputs/apk/release/app-release.apk
```

Keep Tailscale active on the device when accessing a private tailnet Rex server.
Building the APK does not contact or change a Rex server.

## Rust probe without Android or Ghostty

```sh
cargo build --locked --manifest-path rexling-core/Cargo.toml -p rex-probe
rexling-core/target/debug/rex-probe --help
rexling-core/target/debug/rex-probe -S https://your-rex-host sessions
```

The probe's optional `ghostty` feature enables `rex-view`. Build the host Ghostty
library first with the helper below, then use `--features rex-probe/ghostty` for
workspace commands. Protocol-probe usage and evidence are in
`rexling-core/probe/README.md` and `rexling-core/probe/PROTOCOL.md`.

## Checks without a phone

```sh
# Host Ghostty, all Rust workspace tests/features, and actual JNI/JVM smoke test:
python3 rexling-android/scripts/test_jni.py

GHOSTTY_PREFIX="$PWD/rexling-core/.build/ghostty-host" \
  CARGO_TARGET_DIR="$PWD/rexling-core/.build/rust" \
  cargo clippy --locked --manifest-path rexling-core/Cargo.toml \
    --workspace --all-targets --all-features -- -D warnings

./rexling-android/scripts/gradle.sh :app:testDebugUnitTest :app:lintRelease
python3 rexling-android/scripts/verify_apk.py \
  --apk app/build/outputs/apk/release/app-release.apk --require-release
```

The APK verifier interprets its `--apk` path relative to `rexling-android/`.
Build caches and native outputs stay under `rexling-core/.build/`, Rust's default
`rexling-core/target/`, and the Android project's ignored Gradle/build directories.

Device UI checks (use an emulator or a dedicated test device):

```sh
ANDROID_SERIAL=<serial> ./rexling-android/scripts/gradle.sh :app:connectedDebugAndroidTest
```

The test runner may replace/uninstall its target app and data. Some multi-server
tests require explicit owned-server fixture arguments; see
`rexling-android/TESTING.md`. Live Rust tests are opt-in, create disposable
sessions, and remove only those fixtures:

```sh
REX_TEST_SERVER=https://your-owned-rex-host \
  GHOSTTY_PREFIX="$PWD/rexling-core/.build/ghostty-host" \
  CARGO_TARGET_DIR="$PWD/rexling-core/.build/rust" \
  cargo test --locked --manifest-path rexling-core/Cargo.toml \
    -p rexling-jni live_activity_ -- --ignored
```

Historical validation documents retain commands and paths as they were when
recorded. Use this guide for the current monorepo paths. No server configuration
or authentication change is required by the migration.


## Reproduce the Activity attention badge check

Build the probe and debug/test APKs, then explicitly select a server you own:

```sh
cargo build --locked --manifest-path rexling-core/Cargo.toml -p rex-probe
./rexling-android/scripts/gradle.sh :app:assembleDebug :app:assembleDebugAndroidTest
python3 rexling-android/scripts/test_activity_badge.py \
  --server https://your-owned-rex-host --serial emulator-5554
```

The runner creates/removes one disposable session and rejects non-emulator
serials. It verifies an actual blocked report travels through native decoding,
RexModel and the eye's visible blue pixels, then disappears after Mark seen.
The screenshot is written to ignored `rexling-android/.build/activity-attention.png`.


## Program icon artwork

Program tiles use retained catalog SVG geometry compiled to native Canvas vector
commands. To update them after changing the SVG sources:

```sh
python3 rexling-android/scripts/build_program_vectors.py
```

Commit `app/src/main/assets/program_tiles.json` under `rexling-android/` with the
source changes. The converter supports the catalog's paths, gradients, transforms
and monochrome alpha masks; unsupported SVG features fail generation. Original
PNG renders are retained for visual regression comparisons. No browser or network
is needed to regenerate the native commands.

## Native upload checks

Native image uploads use the Rex server directly (API revision 11+). No separate
upload service or checkout is required. The Rust loopback protocol tests run with:

```sh
cargo test --manifest-path rexling-core/Cargo.toml --test uploads
```

With debug and Android test APKs installed on an arm64 emulator, run:

```sh
adb -s emulator-5554 shell am instrument -w -e class \
  dev.shulhi.rex.NativeImageUploadTest,dev.shulhi.rex.TerminalImageInputTest,dev.shulhi.rex.ClipboardImagePasteTest \
  dev.shulhi.rex.test/androidx.test.runner.AndroidJUnitRunner
```

These use isolated loopback peers and test image content, progress/confirmation,
cancellation, unsupported servers, and keyboard/clipboard behavior.
