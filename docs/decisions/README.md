# Architecture decision records

One file per decision, numbered, never edited after acceptance (supersede with a new ADR instead). Format: context, decision, alternatives rejected, consequences. ADR-0001 to ADR-0005 restate decisions the SRS already made so their rationale survives the hackathon; ADR-0006 onward were made while building the skeleton.

| # | Decision | Status |
|---|---|---|
| [ADR-0001](ADR-0001-one-vlm-two-prompts.md) | One VLM with different prompts; the detector is the only second model | accepted (SRS §2) |
| [ADR-0002](ADR-0002-xnnpack-ships.md) | XNNPACK CPU is the shipping backend; QNN is a stretch goal, never the demo build | accepted (SRS §7) |
| [ADR-0003](ADR-0003-no-network-no-storage.md) | No INTERNET permission; camera frames never touch storage | accepted (SRS §5) |
| [ADR-0004](ADR-0004-single-inference-engine-mutex.md) | One `InferenceEngine`, a `Mutex`, detector unloaded while the VLM generates | accepted (SRS §6) |
| [ADR-0005](ADR-0005-contracts-as-code.md) | Cross-module handoffs are interfaces in `core/Contracts.kt`; modules talk through `Flow` and `suspend fun` only | accepted (SRS §6, §8) |
| [ADR-0006](ADR-0006-single-apk-runtime-xr-check.md) | One APK for Galaxy XR and S25 Ultra with a runtime XR-capability check, not product flavors | accepted (skeleton) |
| [ADR-0007](ADR-0007-models-fetched-not-committed.md) | Model artifacts are fetched by a script against a lockfile, never committed | accepted (skeleton) |
| [ADR-0008](ADR-0008-no-di-framework.md) | Manual wiring in `AppGraph`; no Hilt/Koin | accepted (skeleton) |
| [ADR-0009](ADR-0009-onboarding-flag.md) | One "onboarding finished" flag is the only preference the app writes (amends ADR-0003) | accepted (designs) |
| [ADR-0010](ADR-0010-network-with-fallback.md) | Network allowed for optional features, each with an on-device fallback (amends ADR-0003) | accepted (2026-10-17) |
