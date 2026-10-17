# AGENTS.md — operating manual for coding agents (and humans) on Vysion XR

Vysion XR is a native Android XR app for the Samsung Galaxy XR with three voice-driven modes, Text Reader, Audio Description and Navigation, where every model runs on the headset through ExecuTorch and nothing leaves the device. It is built by five people in ~30 hours at the ExecuTorch Hackathon (Oct 17–18, 2026). The product sentence, verbatim everywhere: **"Vysion XR helps you read, understand, and move through a space. It is not validated for unassisted travel by blind users."**

This file tells you how to work here without asking anyone. `CLAUDE.md` points at it; `CONTRIBUTING.md` is the human-process half.

## 1. Read these five things, in order, before writing code

1. [`README.md`](README.md): what the product is, how to build and run, the pinned versions.
2. This file.
3. [`docs/requirements.md`](docs/requirements.md): the FR ids with owner and phase. Every piece of work traces to one.
4. [`docs/contracts.md`](docs/contracts.md) and the code it indexes, [`app/src/main/kotlin/com/vysion/xr/core/Contracts.kt`](app/src/main/kotlin/com/vysion/xr/core/Contracts.kt): what crosses package boundaries.
5. Your module page in [`docs/modules/`](docs/modules/): your task checklist with SRS due times, the APIs you will call (with URLs in [`docs/stack.md`](docs/stack.md)), and your verification.

If you are unsure which role you are: the FR id's **Owner** column in `docs/requirements.md` says.

## 2. Repository map

```
.
├── README.md, AGENTS.md, CLAUDE.md, CONTRIBUTING.md, LICENSE
├── plan.md            roadmap authority: gates, phases F0–F9, status (engineer-workflow)
├── executor.md        spec authority: per-phase specs, filled by `detail F<N>`
├── History/           immutable plan archive + execution log
├── docs/              see docs/README.md; srs/ has the SRS PDF and its verbatim extraction
│   ├── modules/       one page per role R1–R5: tasks, contracts, API cheat-sheet, verification
│   ├── decisions/     ADRs
│   ├── stack.md       pinned versions with sources + per-role API cheat-sheets
│   └── setup.md       fresh clone → headset in ≤ 30 min
├── app/
│   ├── build.gradle.kts, src/main/AndroidManifest.xml (no INTERNET permission, ever)
│   ├── src/main/assets/models/     model files, fetched not committed (ml/models.lock)
│   └── src/main/kotlin/com/vysion/xr/
│       ├── MainActivity.kt, VysionApplication.kt, AppGraph.kt   (entry + manual wiring)
│       ├── core/        Contracts.kt (every cross-module type), Units.kt (clock-face, half-meter)
│       ├── platform/    R1: FrameSource, ModeController, OverlayRenderer, DeviceProfile, Permissions, ui/
│       ├── inference/   R2: InferenceEngine (ExecuTorch), PromptLibrary, ModelCatalog, FakeInferenceEngine
│       ├── perception/  R3: XrPerceptionSession, ProximityMonitor, AnchorStore, NavigationEngine, SpatialAudioPinger
│       ├── voice/       R4: CommandBus, CommandGrammar, Speaker, Cues, Earcons
│       └── diagnostics/ R5: DiagnosticsCollector, DiagnosticsOverlay, MemorySampler, ManifestAudit, BenchmarkReceiver
├── app/src/test/      JVM unit tests (pure logic + fakes); no device needed
├── ml/                models.lock, README.md (export recipes), export/*.sh, requirements.txt
├── scripts/           fetch_models.sh, install_headset.sh, benchmark.sh, check_privacy.sh
└── .github/           ci.yml (assemble + unit tests + privacy gate), PR template
```

One package per role. A package imports from `com.vysion.xr.core` and from itself. Nothing else (ADR-0005).

## 3. How work is organized

- **Roles R1–R5** own packages end to end (SRS §8). Each has a checklist in `docs/modules/` with P0/P1/P2 and the SRS due time.
- **FR ids** (`FR-N3`), **NFR areas** (`NFR-latency`), **assumptions** (`A-5`), **risks** (`RK-2`), **invariants** (`INV-6`), **ADRs** (`ADR-0004`) are the vocabulary. Use them in comments, commits and PR titles.
- **Gates** (`docs/schedule.md`): ten merges to `main`, each one a thing that runs on the headset. Phases F1–F9 in `plan.md` map to them.
- **Stubs** are marked `TODO(FR-xx)`; `grep -rn "TODO(" app/src/main/kotlin` is the backlog in code form.
- **Fakes**: every contract has a `Fake*` so consumers build and test before the real thing exists. Keep them compiling.

## 4. Picking and finishing a task

1. Open your module page. Take the first unchecked **P0** with the earliest due time. P1 only after every P0 of your role is on the headset. P2 only after P1 is stable.
2. Read the contract you provide or consume in `Contracts.kt` (KDoc carries the FR ids). Do not change it; if you must, see §6.
3. Read the API cheat-sheet for your role in `docs/stack.md` and the verified version pins. Do not invent an API from memory: the Jetpack XR libraries are alpha and ExecuTorch's Android API moves. If something is missing, inspect the artifact (§8).
4. Implement **inside your package**. Replace the `TODO(FR-xx)` body; keep the signature.
5. Run the gates: `./gradlew assembleDebug testDebugUnitTest` and `scripts/check_privacy.sh`.
6. Run it on the device: `scripts/install_headset.sh --logcat`. P0 items need device evidence (a logcat line, a number, a screenshot path). "Works on the emulator" is not evidence.
7. Any number you produce goes to `docs/benchmarks.md` (and later the README) from a device run. Never type a number from memory; "not measured" is the honest value.
8. Tick the checkbox in your module page **in the same PR**; title `r3: FR-N1: <what>`; fill the PR template.

## 5. Hard rules (each has a mechanism; breaking one fails review or CI)

| Rule | Why | Where enforced |
|---|---|---|
| No `android.permission.INTERNET` (or any network permission), no analytics/crash SDK | 15 rubric points; ADR-0003 | `scripts/check_privacy.sh` (source + merged manifest), overlay audit |
| Camera frames never written to storage, never logged | privacy NFR; INV-3 | `check_privacy.sh` §3–4; PR checklist |
| Every spoken string lives in `voice/Cues.kt` or comes from the VLM | SRS §8 R4; INV-4 | review; R4's cue grep |
| Every prompt lives in `inference/PromptLibrary.kt` | SRS §7 | review |
| Every displayed/documented number is measured on the device; `null` renders "not measured" | honesty NFR; INV-7 | overlay rendering; `benchmark.sh` refuses without airplane mode |
| Only `core` types cross packages; contract changes are announced and merged alone by R5 | ADR-0005 | review |
| One `Mutex` around all inference; detector unloaded during VLM; nothing inside the mutex calls another module | ADR-0004; INV-1/2/8 | `InferenceEngine` |
| `Stop` is handled outside the mutex and silences within 300 ms | FR-G4; INV-6 | `ModeController`, `Speaker.stop()` non-suspending |
| Latencies on monotonic clocks (`System.nanoTime`, `SystemClock.elapsedRealtimeNanos`) | H-21 | review |
| A `Bitmap` from `captureStill()` has one owner who recycles it | memory budget; INV-9 | KDoc |
| New dependency only with a line in `docs/stack.md` (what for, no network path) | ADR-0003 | review |
| No DI framework, no product flavors, no new Gradle modules | ADR-0006/0008 | review |

## 6. Changing a contract

Say what and why in the merge channel first. R5 merges the `Contracts.kt` change alone, before anything else. Update `docs/contracts.md` and every `Fake*` in the same commit. Keep the change additive when possible (a new optional method with a default body, like `describeStreaming`).

## 7. Commands

```bash
./gradlew assembleDebug testDebugUnitTest      # build + JVM tests (JDK 17; local.properties needs sdk.dir, see docs/setup.md)
scripts/check_privacy.sh                       # privacy gate; run after assembleDebug for the merged-manifest check
scripts/fetch_models.sh [--from <dir>]         # models into app/src/main/assets/models per ml/models.lock
scripts/install_headset.sh [--wifi <ip>] [--logcat]   # build, install, launch; warns if airplane mode is off
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

- **JVM unit tests** (`app/src/test`): pure logic (`core/Units`, `voice/CommandGrammar`, `ModeController` with fakes, cue formatting). Fast, no device, run in CI.
- **Device runs**: everything that touches the camera, XR runtime, ExecuTorch, speech or audio. Evidence is a logcat line under tags `vysion`, `vysion.latency`, `vysion.reliability`, `vysion.bench`, `vysion.security`, or a benchmark JSON.
- **No instrumented UI tests** this weekend (simplicity pass in `plan.md`).
- "Verified" means: it ran on the headset (or the S25 Ultra for R2 iteration), the number is in `docs/benchmarks.md`, and the module checkbox is ticked.

## 10. Gotchas that cost hours

- ExecuTorch `Module.load` needs a filesystem path. Model files are read from the app's external files dir (sideloaded with `adb push` during development) or copied from `assets/models/` to `filesDir/models/` on first launch. The copy is the only write the app ever does.
- The dev APK must **not** bundle the ~1.5 GB of models; a Wi-Fi reinstall would take minutes and break the 15-minute merge cadence. Sideload during development; bundle only for the release tag (`plan.md` Q1).
- `SpeechRecognizer`, `TextToSpeech` and Compose are main-thread APIs; inference never runs on main.
- Benchmarks and the demo run in airplane mode. `benchmark.sh` refuses otherwise. Never demo with Wi-Fi on (RK-4).
- Jetpack XR is alpha: an API in `docs/stack.md` marked UNVERIFIED may have moved. Check the artifact (§8) before spending an hour on a compile error.
- The detector is unloaded while the VLM runs, so Navigation outlines pause during a one-shot command. That is by design (ADR-0004), not a bug.
- `ml/models.lock` rows with `url: "TBD"` make `fetch_models.sh` exit 2 with instructions. That is expected until R2 publishes artifacts.

## 11. Working with the engineer-workflow commands

`plan.md` is the roadmap; `executor.md` holds per-phase specs. `detail F<N>` writes the spec for phase N into `executor.md`; `implement F<N>` builds it; `verify F<N>` checks the code against the spec with device evidence and appends the report. Phase status is updated in `plan.md` after each step; the archive in `History/` gets a log row. One phase per cycle; do not run ahead.

## 12. Etiquette

- Do not edit another role's package to work around a missing piece; use the `Fake*` and say what you need.
- Keep docs in sync in the same PR as the code (module checkbox, contracts page, benchmarks table).
- Prefer deleting over adding. If a component has no number or requirement behind it, it does not go in (simplicity pass, `plan.md`).
- When a check fails, write the result in `docs/assumptions.md` or `docs/risks.md` and take the named fallback. Do not debate past the decide-by time.
