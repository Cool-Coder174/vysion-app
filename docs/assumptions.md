# Assumptions to verify before coding

Source: SRS §12. Each of these is a yes/no we can answer in under an hour with the device in hand. **Anyone whose module depends on an unverified assumption starts with the check, not the code.** Record the result in the table (yes / no / partial), the evidence (a logcat line, a screenshot path, a number), and who checked.

| # | Owner | Assumption | By | Result | Evidence | Checked by |
|---|---|---|---|---|---|---|
| A-1 | R1 | CameraX delivers world-camera frames (camera id 0) to a third-party app on this Galaxy XR firmware with only the `CAMERA` permission; note resolution and format | Sat 11:00 | holds | 2026-10-17 19:57, Galaxy XR worn, `CameraFrameSource` bound analysis + still on id 0: frames 640x480 RGBA_8888 at ~25 fps (logcat `vysion: camera 0: 640x480 RGBA_8888 …`). Ids: 0 back (hfov 79.2°), 1 front (67.4°), 2 and 3 back (79.2°). Still size and rotation are logged on the first `captureStill` (not run yet: no model). | Claude (Isaac's laptop) |
| A-2 | R1 | Compose for XR Full Space keeps passthrough on while our panels are shown | Sat 11:00 | **yes** | 2026-10-17 17:16, Galaxy XR SM-I610, build I610UEU2AZF3 (Android 14 / API 34): the manifest start mode alone put the app in Full Space (`dumpsys window` → `xrDesktopMode=full-space-managed`); `adb exec-out screencap` showed the room in passthrough behind the transcript and control panels. No `requestFullSpace()` or passthrough-opacity call was needed. Screenshot kept out of the public repo (bystanders in frame). | Claude (Isaac's laptop) |
| A-3 | R2 | The current `org.pytorch:executorch-android` 1.3.x AAR on Maven includes the multimodal runner and XNNPACK, so no from-source build is needed | Sat 11:00 | holds | 2026-10-17, Galaxy XR: the Maven AAR (now 1.4.0) loads `LlmModule` and XNNPACK `.pte` files (`vysion.latency: detector load ms=2 backend=XnnpackBackend`, `vlm load ms=957`, `ocr load ms=204`). | Claude (Isaac's laptop) |
| A-4 | R2 | Gemma 4 edge multimodal exports with the documented script and its `.pte` fits in memory on the headset; record file size and load time | Sat 14:00 | no (`docs/stack.md` D5, 2026-10-17) | `LlmModule(MODEL_TYPE_MULTIMODAL)` refuses the `examples/models/gemma4` `.pte` (no `token_embedding` method) at 1.3.1, 1.5.1 and main | research (stack.md D5) |
| A-5 | R3 | ARCore for Jetpack XR `1.0.0-alpha15` (SRS wording; the build pins `1.0.0-rc02`, `docs/stack.md` §1) depth and plane tracking return data on the headset with the `SCENE_UNDERSTANDING_*` permissions granted | Sat 12:00 | | | |
| A-6 | R3 | SceneCore spatial audio point source is available and audible through the headset speakers | Sat 13:00 | | | |
| A-7 | R4 | `SpeechRecognizer` works with network off on the headset | Sat 14:00 | partial | 2026-10-17: `SpeechRecognizer.isOnDeviceRecognitionAvailable()` returned true on the headset (logcat `vysion: A-7 on-device speech recognition available=true`). `AndroidRecognizerPort` is implemented (on-device recognizer only, never the network one); recognition itself, and in airplane mode, is not tested yet (needs a wearer on Hold to talk). The first session logs `A-7 on-device languages installed=…`. | Claude (Isaac's laptop) |
| A-8 | R4 | TTS has an es-MX or es-ES voice installed offline | Sat 14:00 | holds | 2026-10-17, Galaxy XR, default engine `com.samsung.SMT` (48 voices, all offline and installed): es-MX `es-MX-SMTf00` and `es-MX-default` (quality 300), es-ES `es-ES-SMTl01` (500), en-US `en-US-SMTl03` (500, bound at launch: logcat `vysion: tts voice en-US-SMTl03 en-US bound=true`). The engine reports ISO-3 locales (`spa_MEX`), which `VoiceSelection.normalize` maps. Not yet re-checked in airplane mode. | Claude (Isaac's laptop) |
| A-9 | R5 | We have both the Galaxy XR and the S25 Ultra from the track, ADB over Wi-Fi or USB-C to the headset works from each laptop, and the judging time and submission format are confirmed in writing from the organizers | Sat 10:30 | partial | 2026-10-17: ADB over USB-C works from Isaac's MacBook Pro (adb 37.0.1, serial R3GYB0225HT, model SM_I610). Still open: the other laptops, Wi-Fi ADB, the S25 Ultra, and the organizers' written confirmation. | Claude (Isaac's laptop) |

## If an assumption fails

| Failed | Then |
|---|---|
| A-1 | RK-1 fallback: Read and Describe on the S25 Ultra; Navigation stays on the headset with depth and planes only. How it runs: the same debug APK on the phone (`scripts/install_headset.sh --serial <s25>`, models via `scripts/push_models.sh --serial <s25>`); the wearer takes the phone for the Text Reader and Audio Description beats of `docs/demo.md`, points it at the sign / table and says "read this" / "describe" (or taps the panel), then returns to the headset for Navigation. FR-N3 ("find the door") and the detector outlines are cut from the headset demo (both need the headset camera): Navigation shows proximity (FR-N1) and wall / floor cues (FR-N5) only, and the README Limitations says so. Evidence: the phone's `vysion: DeviceProfile: … (2D fallback)`, `camera 0: …` and `vysion.latency: command->action` lines |
| A-2 | Use the start-mode / passthrough-opacity API documented in `docs/stack.md`; if passthrough still drops, show panels only while a command is active |
| A-3 | Build the AAR from source per the ExecuTorch Android docs; R2 loses ~2 h, cut R2's P1 first |
| A-4 | A-4 answered no (D5); see A-13 |
| A-5 | RK-5 fallback: plane hit tests and detector box height as the distance proxy |
| A-6 | Non-spatial earcon through `Speaker.earcon(PING)`; say the bearing more often (every 2 s) |
| A-7 | RK-4: pinch-to-talk and panel buttons are P0; recognition becomes P1 |
| A-8 | Fall back to es-ES, then en-US with a spoken notice; Spanish stays P1 |
| A-9 | Escalate to organizers immediately; nothing else in the plan assumes less than two devices |
| A-10 | stack.md §5 Q1: bump `executorch = "1.5.1"` and/or `xrRuntime = "1.1.0-alpha01"` (both resolved by other verifiers); fallback `kotlin = "2.2.10"`; append a row to stack.md "Corrections found at build time" |
| A-11 | — (implemented in `ModelCatalog`; the alternative is the external files dir, which the catalog checks first) |
| A-12 | fall back to a `BuildConfig` flag set from `-PdeviceProfile=phone` (ADR-0006) |
| A-13 | Option A (Kotlin `Module`-level port of `Gemma4Runner`), or cut the VLM to the S25 Ultra (`docs/stack.md` Q9). With the VLM off the headset, FR-N3 ("find the door") is cut from the headset demo (the door vote needs the VLM; the phone runs `NoOpNavigationEngine`): Navigation shows proximity and wall / floor cues, the demo drops the "find the door" beat, and the README Limitations says so |
| A-14 | same as A-10 |
| A-15 | Copy into `filesDir` via the app (the catalog's second path; needs the file in assets, i.e. a big APK) |

## Assumptions added by the skeleton

| # | Owner | Assumption | By | Result | Evidence | Checked by |
|---|---|---|---|---|---|---|
| A-10 | R5 | `./gradlew assembleDebug` resolves every pinned artifact in `gradle/libs.versions.toml` from `google()` and `mavenCentral()` on a fresh machine with JDK 17 | Sat 10:30 | | | |
| A-11 | R2 | `Module.load` needs a filesystem path, so `.pte` files are copied from `assets/models/` to `filesDir` on first launch (model files only; never frames) | Sat 11:00 | | | |
| A-12 | R1 | The single-APK runtime check for XR capability (see ADR-0006) returns false on the S25 Ultra and the 2D fallback UI shows | Sat 20:00 | | | |
| A-13 | R2 | **The chosen VLM `.pte` loads with `LlmModule(MODEL_TYPE_MULTIMODAL, …)` on the 1.3.1 AAR and answers an image prompt on the headset.** Note: research already showed (2026-10-17) that the SRS's Gemma 4 export does NOT (`docs/stack.md` D5), so A-4 as written is answered "no"; this row covers the replacement path chosen under plan.md Q6 (default: Gemma 3 4B via optimum-executorch) | Sat 14:00 | | | |
| A-14 | R5 | A fresh clone with exactly the pins in `gradle/libs.versions.toml` (executorch 1.3.1 + xr runtime rc02, the two that differ from the research's green build) runs `./gradlew assembleDebug testDebugUnitTest` green (`docs/stack.md` Q1) | Sat 10:30 | | | |
| A-15 | R2 | A multi-GB `.pte` pushed to `/sdcard/Android/data/com.vysion.xr/files/models/` passes `validateFilePath` and loads with `LOAD_MODE_MMAP` (`docs/stack.md` Q20) | Sat 14:00 | | | |
