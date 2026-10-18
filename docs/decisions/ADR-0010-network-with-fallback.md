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
   off the headset needs its own row below, naming the service, what is sent and the fallback.
5. Still no analytics and no crash reporter (ADR-0003 rule 3, narrowed: HTTP client libraries are allowed).

| Feature | Service | What leaves the headset | On-device fallback | Status |
|---|---|---|---|---|
| Cloud Describe (FR-D1), opt-in | Gemini Developer API, model `gemini-3.6-flash` (Google), REST `streamGenerateContent` over TLS; key from `local.properties` `GEMINI_API_KEY` (never committed; it is inside the APK, so restrict it to the Generative Language API) | Only after the wearer says "cloud on" (or the Describe page's Cloud pill), which is spoken back with Google's name; off at every launch and whenever the headset is taken off. Per Describe: the still, scaled to ≤ 768 px and JPEG-encoded in memory, plus the describe prompt. Never written to storage or logged on the headset. On the free tier Google may use what it receives to improve its products. Read, Find the door and arrival never send anything. | The on-device LFM2.5-VL path (`describeStreaming`), when cloud is off, no key is built in, the headset is offline (`ConnectivityManager`, validated internet), the request fails or returns no text, or the cloud has no text 1 s after the request (`CloudDescribeEngine.HEDGE_AFTER_MS`): the headset starts then and whichever speaks first answers, so the worst case is about 1 s later than headset-only, inside FR-D1's 8 s | accepted 2026-10-18 (Isaac: Gemini REST + local key, opt-in, off by default, voice + panel per session) |

## Alternatives rejected

- Keep ADR-0003 as is: blocks the Geospatial navigation Isaac asked about, which needs Google's VPS.
- Network-first features with a best-effort fallback: a blind user cannot be left without Read because the Wi-Fi
  dropped; the fallback is the rule, not the exception.

## Consequences

- The diagnostics window now prints `ACCESS_NETWORK_STATE, INTERNET` under "Network permission" (`ManifestAudit`
  reports what the APK requests; it is not hidden).
- The privacy gate checks rules 1, 3, 4 and 5; rule 2 is checked in review and by testing each feature offline.
- The airplane-mode demo still works: no feature needs the network to do its job.
