# Schedule and integration gates

Source: SRS §9. Ten gates on `main`, each one a thing that runs on the headset, not a slide. Saturday is for getting every module alive in isolation and merged by evening; Sunday morning is for the one feature that needs everything at once, Find the door; everything after the Sun 11:00 freeze is documentation, video, and rehearsal.

Each gate is a merge to `main` that R5 installs and runs on the headset before anyone moves on. **If a gate slips more than two hours, the P1 items for that role are cut first, then P2, never the gate.** Confirm the judging time with organizers Saturday morning.

> **Re-basing the clock.** The times below are the SRS's, counted from a Sat 10:30 start. The skeleton landed on Sat Oct 17 in the early afternoon, so gates 1–2 are already past on paper. When the team actually starts, R5 writes the real start time here, shifts every gate by the same delay, and applies the cut rule (P1 first) to whatever no longer fits before the Sun 11:00 freeze, which does **not** move because judging does not move.
>
> Actual start: ____ · Shift applied: ____ h · Items cut: ____

| # | Gate (runs on the headset) | By | `plan.md` phase | Owner | Evidence (fill in when passed) |
|---|---|---|---|---|---|
| 1 | Camera frames + ARCore session | Sat 12:00 | F1 | R1, R3 | |
| 2 | `FrameSource` handoff (R1); ExecuTorch runtime loads a sample `.pte` (R2) | Sat 13:00 | F2 | R1, R2 | |
| 3 | First merge; voice in/out (R4); `ModeController` skeleton | Sat 15:00 | F3 | R4, R1, R5 | |
| 4 | VLM answers on headset (R2) | Sat 16:00 | F4 | R2 | |
| 5 | Text Reader e2e; `NavigationCues` (R3) | Sat 19:00 | F5 | R1, R2, R3, R4 | |
| 6 | Describe + proximity e2e; diagnostics overlay; outlines | Sat 22:00 | F6 | all | |
| 7 | Find the door e2e | Sun 09:00 | F7 | R2, R3, R1 | |
| 8 | **Feature freeze; README numbers** | **Sun 11:00** | F8 | R5, R2 | |
| 9 | Backup video recorded | Sun 12:00 | F9 | R5 | |
| 10 | Pitch rehearsed; submit | Sun 13:00 | F9 | R5 | |

## Merge cadence

- `main` is protected. One feature branch per role: `r1/xr-platform`, `r2/ml-executorch`, `r3/perception`, `r4/voice-ux`, `r5/integration` (sub-branches per task are fine: `r3/proximity`).
- From Sat 15:00, a **15-minute merge cadence**: R5 merges whatever is green, reinstalls on the headset, and runs the gate checklist. Nobody waits on anyone until the first merge.
- A merge is "green" when `./gradlew assembleDebug testDebugUnitTest` passes in CI and `scripts/check_privacy.sh` is clean.
- Nothing merges after the Sun 11:00 freeze except README, video links and the benchmark table.

## Daily timeline (hackathon, Oct 17–18 2026)

| When | What |
|---|---|
| Sat 10:30 | R5: repo, protected `main`, headset + S25 Ultra in hand, ADB working from every laptop, judging time confirmed in writing |
| Sat 11:00 | Assumption checks ([`docs/assumptions.md`](assumptions.md)) done by R1, R2 |
| Sat 12:00 | Gate 1 |
| Sat 13:00 | Gate 2 |
| Sat 14:00 | R4: offline speech recognition verified (or demoted to P1); `scripts/fetch_models.sh` works from a fresh clone |
| Sat 15:00 | Gate 3, merge cadence starts |
| Sat 16:00 | Gate 4 |
| Sat 18:00 | Panels, overlay, anchors landing |
| Sat 19:00 | Gate 5 |
| Sat 21:00 | R4 blindfold test; spatial audio ping |
| Sat 22:00 | Gate 6 |
| Sun 09:00 | Gate 7 |
| Sun 10:00 | Benchmark script run (20 runs per model); P1 test matrix |
| Sun 11:00 | Gate 8, freeze |
| Sun 12:00 | Gate 9 |
| Sun 13:00 | Gate 10, submit |
