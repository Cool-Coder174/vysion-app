# CLAUDE.md

Read [`AGENTS.md`](AGENTS.md) first; it is the operating manual for this repository and this file only summarizes it.

## What this is
Vysion XR: a native Android XR (Kotlin, Jetpack Compose for XR, SceneCore, ARCore for Jetpack XR) accessibility app for the Samsung Galaxy XR. Three voice modes (Text Reader, Audio Description, Navigation); a Gemma 4 edge VLM and a YOLO26-n detector run fully on device through ExecuTorch (XNNPACK). Hackathon build, Oct 17–18 2026, five roles R1–R5.

## Where to look
- Requirements with owner/phase: `docs/requirements.md`. Contracts: `app/src/main/kotlin/com/vysion/xr/core/Contracts.kt` + `docs/contracts.md`.
- Your tasks: `docs/modules/r<N>-*.md`. Versions and API snippets: `docs/stack.md`. Setup: `docs/setup.md`.
- Roadmap and gates: `plan.md`, `docs/schedule.md`. Decisions: `docs/decisions/`.
- Backlog in code: `grep -rn "TODO(" app/src/main/kotlin`.

## Non-negotiables
1. No network permission, no analytics; camera frames never persisted or logged (`scripts/check_privacy.sh`).
2. Spoken strings only in `voice/Cues.kt`; prompts only in `inference/PromptLibrary.kt`.
3. Numbers shown or documented are measured on the device; otherwise "not measured".
4. Cross-package calls only through `core` contracts; contract changes are announced and merged alone.
5. One `Mutex` around inference; detector unloaded during VLM calls; `Stop` handled outside it within 300 ms.
6. Monotonic clocks for latency; `Bitmap` from `captureStill()` has one owner.
7. No DI framework, flavors, or new Gradle modules.

## Commands
```bash
./gradlew assembleDebug testDebugUnitTest
scripts/check_privacy.sh
scripts/install_headset.sh --logcat
scripts/fetch_models.sh
scripts/benchmark.sh --model vlm
```

## Definition of done
FR id in the PR title; gates green; device evidence for P0; module checkbox ticked; numbers in `docs/benchmarks.md`. See `CONTRIBUTING.md`.
