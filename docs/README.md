# Documentation index

Read in this order if you are new: [`../README.md`](../README.md) → [`../AGENTS.md`](../AGENTS.md) → [`requirements.md`](requirements.md) → [`contracts.md`](contracts.md) → your module page under [`modules/`](modules/).

| Document | What it holds | Read it when |
|---|---|---|
| [`srs/srs-extract.md`](srs/srs-extract.md) | The SRS, transcribed verbatim and structured (the PDF is beside it) | you need the original wording of anything |
| [`requirements.md`](requirements.md) | Rubric, scope, design rules, FR tables with **owner** and **phase** columns | picking a task; writing an FR id in a comment or PR title |
| [`nfr.md`](nfr.md) | Numeric targets with the mechanism that measures each; the fault model | implementing timing hooks, the overlay, error handling |
| [`architecture.md`](architecture.md) | Four layers, package map, state machine, both end-to-end flows, threading, memory, privacy boundary | before touching `ModeController`, `InferenceEngine` or `NavigationEngine` |
| [`contracts.md`](contracts.md) | The handoff table as Kotlin signatures; who provides, who consumes, sequencing rules | before implementing or calling anything across packages |
| [`stack.md`](stack.md) | Pinned versions with source URLs and verification status; API cheat-sheets per role; device facts | writing any Jetpack XR, CameraX, ExecuTorch, Speech or TTS call |
| [`setup.md`](setup.md) | Fresh clone → running app on the headset (target ≤ 30 min); S25 Ultra; ADB over Wi-Fi; troubleshooting | first build; onboarding a teammate |
| [`model-plan.md`](model-plan.md) | Model table, the four prompts, asset layout, measurement protocol, RK-2 decision tree | R2 work; anything that touches a prompt |
| [`schedule.md`](schedule.md) | Ten gates with evidence columns, merge cadence, cut rule, hour-by-hour timeline | planning the day; R5 merges |
| [`risks.md`](risks.md) | SRS risks with decide-by times, plus skeleton-added risks | when a check fails; deciding a fallback |
| [`assumptions.md`](assumptions.md) | Verify-before-coding checklist with result/evidence columns and the fallback per failure | Saturday morning, before writing module code |
| [`benchmarks.md`](benchmarks.md) | The measured-numbers tables (models, NFRs, soak, test matrix) | after every benchmark run; before the README numbers |
| [`demo.md`](demo.md) | 90 s script, diagnostics fields, pitch outline, submission checklist | Sunday |
| [`decisions/`](decisions/README.md) | ADR-0001..0008: the SRS decisions with rationale, plus skeleton decisions | before proposing a structural change |
| [`modules/r1-xr-platform.md`](modules/r1-xr-platform.md) | R1: app shell, passthrough, camera, panels, overlay, mode state machine | you are R1 |
| [`modules/r2-ml-executorch.md`](modules/r2-ml-executorch.md) | R2: exports, `.pte`, `InferenceEngine`, prompts, measurement | you are R2 |
| [`modules/r3-spatial-perception.md`](modules/r3-spatial-perception.md) | R3: ARCore session, depth, planes, anchors, hands, navigation cues, spatial audio | you are R3 |
| [`modules/r4-voice-accessibility.md`](modules/r4-voice-accessibility.md) | R4: speech in/out, grammar, cues, earcons, panel spec, TalkBack, user testing | you are R4 |
| [`modules/r5-integration.md`](modules/r5-integration.md) | R5: repo, scripts, diagnostics overlay, S25 build, README, benchmarks, video, pitch | you are R5 |

Top-level process files: [`../plan.md`](../plan.md) (roadmap, gates, phase status), [`../executor.md`](../executor.md) (phase specs, empty until `detail F<N>`), [`../History/`](../History/) (immutable plan archive + execution log), [`../CONTRIBUTING.md`](../CONTRIBUTING.md) (branches, definition of done, review checklist).
