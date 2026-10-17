# R5 Integration, Docs and Demo

## Purpose

R5 keeps the project shippable while four other people build it. SRS §8 **Owns:** "Repo, build scripts, model download, diagnostics overlay, S25 Ultra fallback build, README, benchmark table, backup video, pitch". **Hands off:** "Green `main` on the headset after every merge; README that a stranger can follow". Due: **continuous**. In code, R5 owns `diagnostics/` (the judge-facing proof of FR-G5 and the measurement protocol behind every number in the README) and the shared `core/` contracts (changes announced and merged alone, ADR-0005); in process, R5 owns `main`, the 15-minute merge cadence, the gates in [`../schedule.md`](../schedule.md), and everything after the Sun 11:00 freeze.

## Files you own

| Path | What it is | Status |
|---|---|---|
| `app/src/main/kotlin/com/vysion/xr/diagnostics/DiagnosticsCollector.kt` | the one `DiagnosticsSource`: combines `engine.vlmInfo/detectorInfo/stats`, 1 Hz `MemorySampler`, one-time `ManifestAudit` into `DiagnosticsSnapshot`; logs `FrameStats` (A-1 evidence) and the 3.5 GB PSS alarm | pure (composition, unit-tested) + real flow wiring; the device APIs sit behind the two helpers below |
| `app/src/main/kotlin/com/vysion/xr/diagnostics/MemorySampler.kt` | total PSS in MB via `ActivityManager.getProcessMemoryInfo`; `null` on any failure; KDoc explains PSS vs heap | real (device API), `kbToMb` pure |
| `app/src/main/kotlin/com/vysion/xr/diagnostics/ManifestAudit.kt` | `PackageManager.getPackageInfo(GET_PERMISSIONS).requestedPermissions` → `"none"` or the offending names; logs under `vysion.security` | real (device API), `classify` pure |
| `app/src/main/kotlin/com/vysion/xr/diagnostics/DiagnosticsOverlay.kt` | `@Composable DiagnosticsOverlay(source, modifier)`: the six SRS §11 rows, monospace ≥ 24 sp, `contentDescription` on the panel, `null` → "not measured"; `DiagnosticsText` holds the pure text rules | real (Compose) + pure (text, unit-tested); preview uses nulls only |
| `app/src/debug/AndroidManifest.xml` (declared by the skeleton) + `app/src/debug/kotlin/com/vysion/xr/diagnostics/BenchmarkReceiver.kt` | the SRS §7 protocol on demand from `scripts/benchmark.sh`: vlm / detector / soak → `files/bench/<model>-latest.json` | real (debug only); stats, JSON, parsing pure and unit-tested |
| `app/src/test/kotlin/com/vysion/xr/diagnostics/*Test.kt` | `ManifestAuditTest`, `MemorySamplerTest`, `DiagnosticsCollectorTest` (with R2's `FakeInferenceEngine` + an in-test `FrameSource`), `DiagnosticsOverlayTextTest`, `BenchmarkReceiverTest` | JVM tests |
| `scripts/push_models.sh` | sideload lockfile artifacts to `/sdcard/Android/data/com.vysion.xr/files/models/`, size-verified; same adb discovery as `install_headset.sh` | script |
| `scripts/install_headset.sh`, `scripts/benchmark.sh`, `scripts/fetch_models.sh`, `scripts/check_privacy.sh` | build+install+launch; measurement protocol; lockfile fetch; privacy gate | scripts (skeleton) |
| `docs/setup.md` | fresh clone → headset in ≤ 30 min, both OSes, troubleshooting | doc |
| `docs/benchmarks.md`, `docs/schedule.md`, `docs/assumptions.md` (A-9, A-10, A-14 rows) | the tables R5 fills from device runs | docs |
| `README.md`, `AGENTS.md`, `CONTRIBUTING.md`, `plan.md`, `.github/workflows/ci.yml` | owned by the orchestrator in the skeleton; R5 edits them after the freeze (numbers, video link, release tag) | docs |
| `app/src/main/kotlin/com/vysion/xr/core/Contracts.kt`, `docs/contracts.md` | the handoff table as code; R5 merges changes alone (ADR-0005, RK-12) | contract |

## Contracts you provide / consume

| Direction | Kotlin signature (from `Contracts.kt` / `AppGraph.kt`) | Other side |
|---|---|---|
| provide | `interface DiagnosticsSource { val snapshot: StateFlow<DiagnosticsSnapshot> }` implemented by `class DiagnosticsCollector(app: Application, engine: InferenceEngine, frames: FrameSource, scope: CoroutineScope)` | R1 wires the overlay into the control panel behind the diagnostics toggle (`a11y_diagnostics_toggle`) on the spatial and 2D layouts |
| provide | `data class DiagnosticsSnapshot(vlm: ModelInfo?, detector: ModelInfo?, backend: String?, lastVlmMs: Long?, medianVlmMs: Long?, detectorFps: Float?, appMemoryMb: Int?, networkPermission: String?, measuredAtMs: Long)` — every nullable is `null` until measured | R1 (panel), judges (overlay) |
| provide | `@Composable fun DiagnosticsOverlay(source: DiagnosticsSource, modifier: Modifier = Modifier)` | R1 places it |
| consume | `InferenceEngine.vlmInfo: StateFlow<ModelInfo?>`, `detectorInfo: StateFlow<ModelInfo?>`, `stats: StateFlow<InferenceStats>`; `suspend fun warmUp()`, `describe(bitmap, prompt, maxNewTokens)`, `detect(bitmap)` (benchmark) | R2 `inference.ExecuTorchInferenceEngine` / `FakeInferenceEngine` |
| consume | `FrameSource.stats: StateFlow<FrameStats?>` (A-1 log line), `suspend fun captureStill(): Bitmap` (benchmark input; caller recycles, INV-9) | R1 `platform.CameraFrameSource` / `FakeFrameSource` |
| consume (debug) | `CommandBus.inject(Command.GuideMe / Command.Stop, CommandSource.TEST)` for the thermal soak | R4 `voice.SpeechCommandBus` → R1 `ModeController` |
| consume (debug) | `AppGraph.scope`, `AppGraph.inferenceEngine`, `AppGraph.frameSource`, `AppGraph.commandBus` via `(application as VysionApplication).graph` | root wiring (ADR-0008) |
| consume (debug) | `PromptLibrary.READ` (R2) — the one cross-package import, see Contract issues | R2 |

## Assumptions to verify first

Verbatim from [`../assumptions.md`](../assumptions.md); record result, evidence and who checked in that table.

| # | Assumption | By | If it fails |
|---|---|---|---|
| A-9 | We have both the Galaxy XR and the S25 Ultra from the track, ADB over Wi-Fi or USB-C to the headset works from each laptop, and the judging time and submission format are confirmed in writing from the organizers | Sat 10:30 | Escalate to organizers immediately; nothing else in the plan assumes less than two devices |
| A-10 | `./gradlew assembleDebug` resolves every pinned artifact in `gradle/libs.versions.toml` from `google()` and `mavenCentral()` on a fresh machine with JDK 17 | Sat 10:30 | stack.md §5 Q1: bump `executorch = "1.5.1"` and/or `xrRuntime = "1.1.0-alpha01"` (both resolved by other verifiers); fallback `kotlin = "2.2.10"`; append a row to stack.md "Corrections found at build time" |
| A-14 | A fresh clone with exactly the pins in `gradle/libs.versions.toml` (executorch 1.3.1 + xr runtime rc02, the two that differ from the research's green build) runs `./gradlew assembleDebug testDebugUnitTest` green (`docs/stack.md` Q1) | Sat 10:30 | same as A-10 |

Also R5's in stack.md §5: **Q2** (headset reports API 34, build `I610UEU2AZF3`+), **Q16** (merged APK has no `INTERNET`; overlay prints "none"; after every merge), **Q17** (lint promotable), **Q18** (submission format in writing), **Q19** (ADB from every laptop; Wi-Fi ADB vs airplane mode).

## Task checklist

Verbatim from SRS §8 R5. Tick in the same PR as the work (CONTRIBUTING.md).

- [ ] P0 Repo with `main` protected, feature branches per role, a 15-minute merge cadence from Sat 15:00; one person (R5) merges and reinstalls on the headset after every merge. Sat 10:30
  Files: GitHub settings, `CONTRIBUTING.md`, `docs/schedule.md` ("Actual start" line) · FR: all (RK-7, RK-12) · Verify: `gh api repos/Cool-Coder174/vysion-app/branches/main/protection` shows required status check `ci`; `git branch -r` lists `r1/xr-platform r2/ml-executorch r3/perception r4/voice-ux r5/integration`; first cadence entry in `docs/schedule.md` gate 3 row
- [ ] P0 `scripts/fetch_models.sh` that downloads or builds the `.pte` files to `app/src/main/assets`; `./gradlew installDebug` works from a fresh clone. Sat 14:00
  Files: `scripts/fetch_models.sh`, `scripts/push_models.sh`, `ml/models.lock` · FR: FR-T1/FR-D1/FR-N2 (models present), ADR-0007 · Verify: `scripts/fetch_models.sh` exits 0 with `✓ vlm: verified` lines (exit 2 is the expected state while rows say `url: TBD`); `scripts/push_models.sh` prints `pushed=N` and `adb shell ls -l /sdcard/Android/data/com.vysion.xr/files/models/` lists the files with the lockfile sizes; a fresh clone (`git clone … /tmp/fresh && cd /tmp/fresh && ./gradlew installDebug`) installs
- [ ] P0 Diagnostics overlay fed by `InferenceEngine` timing hooks and `ActivityManager` memory; shows backend, model, median ms, fps, MB, "Network permission: none". Sat 18:00
  Files: `diagnostics/DiagnosticsCollector.kt`, `MemorySampler.kt`, `ManifestAudit.kt`, `DiagnosticsOverlay.kt`, R1's panel toggle · FR: FR-G5 (OD rubric), NFR Honesty, NFR Offline, NFR Memory · Verify: `./gradlew testDebugUnitTest` green for `com.vysion.xr.diagnostics.*`; on the headset `adb logcat -s vysion.security` prints `manifest audit: network=none requested=…`; screenshot of the overlay with every row non-"not measured" after one Read and ten seconds of Navigation, path in the PR; `adb shell dumpsys meminfo com.vysion.xr | grep "TOTAL PSS"` agrees with the overlay's MB within one sample
- [ ] P0 S25 Ultra build of the same app with a flat 2D UI, used as the fallback demo and for faster ML iteration. Sat 20:00
  Files: `platform/DeviceProfile.kt` (R1), R1's 2D layout, `AppGraph.kt` (`NoOp*` perception on non-XR) · FR: ADR-0006, A-12, RK-1 · Verify: `scripts/install_headset.sh --serial <s25>` launches; logcat `vysion` shows the profile line with `(2D fallback)`; Read and Describe answer; "guide me" speaks the not-available notice (`Cues`), no crash in `adb logcat -s AndroidRuntime:E`
- [ ] P0 README: problem, demo video link, architecture (export this doc's diagram), setup, model table with measured numbers, prompts, what runs locally, limitations, troubleshooting. Sun 11:00
  Files: `README.md`, `docs/benchmarks.md`, `docs/architecture.md` (Mermaid → PNG), `docs/setup.md` · FR: NFR Reproducibility, NFR Honesty, rubric "Deployment & documentation" · Verify: every number in the README `Measured numbers` tables is either a value with a `bench/out/*.json` behind it or "not measured"; a teammate who did not write it follows README + `docs/setup.md` on a clean machine in ≤ 30 min and records the time in the PR; `grep -c "not measured" README.md` is reported in the PR
- [ ] P0 Recorded backup demo (headset screen capture + room camera) of all three modes. Sun 12:00
  Files: README "Demo" link; the video is not committed · FR: RK-8, rubric "Presentation" · Verify: the link opens from a laptop that is not the author's; the clip follows `docs/demo.md` beat by beat and shows the overlay and the airplane-mode status; gate 9 row in `docs/schedule.md` has the link
- [ ] P0 Pitch: 3 slides plus the live demo; the 90-second script in section 11 rehearsed twice. Sun 13:00
  Files: slides (problem / architecture / measured results), `docs/demo.md` · FR: RK-8, RK-9 (product sentence verbatim on slide 1), rubric "Presentation" · Verify: two timed rehearsals under 90 s logged in `docs/demo.md`; slide 3 numbers match `docs/benchmarks.md` cell for cell; gate 10 row ticked
- [ ] P1 Test matrix: 3 lighting conditions, 2 door types, partially open door, person walking across; results in README. Sun 10:00
  Files: `docs/benchmarks.md` "P1 test matrix", README · FR: FR-T1, FR-D1, FR-N3 under conditions; NFR Honesty · Verify: every cell of the matrix is pass / fail / not run with one line of evidence (logcat `vysion.latency` or the spoken answer); misses are written as misses
- [ ] P1 Submission form, team photo, and repo link ready before the deadline. Confirm the deadline with organizers Sat morning.
  Files: `docs/demo.md` submission checklist, release tag · FR: A-9, Q18 · Verify: every box in [`../demo.md#submission-checklist`](../demo.md#submission-checklist) ticked; the deadline is written in `docs/schedule.md` with who confirmed it

Skeleton-added R5 tasks from `plan.md` (same rules):

- [ ] P0 plan.md 2.4 — `scripts/benchmark.sh --model detector` end to end via the debug `BenchmarkReceiver`; row into `docs/benchmarks.md`. Sat 13:00
  Files: `app/src/debug/kotlin/com/vysion/xr/diagnostics/BenchmarkReceiver.kt`, `scripts/benchmark.sh` · FR: FR-N2, SRS §7 protocol · Verify: `scripts/benchmark.sh --model detector --runs 20` prints `result: bench/out/detector-<stamp>.json` and a Markdown row; `adb logcat -s vysion.bench` shows `run 20/20` and `wrote …/files/bench/detector-latest.json`
- [ ] P0 plan.md 4.5 (with R2) — `scripts/benchmark.sh --model vlm --runs 20`; PSS peak; row into `docs/benchmarks.md`; decide RK-2 from the numbers. Sat 16:00
  Files: same, `docs/benchmarks.md`, `docs/model-plan.md` decision tree · FR: FR-T1/FR-D1 latency, NFR Memory · Verify: the JSON's `median_ms`, `p90_ms`, `load_ms`, `file_bytes`, `backend` are non-null; script's `peak PSS (external, dumpsys)` line; the RK-2 branch taken is written in `docs/risks.md`
- [ ] P0 plan.md 8.1 (R2) / NFR Thermal — `scripts/benchmark.sh --soak 600`; rows into the soak table. Sun 10:00
  Files: same · FR: NFR Thermal (detector ≥ 3 fps for 10 min) · Verify: `soak-latest.json` has ≥ 120 samples; `fps` never below 3 or the miss is written as a miss
- [ ] P0 plan.md 8.4 — release tag with APK + `.pte`; lockfile URLs point at the release assets; `fetch_models.sh` verified from a fresh clone by someone who did not write it. Sun 11:00
  Files: GitHub release, `ml/models.lock`, README · FR: ADR-0007, RK-11 · Verify: procedure below; `scripts/fetch_models.sh` from a fresh clone exits 0 with every row `✓ … verified`

## API cheat-sheet

Copied from [`../stack.md`](../stack.md) §3 → R5 (sources: `ActivityManager#getProcessMemoryInfo`, `Debug.MemoryInfo`, `Debug`, `PackageManager#getPackageInfo(String, PackageInfoFlags)`, `PackageInfo#requestedPermissions` on developer.android.com; verified DV-PS + DV-AC). Tags: **VERIFIED** unless marked.

| Fact | Exact | Tag |
|---|---|---|
| Memory | `ActivityManager.getProcessMemoryInfo(int[] pids): Debug.MemoryInfo[]` (kB; since Q only own uid, and rate-limited: faster polling returns the previous sample); `Debug.MemoryInfo.getTotalPss(): Int` (kB) and `getMemoryStat("summary.total-pss")` are the right "resident" numbers because PSS counts resident file-backed mmapped pages (the mmapped .pte); `Debug.getNativeHeapAllocatedSize()` is malloc-only (model NOT in it); `Runtime.totalMemory()` is Java heap only; `Debug.getRss()` is **API 35 — absent on Galaxy XR (API 34) → guard `Build.VERSION.SDK_INT >= 35`** | VERIFIED |
| Permissions | `PackageManager.getPackageInfo(String, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))` (API 33; `GET_PERMISSIONS = 4096`); `PackageInfo.requestedPermissions: String[]?` (null if none; only filled with GET_PERMISSIONS); the merged APK legitimately contains `WAKE_LOCK` from libraries | VERIFIED |
| Airplane mode, battery, thermal (benchmark JSON) | `Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1`; `BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY)` (API 21, `Int.MIN_VALUE` when unknown); `PowerManager.currentThermalStatus` (API 29, `THERMAL_STATUS_NONE..SHUTDOWN` = 0..6) | **not in stack.md**: stable platform APIs used by the debug receiver only; confirm once on the headset and add a row to stack.md "Corrections" if any differs |

```kotlin
// docs/stack.md §3 → R5 (verbatim snippet), https://developer.android.com/reference/android/app/ActivityManager#getProcessMemoryInfo(int[])
class Diagnostics(private val ctx: Context) {
    private val am = ctx.getSystemService(ActivityManager::class.java)
    fun memory(): String {                                              // call at most every ~5 s
        val mi: Debug.MemoryInfo = am.getProcessMemoryInfo(intArrayOf(Process.myPid()))[0]
        val pssMb = mi.totalPss / 1024
        val rssMb = if (Build.VERSION.SDK_INT >= 35) Debug.getRss() / 1024 else -1L
        val nativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        return "PSS $pssMb MB" + (if (rssMb >= 0) " · RSS $rssMb MB" else "") + " · native heap $nativeHeapMb MB"
    }
    // https://developer.android.com/reference/android/content/pm/PackageManager#getPackageInfo(java.lang.String,%20android.content.pm.PackageManager.PackageInfoFlags)
    fun networkPermission(): String {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        val hasInternet = info.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
        return "Network permission: " + if (hasInternet) "INTERNET (BUG)" else "none"
    }
}
```

Device and tooling facts R5 relies on (stack.md §4, with confidence): Galaxy XR is Android 14 / API 34 on every published build (high); developer mode is Settings → About Device → Build Number ×7, USB debugging under System → Developer Options, data-only USB-C side port (medium: labels from a partner guide); `adb` at `~/Library/Android/sdk/platform-tools/adb` on the macOS hosts (scripts find it without `PATH`); the ExecuTorch AAR loads `.pte` files only from real paths, so sideloaded files go to `/sdcard/Android/data/com.vysion.xr/files/` and are read with `LOAD_MODE_MMAP` (A-15 / Q20, VERIFIED at the API level, unverified on the headset).

## Fallbacks

Rows from [`../risks.md`](../risks.md) that name R5, with the decide-by time. After that time nobody argues; the fallback ships.

| # | Risk | Mitigation / fallback | Decide by |
|---|---|---|---|
| RK-3 | Memory pressure with VLM and detector loaded (R2, R5) | Unload the detector during VLM calls; 4 GB budget on the overlay (PSS alarm at 3.5 GB logged under `vysion.reliability`); test 10 minutes continuous (`--soak 600`) | Sat 22:00 |
| RK-7 | Integration collapses Sunday morning | 15-minute merge cadence from Sat 15:00; R5 owns `main`; freeze at Sun 11:00 is not negotiable | continuous |
| RK-8 | Headset tracking loss, thermal throttle, or battery during judging | Recorded backup video; headset at 100% and cooled before our slot; demo under 90 s; S25 Ultra as the second device | Sun 12:00 |
| RK-10 | A Jetpack XR alpha or ExecuTorch API named in the stubs does not match the pinned artifact | Pins live only in `gradle/libs.versions.toml`; stubs have `TODO()` bodies so a signature fix is local; stack.md records which claims were verified; `javap` the AAR (AGENTS.md §8) | first build, Sat 10:30 |
| RK-11 | Model download URLs in `ml/models.lock` change or need auth (R2, R5) | Lockfile carries sha256 + size; `fetch_models.sh --from <dir>`; prebuilt `.pte` attached to the release tag | Sun 11:00 (tag) |
| RK-12 | Two roles edit `core/Contracts.kt` at once | Contract changes are announced before the edit and merged by R5 first, alone | continuous |
| A-9 | Fewer than two devices, or ADB does not reach the headset from a laptop | Escalate to organizers; until then USB-only from one laptop, R5 installs for everyone | Sat 10:30 |

## How to test without the headset

- **Fakes.** `-PuseFakes=true` wires R2's `FakeInferenceEngine` (canned answers after `describeDelayMs` / `detectDelayMs`; `vlmInfo`/`detectorInfo` non-null `ModelInfo("fake-vlm", "none", "FAKE", 0, 896, 896)` etc. after `warmUp()`; `stats` updated on every call) and R1's `FakeFrameSource`. The overlay then shows the fake model and backend, real memory from `ActivityManager`, and the real manifest audit of the installed APK: the two R5 rows that matter for the privacy claim are real even on a laptop emulator or the S25 Ultra. `BenchmarkReceiver` runs end to end against the fakes and writes a JSON whose numbers are the fakes' delays, with `file_bytes` and `load_ms` honestly `null`.
- **JVM unit tests** (`./gradlew testDebugUnitTest`, no device, no models): `ManifestAuditTest` (classification = the `check_privacy.sh` set; short names; sorted; look-alikes ignored), `MemorySamplerTest` (kB → MB; PSS alarm edge), `DiagnosticsCollectorTest` (pure `compose` rules: nulls stay null, backend from metadata, detector fps null until the first `detect`; the live flow with `FakeInferenceEngine` + an in-test `FrameSource` on `runTest`: all "not measured" at start, memory on the 1 Hz tick, model after `warmUp()`, a throwing sampler yields `null` and does not stop the collector), `DiagnosticsOverlayTextTest` (six rows in SRS order; every `null` renders "not measured"; units; the spoken description), `BenchmarkReceiverTest` (request parsing/clamping, median and nearest-rank p90, soak minutes, thermal names, the exact JSON field list and escaping).
- **What `-PuseFakes=true` does not give you:** camera frames, XR perception, ExecuTorch, a `FrameStats` line, or any number that may be written down (NFR Honesty). "Works on the emulator" is not evidence for a P0 item (AGENTS.md §4).

## Merge-cadence runbook (every 15 minutes from Sat 15:00)

1. **Pull.** `git switch main && git pull --ff-only`. Collect the PRs whose CI is green (`testDebugUnitTest`, `assembleDebug`, `check_privacy.sh`, cue gate) and whose template has device evidence for P0 items.
2. **Merge** in dependency order (contracts first, alone; then the role that others wait on). A `Contracts.kt` change is its own merge, announced in the channel, with `docs/contracts.md` and every `Fake*` updated in the same commit (RK-12).
3. **Build locally**, not just in CI: `./gradlew assembleDebug testDebugUnitTest && scripts/check_privacy.sh` (the merged-manifest check needs the fresh build).
4. **Install on the headset:** `scripts/install_headset.sh --no-build --logcat` (USB or `--wifi <ip>:<port>`); if models were re-exported, `scripts/push_models.sh` first.
5. **Gate checklist** (two minutes, every merge): app launches into passthrough and speaks the welcome; `adb logcat -s AndroidRuntime:E` is empty; overlay opens and prints `Network permission: none` (Q16); the gate's own evidence from [`../schedule.md`](../schedule.md) (e.g. gate 5: a Read answered within 6 s or the miss noted); nothing that previously worked stopped working (say "read this", "describe", "stop").
6. **Keep the APK:** `mkdir -p bench/apks && cp app/build/outputs/apk/debug/app-debug.apk bench/apks/main-$(git rev-parse --short HEAD).apk`; keep the last three (gitignored; plan.md Operations "Rollback").
7. **Tick** the gate row in `docs/schedule.md` with the evidence (logcat line, number, screenshot path) and update the phase status table in `plan.md`; post the SHA in the channel.
8. **If red:** `git revert -m 1 <merge-sha>`, reinstall the previous APK from `bench/apks/`, tell the role what failed with the logcat line. Nobody debugs on `main`.
9. **After the Sun 11:00 freeze** only README, video links and the benchmark table merge.

## Release-tag procedure (Sun 11:00, after the freeze)

1. Models in assets: `scripts/fetch_models.sh --from <dir with the final .pte files>` (or the published URLs), so `app/src/main/assets/models/` holds `vlm.pte`, `vlm-tokenizer.*`, `detector.pte`, `models.lock`.
2. Build the prebuilt APK with the models bundled: `./gradlew clean assembleDebug` (the skeleton has no release signing config, so the installable artifact is the debug-signed APK; name it `vysion-xr-v0.1.0-debug.apk`). Install it on the headset from a clean state (`adb uninstall com.vysion.xr` first) and run the 90 s script once: this APK is what a judge installs.
3. Tag and release: `git tag -a v0.1.0-hackathon -m "ExecuTorch Hackathon submission" && git push origin v0.1.0-hackathon`, then `gh release create v0.1.0-hackathon vysion-xr-v0.1.0-debug.apk app/src/main/assets/models/vlm.pte app/src/main/assets/models/detector.pte app/src/main/assets/models/vlm-tokenizer.* ml/models.lock --title "Vysion XR v0.1.0 (hackathon)" --notes-file docs/benchmarks.md`. GitHub release assets are capped at 2 GB per file: a larger `.pte` is split (`split -b 1900m vlm.pte vlm.pte.part-`) with the reassembly line (`cat vlm.pte.part-* > vlm.pte`) in the release notes and in `ml/README.md`, or hosted on Hugging Face with the URL in the lockfile.
4. Lockfile URLs: set each row's `url` to `https://github.com/Cool-Coder174/vysion-app/releases/download/v0.1.0-hackathon/<dest>` with the final `sha256` (printed by `fetch_models.sh`) and `bytes`; commit `r5: ADR-0007: lockfile URLs → v0.1.0-hackathon`.
5. Verify from a fresh clone by someone who did not write it: `git clone … /tmp/fresh && cd /tmp/fresh && scripts/fetch_models.sh && ./gradlew installDebug` (submission checklist). Link the release from the README "Demo" / "Setup" sections.

## Backup video and pitch (Sun 12:00, Sun 13:00)

- **Backup video, gate 9, Sun 12:00** (RK-8): headset screen capture plus a room camera, all three modes, the `docs/demo.md` script beat for beat, overlay visible for the proof beat, airplane-mode status visible. Record twice, keep the better take, upload, put the link in README "Demo" (never commit the file). `adb shell screenrecord` is a fallback for the headset capture if the system recorder is unavailable; verify it on the device first (Q: not in stack.md).
- **Pitch, gate 10, Sun 13:00:** three slides: (1) problem, the three tasks, the product sentence verbatim ("Vysion XR helps you read, understand, and move through a space. It is not validated for unassisted travel by blind users.", RK-9); (2) architecture: the four-layer diagram from `docs/architecture.md` exported to PNG, "nothing leaves the headset"; (3) measured results: the `docs/benchmarks.md` table, misses shown as misses. Rehearse the 90 s script twice, timed; headset at 100% and cool, airplane mode on, backup video open on the laptop.
- **Submission:** every box in [`../demo.md#submission-checklist`](../demo.md#submission-checklist).

## Definition of done

[`../../CONTRIBUTING.md`](../../CONTRIBUTING.md) "Definition of done for a task" applies. Device evidence R5 must attach:

- FR-G5 overlay: a screenshot of the panel on the headset with every row measured (path in the PR), the `vysion.security` audit line, and the `dumpsys meminfo` PSS beside the overlay's value.
- Benchmarks: the `bench/out/<model>-<stamp>.json` files (attached to the PR, not committed), the script's `peak PSS (external, dumpsys)` line, and the pasted rows in `docs/benchmarks.md`.
- Gates: each row of `docs/schedule.md` with a logcat line, a number or a screenshot path, and the SHA installed.
- Reproducibility: the timed fresh-clone run by someone who did not write the README, with the time.
- A-9 / A-10 / A-14 rows in `docs/assumptions.md` filled with result, evidence and name.

## Contract issues

None of these changes `Contracts.kt`; they are recorded so the orchestrator can decide.

1. **`PromptLibrary.READ` in the debug `BenchmarkReceiver`.** SRS §7 fixes the VLM protocol to the READ prompt and prompt text may live only in `inference/PromptLibrary.kt` (R2); `AppGraph` exposes no prompt accessor, so the debug-only receiver imports `com.vysion.xr.inference.PromptLibrary` directly (the only cross-package import in R5's code, flagged in the file). Proposed clean fix: `AppGraph` (orchestrator-owned) exposes `val prompts: PromptLibrary` the way it exposes `modelCatalog`, or the receiver moves to `com.vysion.xr` next to `AppGraph`, which may import every role.
2. **No camera row in `DiagnosticsSnapshot`.** The collector receives `FrameSource` (per `AppGraph.kt`) but the snapshot has no field for `FrameStats`, so the A-1 numbers (camera id, resolution, format, fps) only reach logcat. Proposed additive change: `val camera: FrameStats? = null` on `DiagnosticsSnapshot`, rendered as a seventh row. Not done: SRS §11 lists exactly six fields.
3. **What counts as a network permission.** `scripts/check_privacy.sh` NET_PERMS and ADR-0003 name `ACCESS_NETWORK_STATE` as a violation; stack.md §2.7 says the merged debug APK of the verified build carried a transitive `ACCESS_NETWORK_STATE` and advises testing `INTERNET` only. `ManifestAudit` follows the CI gate (both would print and fail), so if the transitive permission is real, CI §2 fails on the first `assembleDebug` and the overlay prints `Network permission: ACCESS_NETWORK_STATE`. Fix is a manifest-merger guard like the existing one, `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />` (orchestrator-owned `app/src/main/AndroidManifest.xml`), or relaxing the gate; decide at Q16, first merge.
4. **`getProcessMemoryInfo` rate limit.** stack.md → R5 records that since Android Q a faster poll returns the previous sample, while Contracts.kt and the NFR ask for 1 Hz. The sampler polls at 1 Hz as specified; if the overlay's MB visibly freezes on the headset, the one-line swap inside `MemorySampler.sampleMb()` is `android.os.Debug.getMemoryInfo(Debug.MemoryInfo())` (same `totalPss`, not rate-limited). Open question for Sat, owner R5.
5. **`BenchmarkReceiverTest` is debug-variant only.** It lives in `app/src/test` as the task prescribes but exercises a `src/debug` class, so it compiles for `testDebugUnitTest` (CI, AGENTS.md) and not for `testReleaseUnitTest`. If anyone runs `./gradlew test`, move it to `app/src/testDebug/kotlin/...`.
6. **`ml/README.md` does not exist yet** (R2). The sideload path `/sdcard/Android/data/com.vysion.xr/files/models/` is documented in `scripts/push_models.sh` and `docs/setup.md` from stack.md → R2 "Paths" and A-15; R2 should state the same path (and the exact tokenizer filename) in `ml/README.md`, and `ModelCatalog` must look there first (plan.md Q1).
7. **`InferenceStats.detectorFps` is a non-null `Float` (default `0f`) while `DiagnosticsSnapshot.detectorFps` is `Float?`.** The collector maps "never ran" (`lastDetectMs == null`) to `null` and shows the window value otherwise, including `0.0` while the detector is unloaded during a VLM call. If R2 would rather signal "not measured" explicitly, `detectorFps: Float? = null` on `InferenceStats` is the additive change.

## Dependencies needed

None. Everything R5 uses is in `gradle/libs.versions.toml` (Compose BOM, material3, ui-tooling-preview, kotlinx-coroutines, kotlinx-coroutines-test, JUnit 4).
