# Contributing

Five people (or agents), five modules, one integration branch, two days. The rules below are the ones that keep the Sunday-morning merge from collapsing (RK-7). If you are an AI agent, [`AGENTS.md`](AGENTS.md) is the operating manual and this file is the human-process half of it.

## Branches and merges

| What | Rule |
|---|---|
| `main` | protected; only R5 merges; every merge is installed on the headset before the next one |
| role branches | `r1/xr-platform`, `r2/ml-executorch`, `r3/perception`, `r4/voice-ux`, `r5/integration`; task branches hang off them (`r3/proximity`) |
| cadence | from Sat 15:00, R5 merges what is green every 15 minutes |
| freeze | Sun 11:00; after that only README, video links and the benchmark table change |
| contracts | `app/src/main/kotlin/com/vysion/xr/core/Contracts.kt` changes are announced first and merged alone (ADR-0005) |

## Definition of done for a task

A task from a module doc (`docs/modules/r*.md`) is done when all of these hold:

1. The FR id(s) it closes are in the PR title (`FR-N1: proximity cue with 2 s debounce`).
2. `./gradlew assembleDebug testDebugUnitTest` passes and `scripts/check_privacy.sh` is clean.
3. It ran on the headset (or the S25 Ultra for R2 iteration) and the evidence is in the PR: a logcat line, a number, a screenshot path. "Works on my emulator" is not evidence for a P0 item.
4. Any number it produces is measured on the device and goes to `docs/benchmarks.md`; no number is typed from memory.
5. The checkbox in the module doc is ticked in the same PR.
6. If it changed a cue string, the change is in `voice/Cues.kt`; if it changed a prompt, in `inference/PromptLibrary.kt`; nowhere else.

## Code style

- Kotlin, official code style (`kotlin.code.style=official`), 4 spaces, 120 columns (`.editorconfig`).
- Coroutines and `Flow` for everything asynchronous; no callbacks cross a module boundary.
- Comments reference requirements by id (`// FR-G4: stop within 300 ms`) and decisions by ADR (`// ADR-0004`).
- `TODO(FR-xx)` marks skeleton stubs; grep for `TODO(` to see what is left.
- No new dependencies without a line in `docs/stack.md` saying what it is for and that it has no network path.

## Review checklist (reviewer ticks before approving)

- [ ] Privacy: no frame leaves memory, no log of frame content, no network permission ([`scripts/check_privacy.sh`](scripts/check_privacy.sh))
- [ ] Honesty: every displayed or documented number traces to a measurement
- [ ] Contracts: only `core` types cross module boundaries; no `import com.vysion.xr.<other role>`
- [ ] Accessibility: every new control has a `contentDescription`; every new visual has an audio twin
- [ ] Cues and prompts live only in `Cues.kt` / `PromptLibrary.kt`
- [ ] Device evidence attached for P0 items

## Commit messages

`<role>: <FR ids>: <what>` in the imperative, body explains why and what was measured.

```
r3: FR-N1: sample depth cone at 10 Hz, 2 s debounce

Measured on headset: 42 ms from threshold crossing to earcon start (target ≤ 300 ms).
```

## Running the app

See [`docs/setup.md`](docs/setup.md) for the fresh-clone-to-headset path (target ≤ 30 min) and the S25 Ultra fallback.
