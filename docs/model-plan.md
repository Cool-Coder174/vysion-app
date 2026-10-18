# Model plan

Source: SRS §7. Two models ship; three more are stretch goals. **The VLM is the whole product, so its export starts first and is measured on the headset before anything else is built on it.**

| Model | Role | Export path | Backend | Pri | Fallback |
|---|---|---|---|---|---|
| Gemma 4 edge multimodal (~2B) (SRS line; blocked at 1.3.1, D5: the shipped VLM is the Q6 choice below) | Text Reader, Audio Description, door one-shot, arrival check | ExecuTorch `examples/models/gemma4` export, 4-bit weight quantization, Android multimodal runner | XNNPACK CPU | P0 | SmolVLM-500M via the ExecuTorch Qualcomm VLM example if Gemma 4 is too slow or too large on the headset |
| YOLO26-n (COCO) | People and common obstacles during Navigation | ExecuTorch YOLO26 example, int8 | XNNPACK CPU | P0 | Detector off; proximity from depth alone |
| YOLO26-n door fine-tune | Continuous door tracking | Colab fine-tune on a public door dataset, same export | XNNPACK | P2 | VLM one-shot + anchor (the P0 path) |
| Supertonic or Voxtral TTS | ExecuTorch speech out | ExecuTorch TTS examples | XNNPACK | P2 | Android TTS |
| ~~Qualcomm QNN delegate~~ | NPU speedup for the VLM, sponsor relevance | **Closed 2026-10-18: a third-party app cannot reach the NPU on the Galaxy XR** (FastRPC denied, no NNAPI driver, no AICore; ADR-0002 Update) | Hexagon NPU | — | XNNPACK, the only backend |

The verified export commands and the exact example paths that exist in the pinned ExecuTorch release live in [`ml/README.md`](../ml/README.md) and [`docs/stack.md`](stack.md) §3-R2.

## Reality check on the VLM row (research, 2026-10-17; `docs/stack.md` D5, RK-13, plan.md Q6)

The table above is the SRS's. Live verification against ExecuTorch 1.3.1 changed one thing and confirmed another:

- **YOLO26-n: confirmed.** `examples/models/yolo26/export_and_validate.py --model_name yolo26n --backend xnnpack --quantize --video_path <calibration.mp4> --input_dims=[640,640]` produces `yolo26n_int8_xnnpack.pte`; input float32 NCHW `[1,3,640,640]` RGB letterboxed /255; output `[1, N, 6]` rows `[x1, y1, x2, y2, confidence, class_id]` already post-NMS, in letterboxed-input pixels; COCO80 ids (`person` = 0). Loads with `Module.load(path, LOAD_MODE_MMAP)`; `getMethodMetadata("forward").getBackends()` returns `["XnnpackBackend"]` for the overlay.
- **Gemma 4 edge: the export exists, the runner does not.** `examples/models/gemma4/export_gemma4.py` exists and exports one `.pte` with methods `speech_transform, audio_encoder, vision_encoder, text_decoder`, but the Android `LlmModule(MODEL_TYPE_MULTIMODAL, …)` hard-requires `token_embedding` + a 2-input `text_decoder` and fails in `MultimodalPrefiller::load()` with `Error::InvalidArgument` (18). There is no gemma4 code in the Android JNI at 1.3.1, 1.5.1 or main. The SRS's "load with the Android multimodal runner" cannot happen. The SRS fallback "SmolVLM-500M via the Qualcomm VLM example" has no runnable example at 1.3.1 either.

| Option | What | Cost / risk | Status |
|---|---|---|---|
| **C (default)** | **Gemma 3 4B** (`google/gemma-3-4b-it`) exported with optimum-executorch 1.1.0 to an `LlmModule`-compatible `.pte` (`token_embedding` / `text_decoder` / `vision_encoder`) + `tokenizer.json`; loaded with `LlmModuleConfig … modelType(MODEL_TYPE_MULTIMODAL)`; image via `prefillImages`, streaming `onResult` into TTS; exact flags in `ml/README.md` | ~2 h; the HF model is **gated** (accept the license, have a token); separate venv (pins transformers 5.0.0rc1); ~4B params puts memory near the 4 GB budget; image preprocessing numbers (896×896, 256 tokens) UNVERIFIED | decide Sat 11:00 |
| A | Kotlin port of the C++ `Gemma4Runner` over plain `Module`: `execute("vision_encoder", …)` then a decode loop over `execute("text_decoder", input_ids, input_pos, inputs_embeds)`; constants and patch math in `docs/stack.md` §3-R2 | ~1 day; the AAR has no tokenizer class, so the four fixed prompts are pre-tokenized offline and ids decoded via `tokenizer.json`; follow-up questions need real encoding | alternative |
| B | From-source AAR with a custom JNI around `Gemma4Runner` | highest schedule risk | not planned |

**Decision (2026-10-17, Isaac + Claude): three models, all prebuilt or exported in Docker, all through ExecuTorch.** Text Reader reads with PP-OCRv6 (`ocr.pte`, Software Mansion, 1.1 s for a 960×720 sign on the headset, read exactly); Navigation's detector is YOLOv8n Open Images V7 (`ml/export/export_detector_oiv7.sh`, has Door and Stairs; COCO has no door); describe uses LFM2.5-VL-450M (`vlm.pte`, Software Mansion v0.11.0, driven by `ModuleVlmRunner`, the stock runner cannot run it; 7.8 s for a 60-token answer on the headset); Gemma 3 4B stays the fallback. The paragraph below is the earlier option analysis. **Superseded: Decision (`plan.md` Q6, due Sat 11:00, R2 + Isaac): not recorded yet.** Until this line names a path, option C is the default the code and docs are written for, not a decision. When it is taken, replace this paragraph with: `Decision (Sat HH:MM, R2 + Isaac): option C | option A. Evidence: HF license accepted yes/no; first LlmModule load on the S25 Ultra ok/fail (logcat line). Consequence for FR-T1: prefill overload <uint8 | float>, input <W>x<H>, IMAGE_SOFT_TOKENS <n>, chat template <raw | wrapped> (docs/stack.md "Corrections").` and add the same line to the `plan.md` Replan Log. FR-T1's VLM half (R2 P0 "first image-to-text on the headset") starts after it.

Whatever is chosen keeps `vlm.pte` + `vlm-tokenizer.json` as the lockfile destinations and the `InferenceEngine.describe(bitmap, prompt)` contract. Published reference numbers for Gemma 4 E2B on a Galaxy S25 with the C++ runner (ExecuTorch README, not our device): 4.3 GB file, TTFT 3.83 s, 6 tok/s, peak 2600 MB. Measured numbers for whatever we ship go in [`benchmarks.md`](benchmarks.md).

`LlmModule` semantics that shape R2's code (verified at tag v1.3.1): `generate()` blocks (never on Main); `stop()` is safe from any thread (FR-G4); no `LlmModule` call from inside a callback; `seqLen` is an absolute position budget that must exceed the prefilled image tokens, and `maxNewTokens` is not forwarded in 1.3.1, so the contract's `maxNewTokens` becomes `seqLen = prefilledTokens + maxNewTokens`; constructor `temperature = 0f` for greedy read-verbatim; follow-ups on the same frame reuse the KV cache (FR-D2) until `resetContext()`.

## Prompts

Prompts live in one file, `app/src/main/kotlin/com/vysion/xr/inference/PromptLibrary.kt`, and are quoted in the README. They are the only place prompt text may appear.

| Key | Prompt (verbatim) | Used by |
|---|---|---|
| `READ` | Read all text in this image exactly as written, top to bottom, left to right. Output only the text. If there is no text, say: No text found. | FR-T1 |
| `DESCRIBE` | Describe this scene for someone who cannot see it, in at most three sentences. Hazards first, then what is on the left, center, and right. Do not guess. | FR-D1 |
| `DOOR` | Is there a door in this image? Answer with one word: left, center, right, or none. | FR-N3 |
| `ARRIVAL` | Is this door open or closed, and which side is the handle on? One short sentence. | FR-N6 |

P1 variants (`DESCRIBE_BRIEF`, `DESCRIBE_DETAILED`, `TRANSLATE_ES`, `FOLLOW_UP(question)`) are added to the same file with the FR id in the KDoc. R2 tunes wording against 10 real photos from the venue by Sat 19:00; every change is a commit to that one file.

## Asset layout and lockfile

```
app/src/main/assets/models/          # gitignored; filled by scripts/fetch_models.sh
  vlm.pte                            # the VLM chosen at plan.md Q6 (or the fallback), 4-bit, XNNPACK
  vlm-tokenizer.json                 # tokenizer.json for LlmModule(tokenizerPath)
  detector.pte                       # YOLO26-n int8, XNNPACK
ml/models.lock                       # name | url | sha256 | bytes | export-commit — the source of truth
```

On first launch the app copies the `.pte` files from assets to `filesDir/models/` because the ExecuTorch `Module` API loads from a filesystem path (assumption A-11). Model files are the only thing the app ever writes to storage.

## Measurement protocol (SRS §7, verbatim)

20 runs per model on the headset in airplane mode; report median and p90 latency, peak memory, and model file size on the overlay and in the README. **Numbers come from the device, never from a laptop.**

`scripts/benchmark.sh` drives this via ADB and writes `bench/out/<model>-<date>.json`; R2 copies the summary into [`docs/benchmarks.md`](benchmarks.md) and the README table before the Sun 11:00 freeze.

## Fallback decision tree (RK-2, decided by Sat 16:00)

```
Chosen VLM, 4-bit, on headset, 20 runs, READ prompt, 60 max tokens
├─ median ≤ 6 s AND peak PSS ≤ 4 GB  → ship it
├─ median ≤ 8 s                       → ship it for Describe; cap tokens at 40 for Read; note the miss
└─ otherwise                          → export the smaller fallback (same InferenceEngine, same prompts), A/B, ship the faster
```

"The smaller fallback" is a smaller `LlmModule`-compatible optimum-executorch export: SmolVLM-500M has no runnable path at ExecuTorch 1.3.1 (`docs/stack.md` D5; same text as `docs/modules/r2-ml-executorch.md`).
