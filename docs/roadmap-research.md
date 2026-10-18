# Research roadmap: free grounding, faster models, accessibility (2026-10-18)

What can make Vysion XR work better with free data, the headset's own hardware and the ExecuTorch AAR, ordered by
value for blind and low-vision wearers. Every number below was measured on this Galaxy XR, measured from this laptop,
or comes from a cited paper; anything else says "not measured". Sources: three research passes (papers, ExecuTorch
source and Maven, GitHub projects Isaac pointed to), plus probes run with `adb` and `curl`.

## Priorities

| # | What | Why it matters | Effort | Evidence |
|---|---|---|---|---|
| 1 | **Offline voice commands**: Whisper through the AAR's `AsrModule`, Silero VAD to cut the clip, the system recognizer as fallback | Voice never worked on the worn headset (every on-device bind failed, error 10); commit 84c3e15 only adds a fallback to the system recognizer | M | `AsrModule` ships in our 1.4.0 AAR; whisper-tiny int8 1.17 s p50 on a fast phone (Arm); XR2 not measured |
| 2 | ~~**NPU spike** (Qualcomm HTP v69)~~ **closed 2026-10-18: no-go**. A normal app cannot reach the DSP (§2), so the detector and the VLM vision encoder stay on XNNPACK | Describe's vision encoder takes 4.5 s on CPU; the detector 95-160 ms | done | Probe on the headset: `deviceCreate` fails (14001), FastRPC is denied to `untrusted_app` (`docs/stack.md` Corrections 2026-10-18) |
| 3 | **Runtime 1.5.1 + `ImageProcessor`** for detector and OCR preprocessing | Our letterbox and normalize loops are Kotlin; `ImageProcessor` is NEON and reads camera NV12 directly | S | AAR source; `.pte` version checks are new in 1.5, so re-check the sideloaded models load |
| 4 | **"Where am I" on device**: Wi-Fi location + OpenFreeMap tiles decoded on the headset + Overpass entrances/crossings + Wikipedia, worded by Gemini (free tier) or by templates offline | Google's Geospatial API is glasses-only; Maps grounding needs billing | M | One z14 tile = 559 KB, 0.33 s, 3,315 named places, 241 street segments, 165 footpaths, 2,433 house numbers (decoded with a 60-line decoder) |
| 5 | **Heading and position without a compass**: match the ARCore path to OpenStreetMap footways; read street signs through the crosshair and match them to OSM names; saved anchors for repeat routes | No public compass on the headset; Wi-Fi location is 20-50 m | L | Lost! 3.1 m / 1.3° from path shape alone; HMM map matching survives 50 m noise; SignLoc 10/10 routes; CARA 7/7 blind users on a 36 m route |
| 6 | **Detector first, VLM refines, speak only what changed** ("watch" mode, buses) | Slow VLM answers go stale; repeated descriptions overload | M | WorldScribe (UIST 2024); BlindApp's first-time / repeated-scene prompts |
| 7 | **Tracking + time to contact** ("car approaching, about 3 seconds") | Distance alone does not say what is coming | M | IS2AI visual_assistant (Norfair tracking, robust box depth) |
| 8 | **Products and colors at the crosshair**: ML Kit barcodes + Open Food Facts; color by k-means in CIELAB | Daily tasks blind users ask about | S | Open Food Facts answered in 0.70 s (Nutella, allergens milk / nuts / soy), no key |
| 9 | **XAUR gaps**: adjustable gesture timing and sensitivity, mono audio for spatial pings, contrast options, Bluetooth remote / game-controller keys, TalkBack announcements for alerts | W3C XR accessibility requirements 1b, 9c, 15b, 18a, 6a, 2c, 8a | S each | W3C XAUR; BlindApp's TalkBack-safe key list |
| 10 | **The door speaks from where it is** ("door", spatialized) and doors are told apart from glass | Abstract pings are confused; finding the right door is the hardest last-meters task | S | CARA (eLife 2018); acoustic touch (PLOS ONE 2023); Saha et al. (ASSETS 2019) |

## 1. Offline voice commands (ExecuTorch `AsrModule`)

- API (1.4.0 and 1.5.1, identical): `AsrModule(modelPath, tokenizerDir, dataPath?, preprocessorPath?)`, `transcribe(wavPath,
  AsrTranscribeConfig(maxNewTokens, temperature, decoderStartTokenId), AsrCallback)`. The `.pte` needs methods `encoder` and
  `text_decoder`. **Set `decoderStartTokenId = 50258`** for Whisper (the Kotlin default is 0).
- Input is a **16 kHz mono WAV path**; no resampling. The clip is padded to 30 s, so a 1 s command costs the same as a
  long one. Writing that WAV to `cacheDir` and deleting it after the call is new storage: it needs an ADR (audio, not
  frames; never kept) and a `privacy-allow:` marker for the gate.
- Prebuilt: `larryliu0820/whisper-{tiny,small}` XNNPACK repos (`model.pte` + `whisper_preprocessor.pte`), used by the
  official WhisperApp. Software Mansion's Whisper uses other method names (needs plain `Module`).
- VAD: Silero VAD as a plain `Module`, 32 ms chunks (prebuilt on Hugging Face), to start and end the clip without a
  hold. The command grammar stays as is; Whisper's text goes to `CommandGrammar.parse`.
- Not measured: Whisper latency on the XR2+ Gen 2 CPU. Measure before switching the default.

## 2. The NPU (Qualcomm QNN / HTP v69): no-go for apps (measured 2026-10-18)

- **Result**: a third-party app cannot run anything on the Galaxy XR's NPU (I610UEU2AZF3). `ml/qnn-probe/probe.sh`
  builds ExecuTorch v1.4.0's `qnn-htp-test` with the QAIRT 2.37 libraries and goes on past `backendCreate` (which opens
  nothing, so upstream's "SUCCESS" is a false positive here) to `deviceCreate`, which fails with 14001: FastRPC cannot
  open the cDSP from `untrusted_app` (EACCES on `/dev/adsprpc-smd`, which `adb shell` can open; no DSP-HAL fallback on
  the device). Even `adb shell` cannot start an unsigned PD (the cDSP shell in `/vendor/dsp/` is unreadable). Exact
  logcat: `docs/stack.md` Corrections 2026-10-18.
- No other route: SNPE (`libSNPE.so`) and FastCV (`libfastcvopt.so`) are public vendor libraries but use the same
  FastRPC; there is no NNAPI HAL; the NPU's client is Google's `com.google.xr.hardware.dnn.IDnnService`, a vendor
  service with no app API. The only accelerator an app reaches is the Adreno GPU (`libOpenCL.so` is public; QNN GPU or
  `executorch-android-vulkan`), not measured.
- So nothing below was built: no `executorch-android-qnn` dependency, no QNN export, ADR-0002 unchanged. The facts
  stay true and are what a re-run needs if an OTA opens the DSP (re-run `ml/qnn-probe/probe.sh` after each update):
  - Maven: `org.pytorch:executorch-android-qnn` (1.4.0, 1.5.1; arm64; keeps XNNPACK; depends on
    `com.qualcomm.qti:qnn-runtime:2.37.0`, which carries `libQnnHtp`, `libQnnSystem`, `libQnnHtpV69Stub` and
    `libQnnHtpV69Skel`). The `executorch==1.4.0` Linux wheel has the QNN AOT bindings and pins QAIRT `2.37.0.250724`
    (public zip, 1.35 GB), the same build as that AAR: export and runtime match (error 5000 otherwise).
  - App side: bundle the four libraries; `ADSP_LIBRARY_PATH` = native library dir; `extractNativeLibs` through
    `packaging.jniLibs.useLegacyPackaging = true` (AGP 9 refuses the manifest attribute);
    `<uses-native-library libcdsprpc.so>`.
  - v69 gets 16a4w block quantization, not the v73 LLM features (weight sharing, 16-bit KV cache). Targets would be
    YOLOv8n (w8a8; split box and score outputs, an 8-bit tensor cannot hold 0-320 px and 0-1 scores together), the
    LFM2.5-VL vision encoder, PP-OCR detect.
- Vulkan (`executorch-android-vulkan`) is not worth it now: fp16 Vulkan was slower than int8 XNNPACK for ViT in the
  ExecuTorch paper.

## 3. "Where am I", built from free data

Measured from this laptop (San Francisco Civic Center), 2026-10-18:

| Need | Source | Result |
|---|---|---|
| Streets, places, paths, house numbers | OpenFreeMap z14 vector tile (OpenMapTiles schema, no key, no limits; offline up to 3,000 tiles / 50 MB, ≤ 4 parallel, unique User-Agent) | 559 KB, 0.33 s |
| Entrances, crossings, bus stops | Overpass API | 37 s on the public server: cache, never on the critical path |
| Address | Nominatim (1 request/s) or Android's `Geocoder` | 0.65 s |
| Tour-guide facts | Wikipedia GeoSearch | 0.31 s |
| Walking route | FOSSGIS OSRM foot | 0.62 s, 333 m in 7 steps |
| Wording | Gemini free tier, app-restricted key | 1.35 s (Maps grounding: 429 without billing) |

On the headset: no GPS; Wi-Fi location through Play services once Location is on in Settings (it is off); no public
compass (four magnetometers exposed only as a Samsung-private type; the rotation vector is the "game" kind, no north).
Rules: show "© OpenMapTiles, data from OpenStreetMap"; every request reveals the wearer's area, so each service needs an
ADR-0010 row; keep tiles in memory and store only areas the wearer chooses to keep offline (an ADR-0003 amendment).

## 4. What the research says about features we already have

- **Read**: pin text next to its source with adjustable size and contrast (ForeSee); keep OCR on the headset first,
  since cloud OCR at 3-4 s slowed users (Huang et al. 2019); capture a block, never make blind users trace lines
  (HandSight). The crosshair frame and the whole-view fallback follow this.
- **Describe**: users rely on follow-up questions; GPT-4o was still wrong 22% of the time (CHI 2026 diary study), so
  answers must not sound surer than they are. Drop a VLM answer when the view has changed (WorldScribe).
- **Find the door**: egocentric distance ("door 6 meters ahead"), fewer words, hands free (Saha et al.); spoken clock
  directions beat sonification, 90.7% vs 81.7% (acoustic touch); distinct sounds per object.
- **Gestures**: blind users' gestures differ in speed and size from sighted users' (Kane et al. 2011), so thresholds
  need tuning with blind wearers and must be adjustable; confirm every gesture with a sound.
- **Retention**: 3 of 20 OrCam users still used it after years (2022 follow-up). Reliability and onboarding decide use.

## 5. Not possible or not worth it now

- The NPU (Hexagon HTP v69) from an app: FastRPC to the cDSP is denied to `untrusted_app` (§2, measured 2026-10-18).
- Google Geospatial / VPS on the Galaxy XR: the runtime lacks `ANDROID_geospatial`; the DP4 preview is wired XR glasses only.
- Maps grounding without billing on `vysion-app` (429).
- Parakeet ASR: needs a custom AAR with a `ParakeetModule` that no release ships.
- Face identification (one of the repos trained face detection): detecting that a person is there is fine;
  identifying who is not.

## Sources

Papers: Lost! (CVPR 2013, doi 10.1109/CVPR.2013.393) · Newson & Krumm (SIGSPATIAL 2009) · OrienterNet (CVPR 2023,
arXiv 2304.02009) · SignLoc (arXiv 2508.18606, preprint) · NavCog3 (ASSETS 2017, doi 10.1145/3132525.3132535) · Clew
(ASSETS 2019, doi 10.1145/3308561.3353788) · CARA, Liu et al. (eLife 2018, doi 10.7554/eLife.37841) · Soundscape study
(Sci. Rep. 2021, doi 10.1038/s41598-021-87148-4) · ForeSee (ASSETS 2015, doi 10.1145/2700648.2809865) · CueSee
(UbiComp 2016, doi 10.1145/2971648.2971730) · SeeingVR (CHI 2019, doi 10.1145/3290605.3300341) · Huang et al. (PLOS ONE
2019, doi 10.1371/journal.pone.0210630) · HandSight (TACCESS 2016, doi 10.1145/2914793) · OrCam (JAMA Ophthalmol. 2016,
doi 10.1001/jamaophthalmol.2016.1000) · WorldScribe (UIST 2024, arXiv 2408.06627) · CHI 2026 GPT-4o diary study (arXiv
2602.13469) · Saha et al. (ASSETS 2019, doi 10.1145/3308561.3353776) · Acoustic touch (PLOS ONE 2023, doi
10.1371/journal.pone.0290431) · Kane et al. (CHI 2011, doi 10.1145/1978942.1979001) · W3C XAUR (w3.org/TR/xaur).

Code and services: ExecuTorch v1.5.1 source (`extension/android`, `extension/asr`, `backends/qualcomm`) · Maven
`org.pytorch:executorch-android{,-qnn,-vulkan}` · OpenFreeMap (openfreemap.org, github.com/hyperknot/openfreemap) ·
IIT-PAVIS/BlindApp · IS2AI/visual_assistant · XRAccessibility/xraccessibility.github.io · Open Food Facts API.
