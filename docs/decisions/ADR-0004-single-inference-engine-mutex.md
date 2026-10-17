# ADR-0004: One `InferenceEngine`, a `Mutex`, detector unloaded while the VLM generates

**Status:** accepted (restates SRS §6)

## Context

A ~2B-parameter 4-bit VLM plus runtime is expected to need 1.5 to 2.5 GB of resident memory (to be measured, A-4). The detector loop runs continuously during Navigation. Running both at once risks the 4 GB budget and, on an XR headset sharing memory with the compositor, a mid-demo crash (RK-3).

## Decision

A single `InferenceEngine` owns both models. All inference calls take one `Mutex`. Inside a VLM call the engine unloads the detector first and reloads it after, so peak memory is one model plus the runtime. `InferenceStats.detectorLoaded` and `vlmLoaded` expose the state for the overlay.

## Alternatives rejected

- Two independent engines on two threads: simpler code, but the memory peak is both models and the two roles (R2, R3) collide on who owns the lifecycle.
- Keeping both loaded and relying on the 4 GB budget: nobody has measured it yet; the decision can be reversed in one place if Saturday's numbers show headroom.

## Consequences

- During a VLM call (6 to 8 s) the detector is off, so Navigation outlines pause; `NavigationEngine.pause()` is called by the `ModeController` before any one-shot command and `resume()` after, so the pause is explicit, not accidental.
- Detector reload time counts against the "command to action" latency of the next Navigation cue; it is measured and printed.
- Deadlock rule: nothing inside the `Mutex` may call back into `ModeController`, `Speaker` or any other module; the engine returns data and nothing else.
