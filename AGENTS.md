# AGENTS.md — operating manual for coding agents (and humans) on Vysion XR

Vysion XR is a native Android XR app for the Samsung Galaxy XR with three voice-driven modes, Text Reader, Audio Description and Navigation, where every model runs on the headset through ExecuTorch and nothing leaves the device. It is built by five people in ~30 hours at the ExecuTorch Hackathon (Oct 17–18, 2026). The product sentence, verbatim everywhere: **"Vysion XR helps you read, understand, and move through a space. It is not validated for unassisted travel by blind users."**

This file tells you how to work here without asking anyone. `CLAUDE.md` points at it; `CONTRIBUTING.md` is the human-process half.

## 1. Read these five things, in order, before writing code

1. [`README.md`](README.md): what the product is, how to build and run, the pinned versions.
2. This file.
3. [`docs/requirements.md`](docs/requirements.md): the FR ids with owner and phase. Every piece of work traces to one.
4. [`docs/contracts.md`](docs/contracts.md) and the code it indexes, [`app/src/main/kotlin/com/vysion/xr/core/Contracts.kt`](app/src/main/kotlin/com/vysion/xr/core/Contracts.kt): what crosses package boundaries.
5. Your module page in [`docs/modules/`](docs/modules/): your task checklist with SRS due times, the APIs you will call (with URLs in [`docs/stack.md`](docs/stack.md)), and your verification.

If you are unsure which role you are: the FR id's **Owner** column in `docs/requirements.md` says. When it names several roles, the one marked **(lead)** integrates the end-to-end flow and owns the acceptance evidence; the others deliver their contract by its gate (e.g. FR-T1: R1 leads in `ModeController`, R2 delivers `InferenceEngine` by gate 4, R4 delivers `Speaker` by gate 3). A multi-owner FR with no **(lead)** mark is P1/P2: each role does the checklist items its module page lists for that FR id.

## 2. Repository map

```
.
├── README.md, AGENTS.md, CLAUDE.md, CONTRIBUTING.md, LICENSE
├── plan.md            roadmap authority: gates, phases F0–F9, status (engineer-workflow)
├── executor.md        spec authority: per-phase specs, filled by `detail F<N>`
├── History/           immutable plan archive + execution log
├── build.gradle.kts, settings.gradle.kts, gradle.properties, gradlew(.bat)   single `:app` module
├── gradle/            libs.versions.toml (every pin; sources in docs/stack.md), wrapper/
├── local.properties   sdk.dir only; machine-local, gitignored (docs/setup.md)
├── .editorconfig      4 spaces, 120 columns for Kotlin
├── .claude/settings.json   Claude Code permission allowlist (gradlew, scripts/, adb, grep/find, read-only git)
├── docs/              see docs/README.md for the index
│   ├── srs/           the SRS PDF and its verbatim extraction (srs-extract.md)
│   ├── modules/       one page per role R1–R5: tasks, contracts, API cheat-sheet, verification
│   ├── decisions/     ADR-0001..0008
│   ├── requirements.md, nfr.md, architecture.md, contracts.md, model-plan.md
│   ├── schedule.md, risks.md, assumptions.md, benchmarks.md, demo.md
│   ├── stack.md       pinned versions with sources + per-role API cheat-sheets
│   └── setup.md       fresh clone → headset in ≤ 30 min
├── app/
│   ├── build.gradle.kts, proguard-rules.pro
│   ├── src/main/AndroidManifest.xml   no network permission, ever; tools:node="remove" guards strip
│   │                                  INTERNET and ACCESS_NETWORK_STATE that libraries add
│   ├── src/main/assets/models/        README.md; model files + lockfile copy fetched, not committed (ml/models.lock)
│   ├── src/main/res/values/strings.xml
│   ├── src/main/kotlin/com/vysion/xr/
│   │   ├── MainActivity.kt, VysionApplication.kt, AppGraph.kt   (entry, manual wiring, lifecycle)
│   │   ├── core/        Contracts.kt (every cross-module type, incl. the ModePhrasebook / PromptSet seams),
│   │   │                Units.kt (clock-face, half-meter)
│   │   ├── platform/    R1: CameraFrameSource, FakeFrameSource, ModeController, PassthroughOverlayRenderer,
│   │   │                DeviceProfile, Permissions, ui/ (SpatialShell, FlatShell, ControlPanel, TranscriptPanel,
│   │   │                OverlayCanvas)
│   │   ├── inference/   R2: ExecuTorchInferenceEngine, FakeInferenceEngine, PromptLibrary (+ LibraryPromptSet),
│   │   │                ModelCatalog, LatencyStats, YoloPostProcessor
│   │   ├── perception/  R3: XrPerceptionSession, XrHeadPoseSource, XrAnchorStore, XrNavigationEngine,
│   │   │                ProximityMonitor, PlaneSemantics, SpatialAudioPinger, NoOp* (non-XR devices)
│   │   ├── voice/       R4: SpeechCommandBus, CommandGrammar, AndroidSpeaker, Cues (+ CuesPhrasebook), Earcons
│   │   └── diagnostics/ R5: DiagnosticsCollector, DiagnosticsOverlay, MemorySampler, ManifestAudit
│   ├── src/debug/       AndroidManifest.xml + kotlin/.../diagnostics/BenchmarkReceiver.kt (debug-only benchmark)
│   ├── src/test/        JVM unit tests (pure logic + fakes), one folder per package; no device needed
│   └── src/testDebug/   JVM tests of src/debug classes (BenchmarkReceiverTest); run by testDebugUnitTest
├── ml/                models.lock, README.md (VLM decision, export recipes, sideload), export/export_{vlm,detector}.sh,
│                      requirements.txt
├── scripts/           fetch_models.sh, push_models.sh, install_headset.sh, benchmark.sh,
│                      check_privacy.sh, check_cues.sh
└── .github/           workflows/ci.yml (unit tests, assemble, privacy gate, cue gate, lint report),
                       PULL_REQUEST_TEMPLATE.md
```

One package per role. A package imports from `com.vysion.xr.core` and from itself. Nothing else (ADR-0005). The root package (`AppGraph`, `MainActivity`, `VysionApplication`) is the only code that imports every role, because it wires them; debug code reaches the modules through `AppGraph`. Test sources may import another role's public `Fake*` (ADR-0005: consumers test against the provider's fake).

## 3. How work is organized

- **Roles R1–R5** own packages end to end (SRS §8). Each has a checklist in `docs/modules/` with P0/P1/P2 and the SRS due time.
- **FR ids** (`FR-N3`), **NFR areas** (`NFR-latency`), **assumptions** (`A-5`), **risks** (`RK-2`), **invariants** (`INV-6`), **ADRs** (`ADR-0004`) are the vocabulary. Use them in comments, commits and PR titles.
- **Gates** (`docs/schedule.md`): ten merges to `main`, each one a thing that runs on the headset. Phases F1–F9 in `plan.md` map to them.
- **Stubs** are marked `TODO(FR-xx)`; `grep -rn "TODO(" app/src/main/kotlin` is the backlog in code form.
- **Fakes**: every contract has a `Fake*` (or `NoOp*`) so consumers build and test before the real thing exists, except `CommandBus` / `Speaker` (the real `SpeechCommandBus` / `AndroidSpeaker` run anywhere on stub ports) and `DiagnosticsSource`; the two seams `ModePhrasebook` and `PromptSet` have an `Unwired` placeholder instead. Keep them compiling.

## 4. Picking and finishing a task

1. Open your module page. Take the first unchecked **P0** with the earliest due time. P1 only after every P0 of your role is on the headset. P2 only after P1 is stable. Due times on module pages are the SRS's; add the shift written in `docs/schedule.md` "Re-basing the clock" (R5 fills it before the first merge). While that line is blank, the SRS time is the due time; the Sun 11:00 freeze never moves.
   If you are handed an **FR id** instead of a task ("implement FR-T1"), work that FR's items across its owners' module pages in due-time order, starting with your role's; this is a direct assignment and §11's one-phase rule does not apply to it. Example: FR-T1 = `plan.md` tasks 1.2 and 2.1 (R1 camera, A-1), 3.3 (R1 `ModeController`), 4.0–4.4 (R2 VLM path, engine, prompts, streaming) and 5.2 (R1 Read flow), plus the R1 P0 items "capture → first spoken word" and the conditional streaming item.
2. Read the contract you provide or consume in `Contracts.kt` (KDoc carries the FR ids). Do not change it; if you must, see §6.
3. Read the API cheat-sheet for your role in `docs/stack.md` and the verified version pins. Do not invent an API from memory: the Jetpack XR libraries are alpha and ExecuTorch's Android API moves. If something is missing, inspect the artifact (§8).
4. Implement **inside your package**. Replace the `TODO(FR-xx)` body; keep the signature.
5. Run the gates: `./gradlew assembleDebug testDebugUnitTest`, `scripts/check_privacy.sh` and `scripts/check_cues.sh`.
6. Run it on the device: `scripts/install_headset.sh --logcat`. P0 items need device evidence (a logcat line, a number, a screenshot path). "Works on the emulator" is not evidence.
7. Any number you produce goes to `docs/benchmarks.md` (and later the README) from a device run. Never type a number from memory; "not measured" is the honest value.
8. Tick the checkbox in your module page **in the same PR**; title `r3: FR-N1: <what>`; fill the PR template.

## 5. Hard rules (each has a mechanism; breaking one fails review or CI)

| Rule | Why | Where enforced |
|---|---|---|
| No `android.permission.INTERNET` (or any network permission), no analytics/crash SDK | 15 rubric points; ADR-0003 | `scripts/check_privacy.sh` (source + merged manifest), overlay audit |
| Camera frames never written to storage, never logged | privacy NFR; INV-3 | `check_privacy.sh` §3–4; PR checklist |
| Every spoken string lives in `voice/Cues.kt` or comes from the VLM; `ModeController` speaks only through `ModePhrasebook` | SRS §8 R4; INV-4 | `scripts/check_cues.sh` (CI); review |
| Every prompt lives in `inference/PromptLibrary.kt`; other packages read it through `PromptSet` (`AppGraph.prompts`) | SRS §7 | review |
| Every displayed/documented number is measured on the device; `null` renders "not measured" | honesty NFR; INV-7 | overlay rendering; `benchmark.sh` refuses without airplane mode |
| Only `core` types cross packages; contract changes are announced and merged alone by R5 | ADR-0005 | review |
| One `Mutex` around all inference; detector unloaded during VLM; nothing inside the mutex calls another module | ADR-0004; INV-1/2/8 | `InferenceEngine` |
| `Stop` is handled outside the mutex and silences within 300 ms | FR-G4; INV-6 | `ModeController`, `Speaker.stop()` non-suspending |
| Latencies on monotonic clocks (`System.nanoTime`, `SystemClock.elapsedRealtimeNanos`) | H-21 | review |
| A `Bitmap` from `captureStill()` has one owner who recycles it | memory budget; INV-9 | KDoc |
| `AppGraph.stop()` is reversible, `AppGraph.release()` is once: `start`/`stop` follow `onStart`/`onStop` and keep the scope, models and TTS (`warmUp()` is idempotent; `FrameSource.stop()` keeps its executor); `release()` (recognizer, TTS, models, scope) runs only from `onDestroy` when `isFinishing`, and a finished activity in a cached process gets a fresh graph from `VysionApplication.graph` (`AppGraph.isReleased`) | headset off and on restarts without a model reload; `SpeechRecognizer.destroy()` / `TextToSpeech.shutdown()` cannot be undone | `AppGraph`, `VysionApplication`, `MainActivity`; `docs/contracts.md` Lifecycle row |
| New dependency only with a line in `docs/stack.md` (what for, no network path) | ADR-0003 | review |
| No DI framework, no product flavors, no new Gradle modules | ADR-0006/0008 | review |

## 6. Changing a contract

Say what and why in the merge channel first. R5 merges the `Contracts.kt` change alone, before anything else. Update `docs/contracts.md` and every `Fake*` in the same commit. Keep the change additive when possible (a new optional method with a default body, like `describeStreaming`).

## 7. Commands

```bash
./gradlew assembleDebug testDebugUnitTest      # build + JVM tests (JDK 17; local.properties needs sdk.dir, see docs/setup.md)
scripts/check_privacy.sh                       # privacy gate; run after assembleDebug for the merged-manifest check
scripts/check_cues.sh                          # cue gate: no spoken literal outside voice/Cues.kt, no hedging words
scripts/fetch_models.sh [--from <dir>]         # models into app/src/main/assets/models per ml/models.lock
scripts/push_models.sh                         # sideload models to /sdcard/Android/data/com.vysion.xr/files/models/ (dev)
scripts/install_headset.sh [--wifi <ip>:<port>] [--logcat]   # build, install, launch; warns if airplane mode is off
scripts/benchmark.sh --model vlm|detector      # 20-run protocol; refuses without airplane mode
scripts/benchmark.sh --soak 600                # 10-minute thermal soak
grep -rn "TODO(" app/src/main/kotlin           # the backlog in code
```

`adb` lives at `~/Library/Android/sdk/platform-tools/adb` on the macOS hosts; the scripts find it without `PATH` changes.

## 8. Where facts live (so you never guess)

| Fact | Location |
|---|---|
| Library versions and why | `gradle/libs.versions.toml` + `docs/stack.md` (source URL + verified-by per pin) |
| API snippets per role (CameraX, Compose for XR, ARCore for Jetpack XR, SceneCore audio, ExecuTorch, Speech, TTS, memory) | `docs/stack.md` |
| Thresholds (1.0 m, 1.5 m, 60 tokens, 15 s, 300 ms, 0.7x) | `core/Contracts.kt` companions, `core/Units.kt`, `docs/nfr.md` |
| Prompts | `inference/PromptLibrary.kt` (quoted in `docs/model-plan.md`) |
| Cue wording rules | `docs/modules/r4-voice-accessibility.md` phrasing guide |
| Model files, hashes, export scripts | `ml/models.lock`, `ml/README.md`, `ml/export/` |
| Due times and gates | `docs/schedule.md` |
| What to do when a check fails | `docs/assumptions.md` (fallback table), `docs/risks.md` |

To check an API against the real artifact instead of docs: after one Gradle sync, the AARs are under `~/.gradle/caches/modules-2/files-2.1/<group>/<artifact>/<version>/`; `unzip -p <aar> classes.jar > /tmp/c.jar && javap -cp /tmp/c.jar <fqcn>` prints the real signatures. For ExecuTorch, the Java sources are also at `https://github.com/pytorch/executorch/tree/<tag>/extension/android/executorch_android/src/main/java/org/pytorch/executorch`.

## 9. Testing strategy

- **JVM unit tests** (`app/src/test`, plus `app/src/testDebug` for classes in `src/debug`): pure logic (`core/Units`, `voice/CommandGrammar`, `ModeController` with fakes, cue formatting). Fast, no device, run in CI by `testDebugUnitTest`.
- **Device runs**: everything that touches the camera, XR runtime, ExecuTorch, speech or audio. Evidence is a logcat line under tags `vysion`, `vysion.latency`, `vysion.reliability`, `vysion.bench`, `vysion.security`, or a benchmark JSON.
- **No instrumented UI tests** this weekend (simplicity pass in `plan.md`).
- "Verified" means: it ran on the headset (or the S25 Ultra for R2 iteration), the number is in `docs/benchmarks.md`, and the module checkbox is ticked.

## 10. Gotchas that cost hours

- ExecuTorch `Module.load` needs a filesystem path. Model files are read from the app's external files dir (sideloaded with `adb push` during development) or copied from `assets/models/` to `filesDir/models/` on first launch. The copy is the only write the app ever does.
- Sideloading needs a lockfile on the device: `ModelCatalog` reads `assets/models/models.lock` (gitignored; `scripts/fetch_models.sh` copies it there before it exits 2 on `TBD` rows, then `./gradlew assembleDebug` bundles it) and falls back to `/sdcard/Android/data/com.vysion.xr/files/models/models.lock`, which `scripts/push_models.sh` pushes. With neither, no model resolves. A sideloaded file whose size or sha256 differs from a non-`TBD` lockfile row is refused (`vysion.security`).
- The dev APK must **not** bundle the multi-GB models (the default option C VLM is *estimated* at 2.5–3.5 GB, `ml/README.md` §3, not measured; the detector is MBs); a Wi-Fi reinstall would take minutes and break the 15-minute merge cadence. Sideload during development; bundle only for the release tag (`plan.md` Q1).
- `SpeechRecognizer`, `TextToSpeech` and Compose are main-thread APIs; inference never runs on main.
- Benchmarks and the demo run in airplane mode. `benchmark.sh` refuses otherwise. Never demo with Wi-Fi on (RK-4).
- Jetpack XR is alpha: an API in `docs/stack.md` marked UNVERIFIED may have moved. Check the artifact (§8) before spending an hour on a compile error.
- The detector is unloaded while the VLM runs, so Navigation outlines pause during a one-shot command. That is by design (ADR-0004), not a bug.
- `ml/models.lock` rows with `url: "TBD"` make `fetch_models.sh` exit 2 with instructions. That is expected until R2 publishes artifacts.
- The SRS's VLM line (Gemma 4 through the Android multimodal runner) is blocked: the `examples/models/gemma4` `.pte` has no `token_embedding` method, so `LlmModule` refuses it at ExecuTorch 1.3.1, 1.5.1 and main (`docs/stack.md` D5, `plan.md` Q6, RK-13). R2 decides the VLM path (default option C, Gemma 3 4B via optimum-executorch; alternative A, a Kotlin `Gemma4Runner` port over `Module`; recipes in `ml/README.md` §0/§3) before writing any engine code. The `InferenceEngine` contract and the lockfile dests are the same either way.
- Libraries can add network permissions transitively (SceneCore → `media3-exoplayer` adds `ACCESS_NETWORK_STATE`). The manifest guards strip them; if `check_privacy.sh` fails on the merged manifest after a dependency change, add another `tools:node="remove"` guard after checking the library tolerates it (`javap`, §8). Never relax the gate.

## 11. Working with the engineer-workflow commands

`plan.md` is the roadmap; `executor.md` holds per-phase specs. `detail F<N>` writes the spec for phase N into `executor.md`; `implement F<N>` builds it; `verify F<N>` checks the code against the spec with device evidence and appends the report. Phase status is updated in `plan.md` after each step; the archive in `History/` gets a log row. One phase per cycle; do not run ahead: this applies to the `detail` / `implement` / `verify F<N>` commands only (`plan.md` ends with "STOP — awaiting `implement F1`" for that reason). A task or an FR id assigned to you directly follows §4 (earliest-due P0 first, across phases), not the phase order.

## 12. Etiquette

- Do not edit another role's package to work around a missing piece; use the `Fake*` and say what you need.
- Keep docs in sync in the same PR as the code (module checkbox, contracts page, benchmarks table).
- Prefer deleting over adding. If a component has no number or requirement behind it, it does not go in (simplicity pass, `plan.md`).
- When a check fails, write the result in `docs/assumptions.md` or `docs/risks.md` and take the named fallback. Do not debate past the decide-by time.
