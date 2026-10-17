# ADR-0005: Cross-module handoffs are interfaces in `core/Contracts.kt`

**Status:** accepted (restates SRS §6 and §8)

## Context

Five people own five modules and do not wait on each other until the first merge at Sat 15:00. The SRS says every module exposes a Kotlin `Flow` or a `suspend fun`, no module reaches into another's internals, and the handoff table in §8 is the contract.

## Decision

`app/src/main/kotlin/com/vysion/xr/core/Contracts.kt` holds every interface and data type that crosses a role boundary (`FrameSource`, `InferenceEngine`, `CommandBus`, `Speaker`, `NavigationEngine`, `AnchorStore`, `OverlayRenderer`, `Detection`, `Command`, `NavigationCue`, ...). Modules depend on `core` and on nothing else outside their own package. Each module ships a `Fake*` implementation of the contract it provides so that consumers can build and test before the real one exists.

Changing a signature in `Contracts.kt` is a cross-role change: announce it in the merge channel, R5 merges it alone before anything else, and `docs/contracts.md` is updated in the same commit.

## Alternatives rejected

- Gradle modules per role: stronger isolation, but five Gradle modules plus the XR and ExecuTorch AARs is a configuration-time cost nobody has budgeted for on Saturday morning. Packages plus a review rule are enough for a two-day codebase.
- Letting each role define its own types and adapt at the seams: the adapters are where Sunday-morning integration bugs would live.

## Consequences

- An agent or teammate implementing a module reads `Contracts.kt` and their module doc and does not need anyone else's code.
- `Fake*` implementations are first-class: the S25 Ultra build and the unit tests use them.
