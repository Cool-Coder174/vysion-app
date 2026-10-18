## What

<!-- One sentence. Title format: `r3: FR-N1: proximity cue with 2 s debounce` -->

## Requirement ids closed or advanced

<!-- FR-xx / NFR area / assumption A-x / risk RK-x -->

## Evidence from the device

<!-- Logcat line, measured number, screenshot path. Required for P0 items. "Not run on device" is an acceptable answer for docs-only PRs. -->

- Device: Galaxy XR / S25 Ultra / none
- Measured:

## Checklist

- [ ] `./gradlew assembleDebug testDebugUnitTest` passes
- [ ] `scripts/check_privacy.sh` passes (no network permission beyond ADR-0010's two, every network call names its fallback, no frame persistence, no frame logging)
- [ ] Every number shown or documented was measured on the device (NFR Honesty)
- [ ] Only `core` types cross module boundaries (ADR-0005)
- [ ] New controls have TalkBack labels; new visuals have an audio twin
- [ ] Cue strings only in `voice/Cues.kt`; prompts only in `inference/PromptLibrary.kt`
- [ ] Module doc checkbox ticked (`docs/modules/r*.md`)
- [ ] If `core/Contracts.kt` changed: announced, and `docs/contracts.md` updated here
