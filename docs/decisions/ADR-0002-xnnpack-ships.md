# ADR-0002: XNNPACK CPU is the shipping backend

**Status:** accepted (restates SRS §7)

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
