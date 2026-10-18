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
| Cloud Describe (FR-D1), opt-in | Gemini Developer API, model `gemini-3.6-flash` (Google), REST `streamGenerateContent` over TLS; key `GEMINI_API_KEY` from the gitignored `local.properties` (never committed; project `vysion-app`, no billing = free tier; restricted to the Generative Language API and to `com.vysion.xr` + its signing SHA-1, sent as `X-Android-Package` / `X-Android-Cert`; `docs/setup.md` §6b) | Only after the wearer says "cloud on" (or the Describe page's Cloud pill), which is spoken back with Google's name; off at every launch and whenever the headset is taken off. Per Describe: the still, scaled to ≤ 768 px and JPEG-encoded in memory, plus the describe prompt. Never written to storage or logged on the headset. On the free tier Google may use what it receives to improve its products. Read, Find the door and arrival never send anything. | The on-device LFM2.5-VL path (`describeStreaming`), when cloud is off, no key is built in, the headset is offline (`ConnectivityManager`, validated internet), the request fails or returns no text, or the cloud has no text 1 s after the request (`CloudDescribeEngine.HEDGE_AFTER_MS`): the headset starts then and whichever speaks first answers, so the worst case is about 1 s later than headset-only (headset-only measured worn: 8.7–11.1 s, over FR-D1's 8 s; `docs/benchmarks.md`) | accepted 2026-10-18 (Isaac: Gemini REST + local key, opt-in, off by default, voice + panel per session). On the headset, unworn smoke: the cloud answered in 0.9–1.5 s and a slow cold request fell back to the headset. Worn with cloud on: Gemini refused every request with HTTP 429 (free-tier quota, shared with Where am I) and the headset answered at once, capture → first word 8.71 and 10.12 s, so the worn cloud number is not measured. Offline skipped for the deadline (Isaac). `docs/benchmarks.md` "Cloud Describe" |
| "Where am I" map (FR-N9) | OpenFreeMap vector tiles (`https://tiles.openfreemap.org/planet/latest/14/{x}/{y}.pbf`, OpenMapTiles schema; no key, no account; HTTPS; `User-Agent: VysionXR/<version> (Android XR accessibility app)`; at most 4 requests at once, as OpenFreeMap asks). The position comes from Android network location (Google Play services' fused / network provider: the OS sends Wi-Fi scans to Google under the system Location setting; the Galaxy XR has no GPS) | Per "where am I", after the wearer allowed precise location (asked only the first time they ask, after a spoken explanation that names OpenFreeMap and Google): the address of the z14 tile around them, i.e. an area about 2 km across (1.9 km at San Francisco's latitude), plus up to 3 neighboring tiles when the fix is within 300 m of an edge; the request's IP address and User-Agent. No coordinates and no identifier. Tiles are decoded on the headset and kept in memory (the last 16), never written to storage; the fix lives only in the call and is never stored or logged (`scripts/check_privacy.sh` §7) | Tiles already in memory answer offline (the decoder and the street / cross-street / place finder run on the headset); with none, the wearer hears that the map needs a connection (`WhereAmI.NoMap`), and Location off, a refused permission and no fix each get one sentence with the way out. Read, Describe and Find the door never use it | accepted 2026-10-18 (Isaac: "a free equivalent of Maps grounding", OpenFreeMap); device numbers in `docs/benchmarks.md` |
| "Where am I" wording (FR-N9) | Gemini Developer API, the Cloud Describe key and model (`gemini-3.6-flash`), a text-only `streamGenerateContent` | The facts found on the headset: the nearest street, its cross streets and up to three nearest named places, with straight-line meters, and the fix's accuracy; no coordinates, no photo. Not behind "cloud on" (no photo leaves; the street names are what the tile request already revealed, at street level), but named in the spoken explanation before the location dialog. On the free tier Google may use what it receives to improve its products | Template sentences from `voice/Cues.kt` (`Cues.surroundings`) when no key is built in, the headset is offline (`ConnectivityManager`, validated internet), the request fails, or no sentence arrives within 4 s (`ModeController.WORDING_FIRST_SENTENCE_MS`) | accepted 2026-10-18 (with the map row) |
| Voice commands, when the on-device recognizer will not bind | Speech Services by Google (the system's default recognizer), `EXTRA_PREFER_OFFLINE` | Microphone audio while the wearer talks, only if its offline language pack is missing | Whisper on the headset comes first (ADR-0011); then the on-device recognizer; with none, gestures and panel buttons, and the app says so | fallback only since ADR-0011 (2026-10-18: every on-device bind failed on the worn headset with ERROR_TOO_MANY_REQUESTS) |

FR-N9 also adds `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` (location, not network permissions; both are
needed for the precise-location dialog). They are requested only when "where am I" is first asked, never at launch.

## Alternatives rejected

- Keep ADR-0003 as is: blocks the Geospatial navigation Isaac asked about, which needs Google's VPS.
- Network-first features with a best-effort fallback: a blind user cannot be left without Read because the Wi-Fi
  dropped; the fallback is the rule, not the exception.

## Consequences

- The diagnostics window now prints `ACCESS_NETWORK_STATE, INTERNET` under "Network permission" (`ManifestAudit`
  reports what the APK requests; it is not hidden).
- The privacy gate checks rules 1, 3, 4 and 5; rule 2 is checked in review and by testing each feature offline.
- The airplane-mode demo still works: no feature needs the network to do its job.
