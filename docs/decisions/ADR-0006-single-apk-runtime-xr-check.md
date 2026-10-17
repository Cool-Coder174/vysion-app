# ADR-0006: One APK for Galaxy XR and S25 Ultra, runtime XR-capability check

**Status:** accepted (skeleton decision, 2026-10-17)

## Context

SRS R5 task: "S25 Ultra build of the same app with a flat 2D UI, used as the fallback demo and for faster ML iteration" (Sat 20:00). Jetpack XR libraries are designed to run on non-XR devices: spatial content is skipped and 2D content renders normally, and the runtime exposes whether the device has spatial capabilities.

## Decision

A single `app` module and a single APK. `platform.DeviceProfile` reads the runtime XR capability once at startup and the UI picks between the spatial layout (`Subspace` panels, passthrough) and a flat 2D layout (one Compose screen with the same controls, transcript and diagnostics). `FrameSource`, `InferenceEngine`, `CommandBus`, `Speaker` and `diagnostics` are identical on both devices. `perception` (ARCore for Jetpack XR, spatial audio) is `NoOp*` on the phone; Navigation on the phone is reported as unavailable with a spoken notice.

## Alternatives rejected

- Product flavors `headset` / `phone`: two source sets to keep in sync on a two-day project, two APKs for R5 to install, and no benefit the runtime check does not give.
- A separate phone app: duplicates the ML pipeline, which is the thing we want to iterate fastest.

## Consequences

- The XR manifest entries (`uses-feature` for XR, Full Space start mode) must be declared `android:required="false"` where the attribute exists so the APK installs on the S25 Ultra; `docs/stack.md` records the exact attributes.
- Assumption A-12: the capability check returns false on the S25 Ultra. If it does not, fall back to a `BuildConfig` flag set from `-PdeviceProfile=phone`.
