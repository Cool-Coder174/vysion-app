# Assumptions to verify before coding

Source: SRS §12. Each of these is a yes/no we can answer in under an hour with the device in hand. **Anyone whose module depends on an unverified assumption starts with the check, not the code.** Record the result in the table (yes / no / partial), the evidence (a logcat line, a screenshot path, a number), and who checked.

| # | Owner | Assumption | By | Result | Evidence | Checked by |
|---|---|---|---|---|---|---|
| A-1 | R1 | CameraX delivers world-camera frames (camera id 0) to a third-party app on this Galaxy XR firmware with only the `CAMERA` permission; note resolution and format | Sat 11:00 | | | |
| A-2 | R1 | Compose for XR Full Space keeps passthrough on while our panels are shown | Sat 11:00 | | | |
| A-3 | R2 | The current `org.pytorch:executorch-android` 1.3.x AAR on Maven includes the multimodal runner and XNNPACK, so no from-source build is needed | Sat 11:00 | | | |
| A-4 | R2 | Gemma 4 edge multimodal exports with the documented script and its `.pte` fits in memory on the headset; record file size and load time | Sat 14:00 | | | |
| A-5 | R3 | ARCore for Jetpack XR `1.0.0-alpha15` depth and plane tracking return data on the headset with the `SCENE_UNDERSTANDING_*` permissions granted | Sat 12:00 | | | |
| A-6 | R3 | SceneCore spatial audio point source is available and audible through the headset speakers | Sat 13:00 | | | |
| A-7 | R4 | `SpeechRecognizer` works with network off on the headset | Sat 14:00 | | | |
| A-8 | R4 | TTS has an es-MX or es-ES voice installed offline | Sat 14:00 | | | |
| A-9 | R5 | We have both the Galaxy XR and the S25 Ultra from the track, ADB over Wi-Fi or USB-C to the headset works from each laptop, and the judging time and submission format are confirmed in writing from the organizers | Sat 10:30 | | | |

## If an assumption fails

| Failed | Then |
|---|---|
| A-1 | RK-1 fallback: Read and Describe on the S25 Ultra; Navigation stays on the headset with depth and planes only |
| A-2 | Use the start-mode / passthrough-opacity API documented in `docs/stack.md`; if passthrough still drops, show panels only while a command is active |
| A-3 | Build the AAR from source per the ExecuTorch Android docs; R2 loses ~2 h, cut R2's P1 first |
| A-4 | RK-2 fallback: SmolVLM-500M, output tokens capped at 60 |
| A-5 | RK-5 fallback: plane hit tests and detector box height as the distance proxy |
| A-6 | Non-spatial earcon through `Speaker.earcon(PING)`; say the bearing more often (every 2 s) |
| A-7 | RK-4: pinch-to-talk and panel buttons are P0; recognition becomes P1 |
| A-8 | Fall back to es-ES, then en-US with a spoken notice; Spanish stays P1 |
| A-9 | Escalate to organizers immediately; nothing else in the plan assumes less than two devices |

## Assumptions added by the skeleton

| # | Owner | Assumption | By | Result | Evidence | Checked by |
|---|---|---|---|---|---|---|
| A-10 | R5 | `./gradlew assembleDebug` resolves every pinned artifact in `gradle/libs.versions.toml` from `google()` and `mavenCentral()` on a fresh machine with JDK 17 | Sat 10:30 | | | |
| A-11 | R2 | `Module.load` needs a filesystem path, so `.pte` files are copied from `assets/models/` to `filesDir` on first launch (model files only; never frames) | Sat 11:00 | | | |
| A-12 | R1 | The single-APK runtime check for XR capability (see ADR-0006) returns false on the S25 Ultra and the 2D fallback UI shows | Sat 20:00 | | | |
| A-13 | R2 | **The chosen VLM `.pte` loads with `LlmModule(MODEL_TYPE_MULTIMODAL, …)` on the 1.3.1 AAR and answers an image prompt on the headset.** Note: research already showed (2026-10-17) that the SRS's Gemma 4 export does NOT (`docs/stack.md` D5), so A-4 as written is answered "no"; this row covers the replacement path chosen under plan.md Q6 (default: Gemma 3 4B via optimum-executorch) | Sat 14:00 | | | |
| A-14 | R5 | A fresh clone with exactly the pins in `gradle/libs.versions.toml` (executorch 1.3.1 + xr runtime rc02, the two that differ from the research's green build) runs `./gradlew assembleDebug testDebugUnitTest` green (`docs/stack.md` Q1) | Sat 10:30 | | | |
| A-15 | R2 | A multi-GB `.pte` pushed to `/sdcard/Android/data/com.vysion.xr/files/models/` passes `validateFilePath` and loads with `LOAD_MODE_MMAP` (`docs/stack.md` Q20) | Sat 14:00 | | | |
