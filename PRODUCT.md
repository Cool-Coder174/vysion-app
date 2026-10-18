# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

People with low vision, and anyone in a situationally impaired moment (an unfamiliar building, poor light, hands full), wearing a Samsung Galaxy XR headset. Secondary users: Spanish/English learners who want signs and menus read and translated. They use the app hands-free, often with their eyes closed or on the room rather than on a menu, and frequently while also using a cane or guide dog. Source: SRS §3 (`docs/srs/srs-extract.md`).

## Product Purpose

Vysion XR reads text, describes a scene, and guides the wearer to a door, using voice in and speech plus spatial audio out, with every model running on the headset through ExecuTorch. Success is the 90-second demo in airplane mode with measured numbers on screen, and an app a stranger can use with eyes closed.

## Positioning

Everything runs on the headset: nothing is sent unless the wearer says "cloud on" for Describe (ADR-0010), camera frames never saved, measured numbers shown live. A headset (not a phone) because hands stay free, the camera points where the head points, spatial audio can place a cue in the target's direction, and anchors keep a target in memory when it leaves view.

## Operating Context

- Galaxy XR (Android 14 / API 34) in Full Space with passthrough; panels float in the real room.
- A noisy hackathon venue and its hallways, doors, signs and cluttered tables; the live demo has one wearer and a separate speaker.
- Galaxy S25 Ultra runs the same APK with a flat 2D layout as the fallback demo device.
- Input order: voice first, gestures second, panel buttons third. No controllers, no haptics on Galaxy XR: every gesture is confirmed by a sound and a panel change.

## Capabilities and Constraints

- Three modes: Text Reader ("read this"), Audio Description ("describe" / "what's here?"), Navigation ("guide me" / "find the door"). Commands: stop, repeat, slower, in Spanish, next, brief, detailed.
- Every visual element has an audio twin; nothing is spoken that was not measured or seen; hazards first; clock-face bearings; every utterance interruptible by "stop" within 300 ms.
- Spoken strings live only in `voice/Cues.kt`, prompts only in `inference/PromptLibrary.kt`; every displayed number is measured on the device or shows "not measured".
- The app writes only model files and a single "onboarding finished" flag (ADR-0003, ADR-0009). `INTERNET` is used only by opt-in cloud Describe, which falls back to the headset (ADR-0010).
- Typeface: the device's system font; no bundled fonts (decided 2026-10-17).
- English and Spanish only.

## Brand Commitments

- Name: Vysion XR. Product sentence, verbatim in onboarding, README and pitch: "Vysion XR helps you read, understand, and move through a space. It is not validated for unassisted travel by blind users."
- Binding design: Michelle Bui's "Vysion XR designs" (23 pages, October 2026): onboarding (7 cards), headset demo flow (13 states), hand gestures, and the gesture-first phone app. The source PDF is outside the repo (its pages show bystanders, so they are not committed); `DESIGN.md` records the system once the design build passes its finish review.
- Voice of the copy: first person, short, calm, direct ("Hi, I'm Vysion.", "I'm a helper, not a guide.", "Say it again.").

## Evidence on Hand

- The SRS (`docs/srs/`), the measured-numbers template (`docs/benchmarks.md`, all "not measured" until device runs), and the first device run on the Galaxy XR (`docs/assumptions.md` A-2). No measured latency numbers exist yet; none may be invented.

## Product Principles

1. Voice first: the app is fully usable with eyes closed; panels are the visual twin of what is spoken.
2. Honest by construction: say only what was seen or measured, show "not measured" rather than a number nobody measured.
3. Stop always wins: any utterance, any state, within 300 ms, by voice, palm or button.
4. A helper, not a guide: never imply safety for unassisted travel; keep the cane in the copy.
5. Nothing leaves the headset unless the wearer turns cloud Describe on, and the voice says so when they do.

## Accessibility & Inclusion

Low-vision users first: transcript text 48 dp or larger at 7:1 contrast, every control labeled for TalkBack, every gesture with a spoken and a tappable equivalent, Android XR targets of at least 56 dp. The phone app has a TalkBack mode where every gesture becomes a labeled control.
