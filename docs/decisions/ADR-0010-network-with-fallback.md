# ADR-0010: Network allowed for optional features, each with an on-device fallback

**Status:** accepted (2026-10-17, Isaac; amends ADR-0003 rules 1 and 3)

## Context

ADR-0003 kept the app off the network so the demo could run in airplane mode and the diagnostics window could print
"Network permission: none". Isaac's direction on 2026-10-17: "we can use a network, we just need a fallback if we use
features that need network". Candidate network features: Google's Geospatial API (VPS) for outdoor navigation, a
larger cloud model for Describe. Read, Describe and Find the door already run fully on the headset.

## Decision

1. The manifest declares `INTERNET` and `ACCESS_NETWORK_STATE`. No other network permission (Wi-Fi state, changing
   networks) without a new ADR.
2. **Every network feature has an on-device fallback** that runs when the headset is offline, the request fails, or
   it takes too long, within the feature's latency budget. The three core features never wait on the network.
3. Every line that opens a connection carries `network-fallback: <what runs offline>`; `scripts/check_privacy.sh`
   fails without it.
4. Camera frames are still never written to storage or logs (ADR-0003 rule 2 is unchanged). Sending a frame or audio
   off the headset needs its own row below, naming the service, what is sent and the fallback. There are none today.
5. Still no analytics and no crash reporter (ADR-0003 rule 3, narrowed: HTTP client libraries are allowed).

| Feature | Service | What leaves the headset | On-device fallback | Status |
|---|---|---|---|---|
| (none yet) | | | | |

## Alternatives rejected

- Keep ADR-0003 as is: blocks the Geospatial navigation Isaac asked about, which needs Google's VPS.
- Network-first features with a best-effort fallback: a blind user cannot be left without Read because the Wi-Fi
  dropped; the fallback is the rule, not the exception.

## Consequences

- The diagnostics window now prints `ACCESS_NETWORK_STATE, INTERNET` under "Network permission" (`ManifestAudit`
  reports what the APK requests; it is not hidden).
- The privacy gate checks rules 1, 3, 4 and 5; rule 2 is checked in review and by testing each feature offline.
- The airplane-mode demo still works: no feature needs the network to do its job.
