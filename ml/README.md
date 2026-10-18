# ml/ — model exports for Vysion XR (R2)

Everything the app loads comes from here: `ml/models.lock` is the source of truth (ADR-0007), `ml/export/*.sh` build the artifacts on a laptop, `scripts/fetch_models.sh` puts them under `app/src/main/assets/models/`, and during development you sideload them instead so the multi-GB models stay out of the dev APK (~65 MB without models, skeleton debug build, 2026-10-17; plan.md Q1).

Pins: the Android AAR is `org.pytorch:executorch-android:1.3.1` (`gradle/libs.versions.toml`, `docs/stack.md` D1) and the Python export **must** use `executorch==1.3.1` (`ml/requirements.txt`). A `.pte` built with another version is not guaranteed to load on the 1.3.1 runtime.

Tags on commands below: **VERIFIED** = the flag set was checked against the ExecuTorch repository at tag v1.3.1 or PyPI metadata (`docs/stack.md` §3-R2 and the PyPI checks dated 2026-10-17 in `ml/requirements.txt`); **UNVERIFIED** = named in `docs/stack.md` without a verifier, or an inference by R2. Nothing in this file has run on a Galaxy XR yet; numbers marked *estimated* are not measurements and never go on the overlay or in the README table (NFR Honesty).

## What runs today (2026-10-17, device-verified on the Galaxy XR)

| Row | File | Source | On the headset |
|---|---|---|---|
| `ocr` + `ocr-charset` | `ocr.pte`, `ocr-charset.json` | PP-OCRv6 small, prebuilt by Software Mansion (Apache-2.0), URL in `models.lock` | loads 0.2 s; read a 960×720 test sign exactly in 1.1 s |
| `detector` | `detector.pte` | YOLOv8n Open Images V7 (Door 164, Stairs 489, people, furniture): `ml/export/export_detector_oiv7.sh` | loads; `[1, 605, 2100]` decoded as columns + Kotlin NMS |
| `detector-int8` | `detector-int8.pte` | the same model, int8: `ml/export/export_detector_oiv7.sh --int8` (§2a) | `detect()` median 54–55 ms vs 93 ms FP32, door recall 8.9% vs 9.6% (`docs/benchmarks.md`); the shipping default since 2026-10-18; `detector` (FP32) is the fallback |
| `vlm` + `vlm-tokenizer` | `vlm.pte`, `vlm-tokenizer.json` | LFM2.5-VL-450M, prebuilt by Software Mansion v0.11.0 (LFM Open License v1.0) | answers on the headset via `ModuleVlmRunner` (`runner: module`): 7.8 s for 60 tokens |
| `asr` + `asr-preprocessor` + `asr-tokenizer` + `vad` | `asr.pte`, `asr-preprocessor.pte`, `asr-tokenizer.json`, `vad.pte` | Whisper-tiny fp32 (prebuilt, larryliu0820), 30 s log-mel preprocessor (`ml/export/export_asr.sh`, url `TBD`), OpenAI's `tokenizer.json`, Silero VAD (prebuilt, younghan-meta); voice commands, ADR-0011 | load 2.2 s; clip end → command 1.5–1.6 s median, English 41/45 and Spanish 28/42 test clips right (`docs/benchmarks.md` "Voice commands") |

Exports run in Docker on any laptop (PyTorch has no Intel-Mac wheels): `docker build -t vysion-ml:1.3.1 ml/export`, then
`ml/export/export_detector_oiv7.sh`. `python /tools/inspect_pte.py <file>` inside that image prints every method's shapes.
Check a model on the headset with nobody wearing it (debug builds): push a test image to `files/smoke/` and
`adb shell am broadcast -a com.vysion.xr.SMOKE -n com.vysion.xr/.diagnostics.ModelSmokeReceiver --es model ocr|vlm|detector --es image sign.png`.

Every model here is XNNPACK (CPU). The NPU is out of reach: an app cannot open the Hexagon cDSP on the Galaxy XR
(`docs/stack.md` Corrections 2026-10-18), so there is no QNN export. `qnn-probe/probe.sh` re-checks that after an OTA.

## 0. The VLM decision you must make first (Sat 11:00, plan.md Q6, RK-13)

The SRS says "Gemma 4 edge multimodal … `examples/models/gemma4` … Android multimodal runner". Research on 2026-10-17 (`docs/stack.md` D5) showed the export exists and works, **but the Android `LlmModule(MODEL_TYPE_MULTIMODAL, …)` cannot load it** (the gemma4 `.pte` has no `token_embedding` method and a 3-input `text_decoder`; `MultimodalPrefiller::load()` fails with `Error::InvalidArgument` = 18; no gemma4 code exists in the Android JNI at 1.3.1, 1.5.1 or main). The SRS fallback "SmolVLM-500M via the Qualcomm VLM example" has no runnable example at v1.3.1 either.

| Path | Model | Export | Android side | Status |
|---|---|---|---|---|
| **C (default)** | `google/gemma-3-4b-it` (gated on HF) | optimum-executorch 1.1.0, §3 | `LlmModule` as in `docs/stack.md` §3-R2; `ExecuTorchInferenceEngine` is written for this | ~2 h; memory near the 4 GB budget; image preprocessing numbers UNVERIFIED |
| A | `google/gemma-4-E2B-it` (ungated) | `examples/models/gemma4/export_gemma4.py`, §3 | NOT loadable by `LlmModule`; needs a Kotlin port of the C++ `Gemma4Runner` over plain `Module` (`docs/stack.md` §3-R2 option A) | ~1 day; the AAR has no tokenizer class |
| B | same as A | same | from-source AAR with custom JNI | not planned |

Whichever is chosen keeps the lockfile dests `vlm.pte` + `vlm-tokenizer.json` and the `InferenceEngine.describe(bitmap, prompt)` contract. Record the decision in the "Decision" line of `docs/model-plan.md` and in the plan.md Replan Log; until that line is filled, the decision has not been made and the option-C snippets are a default, not a fact.

## 1. Python environment (uv)

Python ≥ 3.10, < 3.14 (executorch 1.3.1 metadata, VERIFIED); use 3.12 as `docs/stack.md` does.

```bash
cd <repo>
uv venv --python 3.12 ml/.venv
source ml/.venv/bin/activate
uv pip install -r ml/requirements.txt          # executorch==1.3.1, torch==2.12.0, ultralytics==8.4.14, opencv-python<5
python -c "import executorch, importlib.metadata as m; print(m.version('executorch'))"   # must print 1.3.1

# The example scripts are not in the wheel in a runnable layout: clone the repo at the SAME tag (docs/stack.md §3-R2).
git clone --depth 1 -b v1.3.1 https://github.com/pytorch/executorch ml/.executorch-src   # gitignored (.gitignore: ml/.executorch-src/)
```

The VLM path C needs a **second** venv because `optimum-executorch==1.1.0` pins `transformers==5.0.0rc1` (VERIFIED, PyPI):

```bash
uv venv --python 3.12 ml/.venv-vlm
source ml/.venv-vlm/bin/activate
uv pip install "executorch==1.3.1" "torch==2.12.0" "optimum-executorch==1.1.0" "huggingface_hub>=1.0,<3"
hf auth login                                   # then accept the license at https://huggingface.co/google/gemma-3-4b-it
```

## 2. Detector: YOLO26-n → XNNPACK int8 (VERIFIED, `docs/stack.md` §3-R2)

```bash
source ml/.venv/bin/activate
ml/export/export_detector.sh --executorch-src ml/.executorch-src --video ~/calib.mp4     # any 10+ s indoor clip, 300 frames are sampled
```

What the wrapper runs (from the example directory, output name is fixed by the script):

```bash
cd ml/.executorch-src/examples/models/yolo26
python -m pip install --upgrade-strategy only-if-needed --extra-index-url https://download.pytorch.org/whl/cpu -r requirements.txt   # ultralytics==8.4.14, opencv-python
python export_and_validate.py --model_name yolo26n --backend xnnpack --quantize --video_path calib.mp4 --input_dims=[640,640]   # -> ./yolo26n_int8_xnnpack.pte
```

Facts the Kotlin side relies on (VERIFIED): input float32 NCHW `[1,3,640,640]`, RGB, letterboxed, `/255`; output `[1, N, 6]` rows `[x1, y1, x2, y2, confidence, class_id]` **already post-NMS**, in letterboxed-input pixels; COCO80 ids (`person` = 0, `chair` = 56, `couch` = 57). `--quantize` requires `--video_path`. `YoloPostProcessor.OutputLayout.XYXY_CONF_CLS_ROWS` decodes exactly this; the lockfile row says `input_width/height = 640/640`.

Expected size: *estimated* 5–10 MB (plan.md capacity table), **not measured**. Record the real `bytes` from the script's printout.

## 2a. Detector int8: `detector-int8` (YOLOv8n Open Images V7, the detector the app runs)

```bash
ml/export/export_detector_oiv7.sh            # FP32 detector.pte first (the reference and the fallback)
ml/export/export_detector_oiv7.sh --int8     # -> ml/out/detector-int8.pte, lockfile fields, recall table
```

Ultralytics' `format=executorch` is FP32 only, so `--int8` runs its own PT2E flow in the `vysion-ml:1.3.1` image
(`ml/export/export_detector_int8.py`): `XNNPACKQuantizer` with `get_symmetric_quantization_config(is_per_channel=True)`
(static int8 activations, symmetric per-channel int8 weights), `prepare_pt2e`, calibration, `convert_pt2e`,
`to_edge_transform_and_lower` with `XnnpackPartitioner`. Facts it relies on:

- **The head's decode stays float32.** Detect concatenates boxes (0–320 px) with sigmoid scores (0–1) into one
  `[1, 605, 2100]` tensor; quantized as one tensor, the scores would share a ~1.25 px step and round to 0. The export
  quantizes everything before Detect and Detect's box/class convs (`cv2`, `cv3`), then strips the annotations from
  the ops that run in Detect itself and in its DFL. Output layout and dtype are the FP32 file's, so the app decodes
  both with the same code.
- **`set_module_name` cannot scope this.** torchao's module-name filter must match every node of a pattern, including
  the conv's weight, and a lifted weight has no `nn_module_stack`, so no conv gets annotated (the first try produced
  a 14 MB "int8" file with only cat/add quantized). Global config minus the decode works: 63 of 64 convs int8 (the
  float one is the DFL's fixed 1×1), 3.7 MB.
- **Calibration** = 300 Open Images V7 *validation* images with Navigation classes, 204 of them with a door (all the
  split has), preprocessed exactly like the headset (`ml/export/yolo_app.py` mirrors `YoloPostProcessor.kt`:
  nearest-neighbor letterbox, 114 gray pad, RGB /255). The static config's histogram observers are slow: ~40 min
  for 300 images on an Intel MacBook in Docker.
- **The check** (`compare_detectors.py`) runs both `.pte` files through the ExecuTorch runtime and the app's decode on
  every Open Images V7 *test* image with a door (held out from calibration), and prints recall/precision per label
  and how many FP32 detections int8 reproduces. Results in `docs/benchmarks.md`. `--conf 0.35,0.25,0.15` prints one
  table per floor from the same forward passes; `--b` equal to `--a` scores a single file.
- **Other input sizes**: `--imgsz 416 --out ml/out/size416` (FP32 or `--int8`) exports the same model for a larger
  letterbox; the lockfile row's `input_width/height` is all the app needs. Door recall vs headset time for 320–640:
  `docs/benchmarks.md` "Detector input size vs door recall". Run one Docker job at a time: two XNNPACK jobs on the
  Docker VM's vCPUs slowed each other several-fold (2026-10-18, a 320 forward went from ~0.3 s to over 1 s).

On the headset the engine tries the build's row first (`BuildConfig.DETECTOR`: `detector-int8` by default,
`-Pdetector=detector` for FP32 first), then `detector`: a missing, refused or unloadable `detector-int8.pte` logs `detector-int8 unavailable …` or
`detector load failed …` and loads `detector.pte`. `vysion.latency: detector load ms=… row=…` names the file in use.

## 3. VLM: 4-bit weights, XNNPACK, plus the tokenizer

### Path C (default): Gemma 3 4B through optimum-executorch 1.1.0

```bash
source ml/.venv-vlm/bin/activate
ml/export/export_vlm.sh                          # --path c is the default; --model google/gemma-3-4b-it
```

What the wrapper runs. The flag set is optimum-executorch's own `test_gemma3_image_vision_with_custom_sdpa_kv_cache_8da4w_8we` config (`docs/stack.md` §3-R2 option C: VERIFIED as that config). The `optimum-cli export executorch … --output_dir` invocation form and whether `tokenizer.json` lands in the output dir are **UNVERIFIED** in this session (the wrapper downloads `tokenizer.json` from the HF repo if it is missing):

```bash
optimum-cli export executorch --model google/gemma-3-4b-it --task multimodal-text-to-text --recipe xnnpack \
  --qlinear 8da4w --qlinear_group_size 32 --qlinear_encoder 8da4w,8da8w --qlinear_encoder_group_size 32 \
  --qembedding 8w --qembedding_encoder 8w --use_custom_sdpa --use_custom_kv_cache --output_dir ml/out/gemma3
# -> model.pte (methods token_embedding / text_decoder / vision_encoder) + tokenizer.json
```

4-bit weights: `8da4w` = 4-bit weight, 8-bit dynamic-activation linear quantization (group size 32); embeddings 8-bit (`8w`). Expected size: *estimated* 2.5–3.5 GB for 4B parameters at 4 bit plus 8-bit embeddings and the SigLIP encoder, **not measured**; it will sit near the 4 GB PSS budget (RK-3), so the first thing to do on the headset is `scripts/benchmark.sh --model vlm` and read the peak PSS.

Image input (stack.md: **UNVERIFIED**, HTTP 401 on the model card): SigLIP 896×896, 256 soft tokens, mean = std = 0.5. `FrameSource.captureStill()` (R1) center-crops to `ModelInfo.inputWidth/Height`; put the verified numbers into the lockfile row (`input_width`, `input_height`) and `ExecuTorchInferenceEngine.IMAGE_SOFT_TOKENS` once the first prefill reports `prompt_tokens` in `onStats`.

Which `prefillImages` overload (**UNVERIFIED**; nobody knows whether the optimum-executorch `vision_encoder` normalizes internally): start with `prefillImages(IntArray uint8 CHW, 896, 896, 3)`, which is what `ExecuTorchInferenceEngine.prefillLocked` documents. If a printed sign comes back as garbage, switch to `prefillImages(FloatArray CHW, 896, 896, 3)` with `((v / 255) - 0.5) / 0.5` per channel (the mean = std = 0.5 above). Accept the overload when `onStats` reports `prompt_tokens` = `IMAGE_SOFT_TOKENS` + the prompt's tokens and the sign is read correctly; record it as a row in `docs/stack.md` "Corrections found at build time".

Prompt format (**UNVERIFIED**): nobody checked whether `LlmModule`'s multimodal runner applies Gemma 3's chat template (`<start_of_turn>user\n … <end_of_turn>\n<start_of_turn>model\n`) or sends the prompt raw. On the first answer, run the same printed sign twice: (a) raw, `prefillImages(…)` then `generate(PromptLibrary.READ, seqLen, cb, false)`; (b) wrapped, `prefillPrompt("<start_of_turn>user\n")`, `prefillImages(…)`, then `generate(READ + "<end_of_turn>\n<start_of_turn>model\n", …)`, so the image sits inside the user turn. Keep the variant that answers with only the sign's text. The wrapping goes in `ExecuTorchInferenceEngine.prefillLocked` / `generateLocked`, never in `PromptLibrary.kt` (ADR-0001); a follow-up on the same frame (FR-D2) then continues with `<end_of_turn>\n<start_of_turn>user\n<question><end_of_turn>\n<start_of_turn>model\n`. Record the result in `docs/stack.md` "Corrections" (the §3-R2 row already there).

### Path A: Gemma 4 E2B through `examples/models/gemma4` (VERIFIED export flags; NOT loadable by `LlmModule`)

```bash
source ml/.venv-vlm/bin/activate && uv pip install "transformers>=5.2.0"     # the script needs it; the repo pins 5.0.0rc1
ml/export/export_vlm.sh --path a --executorch-src ml/.executorch-src
```

```bash
python examples/models/gemma4/export_gemma4.py --checkpoint_path ./gemma-4-E2B-it --variant e2b --no-audio \
  --quantize 8da4w+emb8 --vision_quantize 8da8w --max_seq_len 1024 --max_patches 1260 --output_path gemma4_e2b_vision.pte
```

`--quantize 8da4w+emb8` is the 4-bit weight option (default); `--max_patches 1260` = 140 image soft tokens (its help text wrongly says 2520); never pass `--tied_embedding` (needs C++ TorchAO kernels). Published numbers for this export on a Galaxy S25 with the C++ runner (ExecuTorch README, **not our device, not our runner**): 4.3 GB file, load 0.8 s, image encode 2.73 s, TTFT 3.83 s, 6 tok/s, peak 2600 MB. Only useful with the option-A Kotlin runner.

### Tokenizer artifact

Both paths ship a Hugging Face **`tokenizer.json`** (Gemma 4 E2B has no `tokenizer.model`; `docs/stack.md` §3-R2). The lockfile dest is therefore **`vlm-tokenizer.json`** (changed from the skeleton's `.model`), and `LlmModuleConfig.tokenizerPath` points at it. The wrapper copies it to `ml/out/vlm-tokenizer.json`.

## 4. Sanity-run a `.pte` on the laptop (UNVERIFIED API; `docs/stack.md` lists no Python runtime snippet)

The cheapest check that matters is the **method list**, because D5 is exactly a missing-method failure:

```python
# source ml/.venv/bin/activate; python
from executorch.runtime import Runtime          # executorch Python runtime; name per the ExecuTorch docs, UNVERIFIED here
import torch
rt = Runtime.get()
prog = rt.load_program("ml/out/vlm.pte")
print(prog.method_names)                        # path C must show: token_embedding, text_decoder, vision_encoder
det = rt.load_program("ml/out/detector.pte").load_method("forward")
out = det.execute([torch.rand(1, 3, 640, 640)])
print(out[0].shape)                             # torch.Size([1, N, 6])
```

If `method_names` lacks `token_embedding`, the Android `LlmModule` will refuse the file (D5): stop and fix the export before pushing anything to the headset. The YOLO example also validates its own export against the video (`export_and_validate.py` prints mAP-style numbers for the int8 model); that is laptop accuracy, not a latency number.

## 5. Sideload during development (plan.md Q1, `docs/stack.md` Q20 / A-15)

**Step 0: a lockfile on the device.** `ModelCatalog` resolves nothing without one: it reads `assets/models/models.lock` inside the APK (gitignored, so a fresh clone has none) and falls back to `/sdcard/Android/data/com.vysion.xr/files/models/models.lock`. Either run `scripts/fetch_models.sh` once (it copies the lockfile into assets before exiting 2 on `TBD` rows), then `./gradlew assembleDebug` and install; or use `scripts/push_models.sh`, which pushes `models.lock` next to the models. A plain `adb push` of the `.pte` files alone is not enough.

The engine resolves each lockfile `dest` in this order: `getExternalFilesDir("models")/<dest>` → `filesDir/models/<dest>` (copied from `assets/models/<dest>` on first launch, verified against the lockfile, `.part` then rename). Nothing else is ever written to storage (ADR-0003). A sideloaded file whose size or sha256 differs from a non-`TBD` lockfile row (for example an `adb push` still running) is refused and logged under `vysion.security` (plan.md Security, fail closed); the sha256 runs once per file per process.

```bash
scripts/push_models.sh                     # pushes ml/out/* and ml/models.lock; or by hand:
adb push ml/out/detector.pte /sdcard/Android/data/com.vysion.xr/files/models/
adb push ml/out/vlm.pte ml/out/vlm-tokenizer.json ml/models.lock /sdcard/Android/data/com.vysion.xr/files/models/
adb shell ls -la /sdcard/Android/data/com.vysion.xr/files/models/
adb logcat -s vysion vysion.latency      # expect: "vlm → /storage/emulated/0/Android/data/com.vysion.xr/files/models/vlm.pte (N bytes)"
```

The directory exists once the app has been installed and launched at least once. The multi-GB VLM is **never** bundled into the dev APK (a Wi-Fi reinstall would take minutes and break the 15-minute merge cadence); bundle via `scripts/fetch_models.sh` only for the release tag.

## 6. Filling `ml/models.lock`

Each export script ends with a block formatted for the lockfile. For every artifact:

1. `sha256`: paste the printed value (also what `scripts/fetch_models.sh` prints as "Computed:" for a `TBD` row).
2. `bytes`: paste the printed size; the app refuses a copied file whose size differs (`ModelCatalog`).
3. `url`: R5 creates the release `v0.1.0-hackathon` (`docs/modules/r5-integration.md` "Release-tag procedure"); R2 only uploads the assets and points at them:
   ```bash
   gh release upload v0.1.0-hackathon ml/out/vlm.pte ml/out/vlm-tokenizer.json ml/out/detector.pte
   # url: https://github.com/Cool-Coder174/vysion-app/releases/download/v0.1.0-hackathon/<dest>
   ```
4. `quantization`, `backend`, `input_width`, `input_height`, `source_model`: what the script printed; the overlay prints these verbatim (FR-G5).
5. Run `scripts/fetch_models.sh` (or `--from ml/out`) until it prints `✓ … verified` for every row; it also copies the lockfile to `assets/models/models.lock`, which is what the app reads at runtime.

Hugging Face gated repos (RK-11): never put a tokenized HF URL in the lockfile; the release assets are the download path.

## 7. Measure, then write the number down

```bash
adb shell cmd connectivity airplane-mode enable
scripts/benchmark.sh --model detector --runs 20
scripts/benchmark.sh --model vlm --runs 20
scripts/benchmark.sh --soak 600
```

Rows go to `docs/benchmarks.md` and the README table; the RK-2 decision tree is in `docs/modules/r2-ml-executorch.md` and `docs/model-plan.md`.
