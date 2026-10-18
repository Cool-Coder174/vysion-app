# Setup: fresh clone → running app on the headset (target ≤ 30 min)

Owner: R5. This is the NFR "Reproducibility" row (SRS §5): *fresh clone to running app on the headset using README only, with pinned ExecuTorch and Jetpack XR versions and a model download script, ≤ 30 min*. A teammate who did not write this page follows it on a clean machine and times it (submission checklist, [`demo.md`](demo.md)). Every version below is the one in [`gradle/libs.versions.toml`](../gradle/libs.versions.toml); the reason for each pin and its source URL are in [`stack.md`](stack.md) §1. If this page and the TOML disagree, the TOML wins and this page has a bug.

No Android Studio is needed for any step. The Android XR emulator (optional, §9) is the only thing that does.

## 1. Prerequisites

| Need | Exact | Why |
|---|---|---|
| JDK | **17** (Temurin or Oracle; `java -version` prints `17.x`) | AGP 9.4.1 needs ≥ 17; Gradle 9.6.1 supports 17–26 (stack.md §1) |
| Android SDK packages | `platform-tools`, `platforms;android-37.0`, `build-tools;36.0.0` | compileSdk 37; AGP 9.4.1 requires Build-Tools 36.0.0; `adb` ships in platform-tools (the same three packages CI installs, `.github/workflows/ci.yml`) |
| Command-line tools | `cmdline-tools;latest` (gives `sdkmanager`) | to install the packages above without Android Studio |
| Python 3 | any `python3` | `scripts/fetch_models.sh`, `scripts/push_models.sh` and `scripts/benchmark.sh` parse JSON with it |
| Git | any | clone |
| ffmpeg (R4 only, once) | any build with `libvorbis` (`ffmpeg -encoders \| grep libvorbis`; checked with 7.1.1) | renders `app/src/main/assets/sounds/ping.ogg`, the one audio asset (command in `docs/modules/r4-voice-accessibility.md`, `Speaker` P0 item); nobody else needs it |
| Disk | several GB free | Gradle caches, the ExecuTorch AAR with native libraries, and the model files (A-x sizes are measured, not estimated here) |

Build tool versions you do **not** install: Gradle **9.6.1** comes from the wrapper (`gradle/wrapper/gradle-wrapper.properties`), AGP **9.4.1**, Kotlin **2.4.21** and the Compose compiler plugin come from Maven on the first build.

### macOS

```bash
# JDK 17 (pick one): Homebrew cask, or the installer from https://adoptium.net/temurin/releases/?version=17
brew install --cask temurin@17
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"

# Android command-line tools → ~/Library/Android/sdk (the path the scripts look for)
export ANDROID_HOME="$HOME/Library/Android/sdk"
mkdir -p "$ANDROID_HOME/cmdline-tools"
# download "Command line tools only" for Mac from https://developer.android.com/studio#command-line-tools-only, then:
unzip -q ~/Downloads/commandlinetools-mac-*_latest.zip -d "$ANDROID_HOME/cmdline-tools"
mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

yes | sdkmanager --licenses
sdkmanager --install "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
```

### Linux (Debian/Ubuntu shown)

```bash
sudo apt-get install -y openjdk-17-jdk unzip python3 git
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

export ANDROID_HOME="$HOME/Android/Sdk"
mkdir -p "$ANDROID_HOME/cmdline-tools"
# download "Command line tools only" for Linux from https://developer.android.com/studio#command-line-tools-only, then:
unzip -q ~/Downloads/commandlinetools-linux-*_latest.zip -d "$ANDROID_HOME/cmdline-tools"
mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

yes | sdkmanager --licenses
sdkmanager --install "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
```

Linux only: for USB access to the headset without `sudo`, add a udev rule for Samsung's vendor id (`04e8`) or run `adb` once as root, then `adb kill-server`.

## 2. Clone and point Gradle at the SDK

```bash
git clone https://github.com/Cool-Coder174/vysion-app.git vysion-xr && cd vysion-xr
echo "sdk.dir=$ANDROID_HOME" > local.properties      # macOS default: /Users/<you>/Library/Android/sdk · Linux: /home/<you>/Android/Sdk
```

`local.properties` is gitignored. Setting `ANDROID_HOME` in the environment also works; `sdk.dir` wins when both exist.

## 3. First build: `./gradlew assembleDebug testDebugUnitTest`

```bash
./gradlew assembleDebug testDebugUnitTest
```

What the first run does, in order, so nothing looks wrong:

1. The wrapper downloads the Gradle **9.6.1** distribution (`distributionUrl` in `gradle/wrapper/gradle-wrapper.properties`).
2. Gradle resolves the plugins: `com.android.application` **9.4.1** and `org.jetbrains.kotlin.plugin.compose` **2.4.21** (which lifts the built-in Kotlin to **2.4.21**; nothing applies `org.jetbrains.kotlin.android`, stack.md D10).
3. It resolves the dependencies from exactly two repositories (`settings.gradle.kts`):
   - `google()`: Compose BOM **2026.09.00**, `activity-compose` **1.13.0**, `lifecycle-*` **2.11.0**, `core-ktx` **1.19.1**, CameraX **1.6.2**, and the Jetpack XR pre-release artifacts `androidx.xr.compose:compose:1.0.0-beta01`, `androidx.xr.scenecore:scenecore:1.0.0-rc02`, `androidx.xr.arcore:arcore:1.0.0-rc02`, `androidx.xr.runtime:runtime:1.0.0-rc02`.
   - `mavenCentral()`: `org.pytorch:executorch-android:1.3.1` (the XNNPACK flavor with the native libraries and `LlmModule`) and `kotlinx-coroutines-android` **1.11.0**.
4. It compiles, runs the JVM unit tests (`app/src/test`), and writes `app/build/outputs/apk/debug/app-debug.apk`.

Expect the first run to take several minutes on a fast connection; later runs are incremental and use the configuration cache (`gradle.properties`). A-10 / A-14 in [`assumptions.md`](assumptions.md) is exactly this step on a fresh machine with the exact pins: record the result there (Sat 10:30). If it is red, stack.md §5 Q1 names the two one-line bumps to try.

Then run the privacy gate once, it needs the merged manifest the build just produced:

```bash
scripts/check_privacy.sh
```

## 4. Enable developer mode and ADB on the devices

Menu labels come from [`stack.md`](stack.md) §4 (Samsung has no developer page for the headset; the labels are from a partner enrollment guide, medium confidence; fix them here if the device differs).

### Galaxy XR (primary, Android 14 / API 34)

1. Settings → About Device → tap **Build Number** seven times → enter the PIN.
2. Settings → System → **Developer Options** → **USB Debugging** on.
3. Plug a **data** USB-C cable into the **headset's** side port (under a cover; it is data-only and does not charge; the battery pack charges separately and can charge while in use).
4. Put the headset on, accept **Allow USB debugging** for this computer.
5. On the laptop: `adb devices` shows the headset as `device`. Confirm Q2: `adb shell getprop ro.build.version.release ro.build.version.sdk ro.build.display.id` → expect `14`, `34`, and a build id `I610UEU2AZF3` or newer.

### Galaxy S25 Ultra (fallback, 2D UI)

1. Settings → About phone → Software information → tap **Build number** seven times.
2. Settings → Developer options → **USB debugging** on. Record the Android version (One UI 8 = Android 16 / API 36, One UI 9 = Android 17 / API 37; both run the app, `minSdk` is 34).

### ADB over Wi-Fi (both devices, Android 11+ wireless debugging)

Use this when the headset is on someone's head and the cable is in the way. Pairing is per laptop; it survives until the device reboots or airplane mode cuts Wi-Fi (Q19).

```bash
# on the device: Developer options → Wireless debugging → on → "Pair device with pairing code"
adb pair <ip>:<pairing-port>          # type the 6-digit code the device shows
adb connect <ip>:<port>               # the port on the Wireless debugging screen itself (NOT the pairing port, NOT 5555)
adb devices                           # <ip>:<port>   device
```

The scripts take the same address: `scripts/install_headset.sh --wifi <ip>:<port>` (pass the port; without one the script appends `:5555`, which is the classic `adb tcpip 5555` route over USB first: `adb tcpip 5555 && adb connect <ip>:5555`).

## 5. Install and launch on the headset

```bash
scripts/install_headset.sh --logcat                 # builds, installs, launches, tails the vysion.* tags
scripts/install_headset.sh --wifi <ip>:<port>       # over Wi-Fi
scripts/install_headset.sh --serial <serial>        # when both devices are plugged in
scripts/install_headset.sh --no-build               # reinstall the last APK
```

The script prints the target model and API level and warns if airplane mode is off. First launch asks for `CAMERA`, `RECORD_AUDIO` and the four XR permissions with a spoken explanation (FR-G1); grant all of them, Navigation needs the `SCENE_UNDERSTANDING_*` pair and `HEAD_TRACKING`. R5 runs this after every merge (merge cadence, [`modules/r5-integration.md`](modules/r5-integration.md)).

## 6. Models: fetch (release) vs push (development), and why

The `.pte` files are hundreds of MB to a few GB and are never committed (ADR-0007). [`ml/models.lock`](../ml/models.lock) is the source of truth: name, destination filename, URL, sha256, size, export script. Two ways to get them onto the device, chosen in `plan.md` Q1:

| | `scripts/fetch_models.sh` | `scripts/push_models.sh` |
|---|---|---|
| Puts files in | `app/src/main/assets/models/` → **bundled into the APK** | `/sdcard/Android/data/com.vysion.xr/files/models/` on the device (`getExternalFilesDir(null)/models`) |
| Use for | the **release tag** build and the submission (a judge downloads one APK) | **development**: the APK stays ~tens of MB, so a Wi-Fi reinstall fits the 15-minute merge cadence |
| Reinstall cost | the whole model set every install (minutes over Wi-Fi) | zero: the files stay on the device across reinstalls |
| How the app finds them | copied from assets to `filesDir/models/` on first launch (A-11, the only write the app makes) | read in place with `LOAD_MODE_MMAP` (A-15; stack.md Q20) |
| Lookup order (R2 `ModelCatalog`) | second | **first** |

```bash
scripts/fetch_models.sh                     # downloads per lockfile; exits 2 while rows still say url: TBD (expected until R2 publishes)
scripts/fetch_models.sh --from ~/exports    # or copy artifacts you just exported with ml/export/*.sh
scripts/push_models.sh                      # sideload whatever is in assets/models (or --from <dir>) and size-verify on the device
```

**Precondition for either path: a lockfile the app can read.** `ModelCatalog` reads `assets/models/models.lock` (gitignored, so a fresh clone has none; `scripts/fetch_models.sh` copies it there before it exits 2 on `TBD` rows, then `./gradlew assembleDebug` bundles it) and falls back to `/sdcard/Android/data/com.vysion.xr/files/models/models.lock`, which `scripts/push_models.sh` pushes. With neither, logcat says `No models will resolve` and nothing loads.

Both scripts verify what they move: sha256 against the lockfile for fetch, byte size on the device for push (`--sha256` for a full hash). The app checks again before loading: a sideloaded file whose size or sha256 differs from a non-`TBD` lockfile row is refused and logged under `vysion.security` (fail closed, plan.md Security). Export recipes: `ml/README.md` (R2). Prebuilt artifacts are attached to the release tag ([`modules/r5-integration.md`](modules/r5-integration.md), release procedure).

## 6b. Optional: the Gemini key for cloud Describe (ADR-0010)

Without a key, Describe runs on the headset as before. The key lives only in your gitignored `local.properties` and is
restricted in Google Cloud to the Gemini API and to this app signed with your debug keystore, so it is useless outside
the app. Isaac's key for project `vysion-app` was created this way on 2026-10-18; make your own for your keystore:

```bash
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android | grep SHA1
gcloud services enable generativelanguage.googleapis.com --project vysion-app
gcloud services api-keys create --project vysion-app --display-name="Vysion XR (<you>, Android only)" \
  --allowed-application=sha1_fingerprint=<SHA1 from keytool>,package_name=com.vysion.xr \
  --api-target=service=generativelanguage.googleapis.com
gcloud services api-keys get-key-string <uid printed above> --project vysion-app --format="value(keyString)"
```

Put `GEMINI_API_KEY=<that string>` in `local.properties` and rebuild. The app sends `X-Android-Package` and
`X-Android-Cert` with every request (`AppGraph.signingCertSha1`); a request without them gets 403. Billing is off on
`vysion-app`: Gemini's free tier answers, but Maps grounding returns 429 until billing is enabled.

## 6c. See and record what the headset shows

`scripts/record_headset.sh` records, or live-previews, the headset's own picture: your passthrough view plus the app, one
square frame (checked on the Galaxy XR 2026-10-18: `screenrecord` gives 1920×1920 or the full 2880×2880 at about 36 fps).

```bash
scripts/record_headset.sh --live            # preview window on the Mac (scrcpy if installed, else ffplay); close it to stop
scripts/record_headset.sh                   # record until Ctrl-C; saves ~/Movies/Vysion/vysion-<time>.mp4 (1080×1080) + the raw file
scripts/record_headset.sh --size 2880x2880  # full resolution; --seconds 60 stops by itself; --test records 3 s and says if it is black
```

- **The headset must be on your head.** With the display off `screenrecord` fails with `INVALID_LAYER_STACK`, and an unworn
  capture is black; the script says so and checks the first frame.
- **No audio.** `screenrecord` records video only, so the app's spoken answers are missing. For a demo with sound use the
  headset's own recorder: Launcher (Top button) → Quick settings → **Screen record** → pick an audio option → Start; stop
  the same way; the file is in Gallery (pull it from `/sdcard/DCIM` or `/sdcard/Movies` with `adb pull`). Whether that
  recorder includes the passthrough view is not verified. Or record the Mac's audio from the headset's speaker on the side.
- **Bystanders appear in the picture.** Keep recordings out of the repo (`docs/demo.md`: do not commit the video).

## 7. Airplane mode: every benchmark and the demo

The product claim is that Read, Describe and Find the door never need a connection and every optional network feature has an on-device fallback (ADR-0010, which lists each one and what it sends). The manifest declares `INTERNET` and `ACCESS_NETWORK_STATE`, and the overlay prints them (`Network permission: ACCESS_NETWORK_STATE, INTERNET` is the expected value). The benchmarks and the demo still run with the radios off, so every number and every answer comes from the headset and there is nothing to argue about (RK-4: never demo with Wi-Fi on).

```bash
adb shell settings get global airplane_mode_on       # 1 = on
adb shell cmd connectivity airplane-mode enable      # on (cuts Wi-Fi: install and push BEFORE this over Wi-Fi, or use USB)
adb shell cmd connectivity airplane-mode disable     # off
```

`scripts/benchmark.sh` refuses to run unless the setting reads `1`; `scripts/install_headset.sh` only warns. Order for a benchmark session: install and push over USB or Wi-Fi → airplane mode on → USB stays connected → `scripts/benchmark.sh --model vlm` (then `--model detector`, then `--soak 600`). Re-enabling Wi-Fi while airplane mode stays on keeps Wi-Fi ADB alive and still passes the script's check; that is acceptable for development runs only, never for the demo or the numbers that go into the README.

## 8. No headset? `-PuseFakes=true` on a laptop emulator or the S25 Ultra

`AppGraph` wires `FakeFrameSource` and `FakeInferenceEngine` (and the `NoOp*` perception modules on any non-XR device, ADR-0006) when the build is made with the `useFakes` property:

```bash
./gradlew installDebug -PuseFakes=true      # BuildConfig.USE_FAKES = true for this build only (default false, gradle.properties)
./gradlew testDebugUnitTest                 # the JVM tests always use the fakes; no device, no models
```

On a laptop without an XR-capable emulator, use an Android 14 (API 34, the Galaxy XR's level) phone AVD:

```bash
sdkmanager --install "emulator" "system-images;android-34;google_apis;x86_64"   # arm64-v8a instead on Apple Silicon
avdmanager create avd -n Vysion_Phone_API34 -k "system-images;android-34;google_apis;x86_64" -d pixel_8
emulator -avd Vysion_Phone_API34 -no-snapshot-save &                            # add -no-window -no-audio for headless
adb wait-for-device && ./gradlew installDebug -PuseFakes=true
adb shell pm grant com.vysion.xr android.permission.CAMERA && adb shell pm grant com.vysion.xr android.permission.RECORD_AUDIO
adb shell am start -n com.vysion.xr/.MainActivity
```

The APK is arm64-only (`abiFilters`); it still installs and runs on the x86_64 Android 14 image through the image's built-in ARM translation, which is fine for fakes and useless for ExecuTorch timing. Verified on 2026-10-17 on an Intel MacBook Pro (i9-9880H, 16 GB): emulator 37.2.12, cold launch 8.5 s, no crash; logcat showed `DeviceProfile: … (2D fallback)` and `manifest audit: network=none`.

What you get: the app launches, the welcome and every answer go to `Speaker.say` (logcat shows `TODO(FR-G4) AndroidTtsPort.speak … dropping utterance` until R4 implements the TTS port, so nothing is audible yet), Read and Describe put the canned answer in the transcript panel, Navigation (GuideMe, FindDoor) reports unavailable (perception is `NoOp*` under fakes), the overlay shows the fake model (`fake-vlm`, backend `FAKE`) with real memory and the real manifest audit but no detector outlines (`NoOpNavigationEngine` emits no detections), and the 2D layout on a phone. What you do not get: camera frames, XR perception, ExecuTorch, or any number worth writing down. Fakes exist so a module builds and tests before the real thing lands (ADR-0005); they are never evidence for a P0 item.

## 9. Optional: the Android XR emulator

Needs Android Studio (the XR tooling is absent from stable; stack.md §1 names the current **Canary**, Android Studio Rabbit 2 \| 2026.2.2 Canary 5 on 2026-10-17) and the system image `system-images;android-34;android-xr-preview-playstore;arm64-v8a` (API 34, macOS 13.3+ on Apple Silicon, 16 GB RAM). It is useful for the panel layout only: no world camera, no ExecuTorch numbers, no depth. Run the app there with `-PuseFakes=true`. The APK is arm64-only (`abiFilters`), which matches the arm64 image on Apple Silicon; on an x86_64 Linux host the arm64 image is slow or unavailable, so prefer the S25 Ultra.

**Intel Macs cannot run the XR emulator at all.** Every Android XR system image Google publishes is arm64-v8a only (`system-images;android-34;android-xr-preview-playstore;arm64-v8a`, `…;android-xr-v3-playstore;arm64-v8a`, `…;google-xr;arm64-v8a`, checked with `sdkmanager --list` on 2026-10-17), and the XR emulator requires Apple Silicon. Do not download the image on an Intel Mac: it will not boot. On Intel, the XR targets are the real Galaxy XR over ADB (§4–§5) and, for the 2D path, the phone AVD in §8.

## 10. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `Unsupported class file major version` / `Could not determine java version` / Gradle refuses to start | wrong JDK on `PATH` or in `JAVA_HOME` | use JDK 17: `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` (macOS) or `/usr/lib/jvm/java-17-openjdk-amd64` (Linux); `./gradlew --version` must print JVM 17 |
| `Minimum supported Gradle version is 9.6.0` or an AGP/Gradle incompatibility | not using the wrapper | always `./gradlew`, never a system `gradle`; the wrapper pins 9.6.1 |
| `Failed to install the following Android SDK packages as some licences have not been accepted` | SDK licenses unaccepted | `yes \| sdkmanager --licenses`, then `sdkmanager --install "platforms;android-37.0" "build-tools;36.0.0"` |
| `SDK location not found` | no `local.properties` and no `ANDROID_HOME` | §2 |
| `Could not find androidx.xr.*` / `org.pytorch:executorch-android:1.3.1` | repository or version typo; offline | compare `gradle/libs.versions.toml` with `stack.md` §1; `./gradlew --refresh-dependencies`; the machine needs network for the first build only |
| `Could not resolve org.jetbrains.kotlin.plugin.compose:2.4.21` or a Kotlin/AGP version clash | the compose-plugin lift of the built-in Kotlin misbehaves (stack.md D10) | set `kotlin = "2.2.10"` in the TOML (AGP 9.4.1's bundled KGP) and record it in stack.md "Corrections" |
| `adb: no devices/emulators found` or the device shows `unauthorized` | USB debugging off, cable is charge-only, or the "Allow USB debugging" dialog is still open in the headset | §4; use a data cable in the headset's side port; put the headset on and accept; `adb kill-server && adb devices` |
| `adb devices` is empty right after updating platform-tools, although the headset is plugged in and authorized | the old `adb` server from before the update is still running | `adb kill-server && adb start-server && adb devices` (seen 2026-10-17 on the Galaxy XR) |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.vysion.xr signatures do not match` | another laptop installed its own debug build; every machine has a different debug key | `adb uninstall com.vysion.xr` (also deletes the app's storage, including sideloaded models: check `adb shell ls /sdcard/Android/data/com.vysion.xr/files/models` first), then reinstall. To stop it recurring during the 15-minute merge cadence, let R5 install every build from one laptop |
| `adb connect` works, then the device vanishes | airplane mode (or a reboot) cut Wi-Fi debugging | re-pair (§4) or use USB; install before airplane mode |
| App speaks "model not found" / overlay model row says "not measured" | no `.pte` where the engine looks (nothing pushed, nothing in assets), no lockfile on the device (§6 precondition), a sideloaded file failed its size/sha256 check (`adb logcat -s vysion.security`), or the model did not load (the overlay names a model only once it is loaded) | `scripts/push_models.sh` (dev) or `scripts/fetch_models.sh` + reinstall; `adb shell ls -l /sdcard/Android/data/com.vysion.xr/files/models/` should list `vlm.pte`, `vlm-tokenizer.json`, `detector.pte`, `models.lock` |
| logcat `vysion: detector-int8 unavailable …` or `detector load failed … detector-int8`, then `detector load … row=detector` | the default detector (`detector-int8.pte`) is not on the device or will not load; the app falls back to the FP32 `detector.pte` (about 93 ms per frame instead of 55, `docs/benchmarks.md`) | `ml/export/export_detector_oiv7.sh --int8`, then `scripts/push_models.sh` |
| `fetch_models.sh` exits 2 | rows in `ml/models.lock` still say `url: TBD` | expected until R2 publishes; build with `ml/export/*.sh` and `--from <dir>`, or download the release assets |
| `push_models.sh`: `Permission denied` under `/sdcard/Android/data/...` | app not installed yet, so the package directory is not the app's | install first (§5), launch once, push again |
| No camera frames; logcat `vysion` has no `camera 0: ...` line; app says the camera is unavailable | `CAMERA` permission denied, or the firmware does not expose camera id 0 to apps (A-1 / RK-1) | Settings → Apps → Vysion XR → Permissions → Camera; if granted and still nothing, take the A-1 fallback (Read/Describe on the S25 Ultra) |
| Overlay shows `Network permission:` with anything beyond `ACCESS_NETWORK_STATE, INTERNET` (for example `ACCESS_WIFI_STATE`) | a dependency merged a further network permission into the APK | `scripts/check_privacy.sh` after `assembleDebug` names it; add a `tools:node="remove"` guard for the offending permission in the manifest (after `javap` shows the library tolerates it) and rebuild (ADR-0010 allows only those two) |
| TalkBack is on and the headset reads every control aloud while you debug | TalkBack was enabled for the accessibility pass (R4, Q14) | off: `adb shell settings put secure enabled_accessibility_services ""` · on again: enable it once through Settings → Accessibility, read the component with `adb shell settings get secure enabled_accessibility_services` (Google's is `com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService`; Galaxy devices may ship Samsung's own), then `adb shell settings put secure enabled_accessibility_services <that value>` and `adb shell settings put secure accessibility_enabled 1` |
| logcat `vysion.reliability`: `PSS alarm: N MB > 3500 MB` | resident memory crossed the 3.5 GB alarm from the 4 GB budget (NFR Memory; VLM resident size larger than estimated) | the app does not react on its own: `DiagnosticsCollector` logs the alarm and the operator takes the RK-2/RK-3 decision (smaller VLM export, token cap); for the numbers, `scripts/benchmark.sh --model vlm` and the RK-2 decision tree in `model-plan.md` |
| `benchmark.sh`: `REFUSING: airplane mode is off` | the protocol requires it (SRS §7) | `adb shell cmd connectivity airplane-mode enable` over USB |
| `benchmark.sh`: `no result file at files/bench/<model>-latest.json after the deadline` | the debug `BenchmarkReceiver` was not reached, or the engine threw before the first run | the APK must be a **debug** build; `adb logcat -s vysion.bench` shows `accepted:` and the per-run lines or the failure |
| App installs on the S25 Ultra but shows the spatial layout, or the headset shows the flat one | `DeviceProfile.isXr` mis-detected (A-12) | logcat `vysion` prints the profile at start; fallback in ADR-0006 is a `BuildConfig` flag |
