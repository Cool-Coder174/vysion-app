# ADR-0008: Manual wiring in `AppGraph`; no dependency-injection framework

**Status:** accepted (skeleton decision, 2026-10-17)

## Context

The app has about eight long-lived objects (`FrameSource`, `InferenceEngine`, `CommandBus`, `Speaker`, `NavigationEngine`, `AnchorStore`, `OverlayRenderer`, `DiagnosticsCollector`) and one controller. Jetpack XR alpha libraries plus ExecuTorch already add build-time risk; KSP/Hilt would add more.

## Decision

`AppGraph` (in `com.vysion.xr`) constructs the real or fake implementation of each contract based on `DeviceProfile` and a `BuildConfig` flag, owns their coroutine scope, and hands them to `ModeController` and the UI. Nothing else constructs a module.

## Alternatives rejected

- Hilt: annotation processing time, a KSP version to pin against Kotlin, and no benefit for eight singletons.
- Koin: lighter, still a dependency and a runtime resolution failure mode.

## Consequences

- Swapping a fake for a real implementation is a one-line change in `AppGraph`.
- Tests construct modules directly.
