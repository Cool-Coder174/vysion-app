# ADR-0002: XNNPACK CPU is the shipping backend

**Status:** accepted (restates SRS §7). Confirmed by measurement 2026-10-18: the NPU is closed to apps on this headset, so XNNPACK is not only the shipping backend but the only one (see Update).

## Context

The Snapdragon XR2+ Gen 2 has a Hexagon NPU reachable through the ExecuTorch Qualcomm (QNN) delegate, and Qualcomm staff are on site. The rubric rewards working ExecuTorch integration and measured efficiency, not peak speed.

## Decision

Both models ship as XNNPACK (CPU) exports. A QNN export of the VLM is a P2 stretch goal done with on-site help; even if it works, the XNNPACK build stays the demo build unless the QNN build has passed the same 20-run measurement protocol on the headset by the Sun 11:00 freeze.

## Alternatives rejected

- QNN as the primary backend: the QNN SDK setup and the Hexagon export of a multimodal model are each multi-hour risks with no fallback inside the hackathon window.
- Vulkan delegate: not listed in the SRS; no on-site expertise.

## Consequences

- The diagnostics overlay reads the backend name from the runtime (or from export metadata recorded in `ml/models.lock`) and prints it; "XNNPACK" is expected on the demo build.
- `ml/models.lock` carries a `backend` column so a QNN artifact can never be confused with the shipping one.

## Update 2026-10-18: no app route to the NPU

The Context's "Hexagon NPU reachable through the QNN delegate" does not hold for third-party apps on the Galaxy XR
(firmware I610UEU2AZF3, SELinux enforcing). Measured on the headset:

- QNN (`executorch-android-qnn` 1.4.0 / QAIRT 2.37): `backendCreate` succeeds, but `deviceCreate` fails with 14001; FastRPC
  to the cDSP is denied to `untrusted_app` (errno 13) and no DSP-HAL fallback exists. SNPE and FastCV use the same FastRPC.
- No NNAPI driver: no `android.hardware.neuralnetworks` service on the device.
- No AICore (Gemini Nano / ML Kit GenAI on-device): the package is not installed.
- The NPU's only client is Google's vendor service `com.google.xr.hardware.dnn.IDnnService` (headset perception), with
  no app API.

So the QNN stretch goal is closed, not deferred. Every model runs on the CPU through XNNPACK, with int8 where it holds
accuracy (the detector since 2026-10-18; the VLM is 8da4w). The only other accelerator an app can reach is the Adreno
GPU (`executorch-android-vulkan`), not measured, and in ExecuTorch's own benchmarks fp16 Vulkan was slower than int8
XNNPACK for vision transformers. Re-check after each OTA with `ml/qnn-probe/probe.sh`; full logcat in `docs/stack.md`
Corrections, 2026-10-18.
