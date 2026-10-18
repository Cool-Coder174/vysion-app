# Requirements

Source of truth: [`docs/srs/srs-extract.md`](srs/srs-extract.md) §1–§4 (transcribed from the SRS PDF in the same folder). This page adds two columns the SRS does not have, **Owner** (the role whose module implements it, see [`docs/modules/`](modules/); when a P0 row names several roles, **(lead)** marks the one that integrates the end-to-end flow and owns the acceptance evidence, and the others deliver their contract by its gate, AGENTS.md §1) and **Phase** (the `plan.md` phase whose gate it lands in). Requirement text is verbatim from the SRS so an FR id can be grepped from code comments back to here.

## Judging rubric (what every requirement must earn)

| Criterion | Pts | What earns it in Vysion XR |
|---|---|---|
| Use case & impact (UC) | 40 | One headset, three real accessibility tasks, hands-free, voice in / spatial audio out |
| Technical implementation (TI) | 25 | Kotlin + Jetpack XR app; ExecuTorch runs the VLM and the detector on live passthrough frames |
| On-device AI & efficiency (OD) | 15 | Airplane-mode demo; live diagnostics panel (ms/frame, memory, backend); no network permission in the manifest |
| Deployment & documentation | 10 | Reproducible README, pinned ExecuTorch version, prebuilt APK + `.pte` models, TalkBack-tested UI |
| Presentation & demonstration | 10 | 90-second live demo with a recorded backup; architecture slide; measured numbers, not claims |

Target: **85+/100**. Anything that earns no rubric points is cut.

## Scope

- **In:** the three modes on Galaxy XR, on-device inference, voice command and TTS output, a diagnostics overlay, docs, and a demo.
- **Out:** phone companion app, cloud anything, account/login, model training from scratch, multi-language beyond English and Spanish, and any safety claim for unassisted blind navigation.

## Priority meaning

- **P0** must work for the demo.
- **P1** is what separates us from a box-drawing project.
- **P2** only after P1 is stable on the headset.

## Design rules (SRS §3, apply to every requirement)

1. Every visual element has an audio twin; the app is fully usable with eyes closed.
2. Voice first, gestures second, panel buttons third. No mode requires looking at a menu.
3. Directions use clock-face bearings and meters ("door, 2 o'clock, 3 meters"); hazards are spoken before descriptions; every utterance is interruptible with "stop".
4. Transcript text is 48 dp or larger at 7:1 contrast; every control carries a TalkBack label.
5. Nothing is spoken that was not measured or seen: no "probably a door".
6. The product sentence, verbatim, in README, pitch and onboarding: **"Vysion XR helps you read, understand, and move through a space. It is not validated for unassisted travel by blind users."**

## Functional requirements

### Global

| ID | Requirement | Pri | Rubric | Owner | Phase |
|---|---|---|---|---|---|
| FR-G1 | App launches on Galaxy XR into passthrough Full Space with one floating control panel and a spoken welcome that lists the three commands | P0 | UC, TI | R1 (lead; shell, panel), R4 (welcome text) | F3 |
| FR-G2 | Voice commands via on-device SpeechRecognizer, started by pinch-and-hold or the panel mic button: read, describe, guide me, find the door, stop, repeat, slower, in Spanish | P0 | UC | R4 (lead; `CommandBus`), R1 (mic button; pinch is P1) | F3 (pinch: F5) |
| FR-G3 | Mode changes are announced by TTS; the active mode is also shown on the panel | P0 | UC | R1 (lead; `ModeController`, panel), R4 (`Cues.kt`) | F3 |
| FR-G4 | All speech output through Android TextToSpeech; "stop" interrupts within 300 ms | P0 | UC | R4 (`Speaker`) | F3 |
| FR-G5 | Diagnostics overlay (toggle): model name, backend, last inference ms, detector fps, app memory MB, "Network permission: none". Every value measured at runtime | P0 | OD | R5 (lead; `diagnostics/`), R2 (timing hooks) | F6 |
| FR-G6 | Spanish output in all three modes (TTS voice + VLM prompt) | P1 | UC | R4 (voice), R2 (prompt) | F6 |
| FR-G7 | ExecuTorch TTS (Supertonic or Voxtral TTS) replaces Android TTS so speech out is also on ExecuTorch | P2 | TI, OD | R4 | stretch |

### Text Reader

| ID | Requirement | Pri | Rubric | Owner | Phase |
|---|---|---|---|---|---|
| FR-T1 | On "read this", capture one world-camera frame, run the VLM with the read-verbatim prompt, and begin speaking within 6 s | P0 | UC, TI | R1 (lead; `FrameSource`, `ModeController`, the 6 s evidence), R2 (`InferenceEngine`, gate 4), R4 (`Speaker`, gate 3) | F5 |
| FR-T2 | Show the transcript in a large-print spatial panel (48 dp+, 7:1 contrast) anchored where the user was looking | P0 | UC | R1 (lead; `StatusWindow` answer text, `platform/ui/HeadsetUi.kt`), R4 (spec) | F5 (anchoring: P1) |
| FR-T3 | "Repeat" replays; "slower" replays at 0.7x rate | P1 | UC | R4 | F6 |
| FR-T4 | "In Spanish" re-prompts the VLM on the same frame for a translation | P1 | UC | R2 (prompt), R4 (locale), R1 (`ModeController` re-prompt on the retained still) | F6 |
| FR-T5 | If head angular velocity is high at capture, say "hold still" and retry once | P1 | UC | R3 (`HeadPoseSource`), R1 | F6 |
| FR-T6 | "Next" captures and reads the next page or sign without re-issuing the command | P2 | UC | R1, R4 | stretch |

### Audio Description

| ID | Requirement | Pri | Rubric | Owner | Phase |
|---|---|---|---|---|---|
| FR-D1 | On "describe", capture one frame, run the VLM with the describe prompt (3 sentences max, hazards first, left to right), begin speaking within 8 s | P0 | UC, TI | R1 (lead), R2, R4 | F6 |
| FR-D2 | A follow-up question ("what's on the table?") runs against the same frame without recapture | P1 | UC | R2 (frame + prefill cache), R4 (`Command.FollowUp`), R1 (`ModeController` re-prompt on the retained still) | F6 |
| FR-D3 | "Brief" and "detailed" switch prompt length | P1 | UC | R2 (`PromptLibrary`), R4 | F6 |
| FR-D4 | Pointing with the index finger at a region (hand tracking) describes only that region | P2 | UC, TI | R3 | stretch |

### Navigation

| ID | Requirement | Pri | Rubric | Owner | Phase |
|---|---|---|---|---|---|
| FR-N1 | Proximity warning: when depth in the forward 60-degree cone falls below 1.0 m, play a rising tone and say "obstacle ahead"; repeat no more than every 2 s | P0 | UC, OD | R3 (lead; `ProximityMonitor`), R4 (earcon + cue) | F6 |
| FR-N2 | Detector runs at 5 fps or better on camera frames for person and common obstacles; detections drawn as outlines in passthrough | P0 | TI, OD | R2 (`detect`), R3 (lead; loop), R1 (`OverlayRenderer`) | F6 |
| FR-N3 | "Find the door": VLM returns the door's horizontal position (left/center/right, or none); the app samples depth along that bearing, drops an anchor there, then speaks bearing and distance every 3 s with a spatial-audio ping from the anchor | P0 | UC, TI | R2 (door prompt), R3 (lead; `AnchorStore`, `NavigationEngine`, spatial audio, the gate 7 evidence), R1 (door vote, highlight, cue speech) | F7 |
| FR-N4 | The door anchor persists when it leaves the field of view; guidance continues from head pose | P1 | TI | R3 | F7 |
| FR-N5 | Plane semantics: say "wall ahead" when a wall plane is within 1.5 m on the heading; say "floor ends" when the floor plane stops within 1.5 m | P1 | UC | R3 | F7 |
| FR-N6 | On arrival (anchor within 1 m), run the VLM once and speak handle side and whether the door is open | P1 | UC | R2 (arrival prompt), R3 | F7 |
| FR-N7 | Multiple doors: "the one on the left" selects among candidates | P2 | UC | R3, R4 | stretch |
| FR-N8 | Fine-tuned door class in the detector replaces the VLM one-shot for door finding | P2 | TI | R2 | stretch |

## Traceability conventions

- Code comments reference requirements by id: `// FR-N1: 2 s debounce` and NFRs by area: `// NFR-latency: stop ≤ 300 ms`.
- A PR that closes a requirement ticks it in the owning module doc under `docs/modules/` and names the FR id in the PR title.
- The acceptance evidence for a P0 requirement is a run on the headset, recorded in the gate row of [`docs/schedule.md`](schedule.md).
