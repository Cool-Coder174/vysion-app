# Risks and mitigations

Source: SRS §10. The two risks that can sink the project are **camera access** and **VLM speed**, and both get answered in the first six hours. Everything else has a cheaper fallback already in the plan. The "Decide by" column is when the fallback is taken if the check fails; after that time nobody argues, the fallback ships.

| # | Risk | Likelihood | Impact | Mitigation | Owner | Decide by |
|---|---|---|---|---|---|---|
| RK-1 | World-camera frames not available to our app on this Galaxy XR build (permission denied, blank stream, unsupported format) | Medium | Fatal for Read and Describe on the headset | Test in hour one. Fallback: run Read and Describe on the S25 Ultra from the same track; keep Navigation on the headset using ARCore depth and planes only | R1 | Sat 12:00 |
| RK-2 | Gemma 4 edge too slow or too large on the XR2+ Gen 2 | Medium | Misses 6 s / 8 s targets | Measure by Sat 16:00; switch to SmolVLM-500M; cap output tokens at 60; prefill once, answer follow-ups from cache | R2 | Sat 16:00 |
| RK-3 | Memory pressure with VLM and detector loaded | Medium | Crashes mid-demo | Unload the detector during VLM calls; 4 GB budget on the overlay; test 10 minutes continuous | R2, R5 | Sat 22:00 |
| RK-4 | Offline speech recognition unavailable on the headset | Medium | Voice commands need network | Pinch-to-talk plus panel buttons are P0; recognition becomes P1; never demo with Wi-Fi on | R4 | Sat 14:00 |
| RK-5 | ARCore depth noisy or missing | Low | Proximity cue unreliable | Fall back to plane hit tests and detector box height as a distance proxy; only speak distances we trust | R3 | Sat 15:00 |
| RK-6 | Door one-shot returns the wrong side | Medium | Guides the user to a wall | Ask the VLM twice on two frames and require agreement; otherwise say "I can't find a door, turn slowly" | R2, R3 | Sun 09:00 |
| RK-7 | Integration collapses Sunday morning | Medium | Nothing demos | 15-minute merge cadence from Sat 15:00; R5 owns `main`; freeze at Sun 11:00 is not negotiable | R5 | continuous |
| RK-8 | Headset tracking loss, thermal throttle, or battery during judging | Medium | Live demo fails | Recorded backup video; headset at 100% and cooled before our slot; demo under 90 s; S25 Ultra as the second device | R5 | Sun 12:00 |
| RK-9 | Overclaiming accessibility | Low | Judges push back on the 40-point line | The assistive-not-safety sentence appears in the pitch, README, and app onboarding | R4 | continuous |

## Risks added by the skeleton (not in the SRS)

| # | Risk | Likelihood | Impact | Mitigation | Owner |
|---|---|---|---|---|---|
| RK-10 | A Jetpack XR alpha or ExecuTorch API named in the stubs does not match the pinned artifact (the skeleton was written from docs, not from a build on the headset) | Medium | First-hour compile errors | Pinned versions live only in `gradle/libs.versions.toml`; stubs have `TODO()` bodies so a signature fix is local; `docs/stack.md` records which API claims were verified against which source | R5 |
| RK-11 | Model download URLs in `ml/models.lock` change or need auth (Hugging Face gated repos) | Medium | `fetch_models.sh` fails from a fresh clone | Lockfile carries sha256 + size; script supports a local `--from <dir>` copy path; prebuilt `.pte` attached to the release tag | R2, R5 |
| RK-12 | Two roles edit `core/Contracts.kt` at once | Low | Merge conflict on the one shared file | Contract changes are announced before the edit and merged by R5 first, alone | R5 |
| RK-13 | **The SRS VLM path does not exist as written**: the `examples/models/gemma4` export cannot be loaded by the Android `LlmModule` multimodal runner at ExecuTorch 1.3.1 (nor 1.5.1 or main); the SRS's SmolVLM fallback has no runnable example at 1.3.1 either (`docs/stack.md` D5) | Certain (verified 2026-10-17) | No VLM on the headset = no product | Decide by Sat 11:00 (plan.md Q6): **option C** Gemma 3 4B via optimum-executorch (`LlmModule`-compatible, gated HF model, ~2 h, memory near the 4 GB budget) by default; **option A** Kotlin port of `Gemma4Runner` over plain `Module` (~1 day) as the alternative; both recipes in `ml/README.md`; the `InferenceEngine` contract is the same either way | R2, Isaac | Sat 11:00 |
