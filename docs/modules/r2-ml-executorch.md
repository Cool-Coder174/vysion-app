# R2 ML / ExecuTorch

## Purpose

R2 turns two models into `.pte` files that run on the headset and wraps them in the one `InferenceEngine` every other role calls. SRS §8: **Owns** "Model exports, `.pte` files, `InferenceEngine`, prompts, latency and memory measurement". **Hands off** "`InferenceEngine` with `describe(bitmap, prompt)` and `detect(bitmap)` running on the headset", due **Sat 16:00** (gate 4). The VLM is the whole product (ADR-0001), so its export starts first and is measured on the device before anything is built on it; and because the SRS's Gemma 4 + "Android multimodal runner" path cannot load at ExecuTorch 1.3.1 (`docs/stack.md` D5, RK-13), R2's first act on Saturday is the VLM-path decision (plan.md Q6).

## Files you own

| Path | What it is | Status |
|---|---|---|
| `app/src/main/kotlin/com/vysion/xr/inference/PromptLibrary.kt` | The four SRS §7 prompts verbatim + P1 variants, `followUp()`, `forLocale()`, `parseDoorAnswer()` allowlist | pure |
| `app/src/main/kotlin/com/vysion/xr/inference/ModelCatalog.kt` | Reads `assets/models/models.lock`; resolves external-files-dir → `filesDir/models` → verified copy from assets (`.part` + sha256/bytes + rename); builds `ModelInfo`; contains the pure lockfile JSON reader | pure (parse/resolve/copy) + device (assets, dirs) |
| `app/src/main/kotlin/com/vysion/xr/inference/ExecuTorchInferenceEngine.kt` | The real `InferenceEngine`: one `Mutex`, detector unloaded during VLM, 15 s watchdog with `stop()`, timing/stats, lazy detector reload, FR-D2 prefill cache | stub (every ExecuTorch call); the structure around it is real |
| `app/src/main/kotlin/com/vysion/xr/inference/YoloPostProcessor.kt` | Letterbox geometry, ARGB → float CHW packing, YOLO output decode (two documented layouts), class-wise NMS, un-letterbox to `NormalizedBox`, COCO80 names | pure |
| `app/src/main/kotlin/com/vysion/xr/inference/LatencyStats.kt` | Ring buffer with nearest-rank median/p90 (`LatencyStats`) and the 5 s sliding fps window (`FpsWindow`) | pure |
| `app/src/main/kotlin/com/vysion/xr/inference/FakeInferenceEngine.kt` | Fully working fake: canned answers per prompt, two boxes, stats, `failNextDescribe`, cancellable `hangNextDescribe`, streaming | fake |
| `app/src/test/kotlin/com/vysion/xr/inference/*Test.kt` | JUnit 4: `PromptLibraryTest`, `LatencyStatsTest`, `YoloPostProcessorTest`, `ModelCatalogTest`, `FakeInferenceEngineTest` (also covers `seqLenBudget`) | pure |
| `ml/README.md`, `ml/requirements.txt`, `ml/export/export_vlm.sh`, `ml/export/export_detector.sh` | Export recipes (uv env, pins, both VLM paths, YOLO26-n int8, laptop sanity run, sideload, lockfile filling) | docs/scripts |
| `ml/models.lock` (+ its copy `app/src/main/assets/models/models.lock`) | Artifact rows; R2 fills `url`/`sha256`/`bytes`/dims after each export | data |
| `docs/benchmarks.md` rows for the two models | Measured numbers only | docs |

## Contracts you provide / consume

| Direction | Kotlin signature (from `core/Contracts.kt` / `AppGraph.kt`) | Other side |
|---|---|---|
| provide | `interface InferenceEngine { val vlmInfo: StateFlow<ModelInfo?>; val detectorInfo: StateFlow<ModelInfo?>; val stats: StateFlow<InferenceStats>; suspend fun warmUp(); suspend fun describe(bitmap: Bitmap, prompt: String, maxNewTokens: Int = 60): String; fun describeStreaming(bitmap, prompt, maxNewTokens): Flow<String>; suspend fun detect(bitmap: Bitmap): List<Detection>; suspend fun release() }` | R1 `ModeController` (describe/warmUp/release), R3 `NavigationEngine` (detect), R5 `DiagnosticsCollector` (stats, infos) |
| provide | `class ExecuTorchInferenceEngine(app: Application, catalog: ModelCatalog, scope: CoroutineScope) : InferenceEngine` | `AppGraph` (wired when `USE_FAKES` is false) |
| provide | `class FakeInferenceEngine(describeDelayMs: Long = 50, detectDelayMs: Long = 20) : InferenceEngine` + `failNextDescribe`, `hangNextDescribe`, `cannedAnswers`, `cannedDetections` | `AppGraph` (`-PuseFakes=true`), R1 tests, R5 overlay |
| provide | `class ModelCatalog(app: Application)` with `entries` (assets lockfile, else the sideloaded `models.lock`), `resolve(name): File` (a sideload that fails its non-`TBD` size/sha256 is refused, `vysion.security`), `modelInfo(name[, file]): ModelInfo`, `verify(name): Boolean` | `AppGraph`, R5 `BenchmarkReceiver` (file size, backend) |
| provide | `object PromptLibrary { READ, DESCRIBE, DOOR, ARRIVAL, DESCRIBE_BRIEF, DESCRIBE_DETAILED, TRANSLATE_ES; fun followUp(q): String; fun forLocale(prompt, Locale): String; fun parseDoorAnswer(text): HorizontalBearing }` and `object LibraryPromptSet : PromptSet` (incl. `forLocale`). The DOOR answer is parsed by R1's `platform.DoorVote` (exactly one of left/center/right/none, no negation, else no door; RK-6); `parseDoorAnswer` is reference only, not used by the app | R1 `ModeController` through `AppGraph.prompts` (prompt choice, FR-G6 locale) |
| consume | `data class Detection(label, confidence, box: NormalizedBox)`, `NormalizedBox(left, top, right, bottom)` 0..1 | produced for R1 overlay / R3 |
| consume | `InferenceStats(vlmLoaded, detectorLoaded, lastVlmMs, medianVlmMs, p90VlmMs, vlmRuns, lastDetectMs, detectorFps, lastDetectAtNanos)` (reset `detectorFps` to 0 when the detector unloads; stamp `lastDetectAtNanos` in `detect`), `ModelInfo(name, quantization, backend, fileSizeBytes, inputWidth, inputHeight)`, `InferenceException`, `HorizontalBearing` | R5 overlay renders `null` as "not measured" |
| consume | `Bitmap` from `FrameSource.captureStill()` (R1): already center-cropped to `vlmInfo.inputWidth/Height` (`AppGraph` sets `CameraFrameSource.stillEdge` from `vlmInfo`, so publish `vlmInfo` only once the VLM loaded); owned and recycled by the caller (INV-9); the engine only reads it | R1 |

Sequencing rules that live between the contracts (`docs/contracts.md`): R1 calls `NavigationEngine.pause()` before any `describe` and `resume()` after; nothing inside the engine calls another module (INV-8); `Stop` is handled outside the mutex by cancelling the describe `Job`, which makes the engine call `LlmModule.stop()` (FR-G4).

## Assumptions to verify first

Verbatim rows from `docs/assumptions.md`; start with these, not with code.

| # | Assumption | By | Fallback if it fails |
|---|---|---|---|
| A-3 | The current `org.pytorch:executorch-android` 1.3.x AAR on Maven includes the multimodal runner and XNNPACK, so no from-source build is needed | Sat 11:00 | Build the AAR from source per the ExecuTorch Android docs; R2 loses ~2 h, cut R2's P1 first |
| A-4 | Gemma 4 edge multimodal exports with the documented script and its `.pte` fits in memory on the headset; record file size and load time | Sat 14:00 | A-4 answered no (D5); see A-13 |
| A-11 | `Module.load` needs a filesystem path, so `.pte` files are copied from `assets/models/` to `filesDir` on first launch (model files only; never frames) | Sat 11:00 | — (implemented in `ModelCatalog`; the alternative is the external files dir, which the catalog checks first) |
| A-13 | **The chosen VLM `.pte` loads with `LlmModule(MODEL_TYPE_MULTIMODAL, …)` on the 1.3.1 AAR and answers an image prompt on the headset.** Note: research already showed (2026-10-17) that the SRS's Gemma 4 export does NOT (`docs/stack.md` D5), so A-4 as written is answered "no"; this row covers the replacement path chosen under plan.md Q6 (default: Gemma 3 4B via optimum-executorch) | Sat 14:00 | Option A (Kotlin `Module`-level port of `Gemma4Runner`), or cut the VLM to the S25 Ultra (`docs/stack.md` Q9) |
| A-15 | A multi-GB `.pte` pushed to `/sdcard/Android/data/com.vysion.xr/files/models/` passes `validateFilePath` and loads with `LOAD_MODE_MMAP` (`docs/stack.md` Q20) | Sat 14:00 | Copy into `filesDir` via the app (the catalog's second path; needs the file in assets, i.e. a big APK) |

## Task checklist

Verbatim from SRS §8 (R2). Tick in the same PR as the code; P0 items need device evidence.

- [ ] P0 Pin `org.pytorch:executorch-android` to the current 1.3.x release; load a sample `.pte` on the headset to confirm the runtime works. Sat 11:00
  Files: `gradle/libs.versions.toml` (pinned 1.3.1, done in the skeleton), `inference/ExecuTorchInferenceEngine.kt` (`loadDetectorLocked`), `inference/ModelCatalog.kt` · FR: FR-G5 (backend), A-3, Q7 · Verify: `adb logcat -s vysion vysion.latency` shows `detector → /storage/emulated/0/Android/data/com.vysion.xr/files/models/detector.pte (N bytes)` then `detector load ms=<n> backend=XnnpackBackend`
- [ ] P0 Export YOLO26-n (COCO) to `.pte` with XNNPACK, int8; run on headset; record ms/frame. Sat 13:00
  Files: `ml/export/export_detector.sh`, `ml/models.lock` (detector row), `inference/ExecuTorchInferenceEngine.kt` (`forwardDetector`, `detect`), `inference/YoloPostProcessor.kt` · FR: FR-N2 · Verify: `scripts/benchmark.sh --model detector --runs 20` prints a Markdown row with a measured median; `adb logcat -s vysion.latency` shows `detect ms=<n> fps=<f>` with `fps ≥ 5` (logged by `detect()` at most once per second, already in the skeleton)
- [ ] P0 Export Gemma 4 edge multimodal with 4-bit weights following the ExecuTorch `examples/models/gemma4` path; load with the Android multimodal runner; first image-to-text on the headset. Sat 16:00
  Files: `ml/export/export_vlm.sh` (`--path c` default, `--path a` the SRS path), `ml/README.md` §0/§3, `ml/models.lock` (vlm + vlm-tokenizer rows), `inference/ExecuTorchInferenceEngine.kt` (`loadVlmLocked`, `prefillLocked`, `generateLocked`) · FR: FR-T1, FR-D1; A-13, Q8, Q9; RK-13 · Verify: **the SRS wording is impossible as written** (`docs/stack.md` D5): the evidence is `adb logcat -s vysion.latency` showing `vlm ms=<n> median=<n> p90=<n> runs=1 maxNewTokens=60` (the exact format `ExecuTorchInferenceEngine` logs; `runs` counts every VLM call since launch) after the first `describe(bitmap, PromptLibrary.READ)` on the headset with the chosen path, plus the first answer text in the PR. Two first-run decisions are recorded in `docs/stack.md` "Corrections" with it (both UNVERIFIED until then, procedure in `ml/README.md` §3): whether `LlmModule` applies Gemma 3's chat template ("Prompt format") and which `prefillImages` overload the export expects ("Image input"). Before this item, record the VLM path decision (`plan.md` Q6, Sat 11:00) in `docs/model-plan.md` "Decision"
- [ ] P0 `InferenceEngine`: `describe(bitmap, prompt): String`, `detect(bitmap): List<Detection>`, load/unload lifecycle, timing hooks for the overlay. Sat 16:00
  Files: `inference/ExecuTorchInferenceEngine.kt`, `inference/LatencyStats.kt`, `inference/FakeInferenceEngine.kt` · FR: FR-T1, FR-D1, FR-N2, FR-N3, FR-N6, FR-G5; ADR-0004; INV-1/2/8 · Verify: `./gradlew testDebugUnitTest` green (`FakeInferenceEngineTest`, `LatencyStatsTest`); on the headset the overlay Model row reads `detector … (unloaded)` during a describe and `(resident)` again after the next `detect()` (lazy reload, no eager reload after each describe), and `vysion.latency` shows `detector unloaded (INV-2)` / `detector load ms=…`; a second `warmUp()` (headset off and on) logs no second load; `grep -rn "TODO(FR-T1)\|TODO(FR-N2)" app/src/main/kotlin/com/vysion/xr/inference` returns nothing
- [ ] P0 `PromptLibrary.kt` with the four prompts in section 7; tune wording against 10 real photos from the venue. Sat 19:00
  Files: `inference/PromptLibrary.kt`, `app/src/test/.../PromptLibraryTest.kt` · FR: FR-T1, FR-D1, FR-N3, FR-N6 (+ FR-T4, FR-D2, FR-D3, FR-G6 variants) · Verify: `./gradlew testDebugUnitTest --tests '*PromptLibraryTest'` green (verbatim check); 10 venue photos run through `describe` with READ/DESCRIBE/DOOR on the headset, answers pasted in the PR, every wording change a commit to this one file
- [ ] P0 Benchmark script: 20 runs per model, median and p90 latency, peak memory, file size; results into the README table. Sun 10:00
  Files: `scripts/benchmark.sh` (R5 wrote the driver; R2 owns the numbers), `diagnostics/BenchmarkReceiver` contract (R5), `docs/benchmarks.md`, `README.md` model table · FR: FR-G5, NFR-latency/memory/thermal · Verify: `scripts/benchmark.sh --model vlm --runs 20` and `--model detector --runs 20` in airplane mode produce `bench/out/<model>-<date>.json`; the printed rows are in `docs/benchmarks.md` with a date and `Airplane mode: on`
- [ ] P1 If Gemma 4 misses the 6 s / 8 s targets, export SmolVLM-500M and A/B on the headset. Sat 22:00
  Files: `ml/export/export_vlm.sh --model <id>`, `ml/README.md`, `ml/models.lock` (same `vlm.pte` dest) · FR: FR-T1, FR-D1; RK-2 · Verify: two `docs/benchmarks.md` rows (one per model, 20 runs each); the faster one's name shows on the overlay. **Note**: at v1.3.1 there is no runnable SmolVLM example (`docs/stack.md` §3-R2); the practical A/B is a smaller `LlmModule`-compatible export from optimum-executorch, same recipe
- [ ] P2 Door-class fine-tune on Colab, re-export, swap into `detect()`. Sun 09:00
  Files: `ml/export/export_detector.sh --model <fine-tuned>`, `YoloPostProcessor.decode(classNames = …)` · FR: FR-N8 · Verify: `detect()` returns `label == "door"` boxes on a venue door, logcat `detect ms=` still ≥ 5 fps
- [ ] P2 QNN export of the VLM with on-site Qualcomm help; keep XNNPACK as the shipping build. Sun 11:00
  Files: `ml/models.lock` (new row, `backend: "QNN"`), `gradle/libs.versions.toml` (`org.pytorch:executorch-android-qnn:1.3.1`, only with a `docs/stack.md` line) · FR: FR-G5; ADR-0002 · Verify: a `docs/benchmarks.md` row with `backend = QNN` from the same 20-run protocol; the demo build still prints `XNNPACK`

## API cheat-sheet

Copied from `docs/stack.md` §3-R2 (tag v1.3.1). Sources: `Module` https://raw.githubusercontent.com/pytorch/executorch/v1.3.1/extension/android/executorch_android/src/main/java/org/pytorch/executorch/Module.java ; `LlmModule` https://raw.githubusercontent.com/pytorch/executorch/v1.3.1/extension/android/executorch_android/src/main/java/org/pytorch/executorch/extension/llm/LlmModule.java ; `LlmModuleConfig` …/extension/llm/LlmModuleConfig.java ; `LlmCallback` …/extension/llm/LlmCallback.java ; yolo26 https://raw.githubusercontent.com/pytorch/executorch/v1.3.1/examples/models/yolo26/export_and_validate.py ; gemma4 https://raw.githubusercontent.com/pytorch/executorch/v1.3.1/examples/models/gemma4/export_gemma4.py ; multimodal runner https://raw.githubusercontent.com/pytorch/executorch/v1.3.1/extension/llm/runner/multimodal_prefiller.cpp ; backend key https://raw.githubusercontent.com/pytorch/executorch/v1.3.1/backends/xnnpack/runtime/XNNPACKBackend.h.

**Detector path** (VERIFIED, ET-PS/ET-AC):

```kotlin
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor

val det = Module.load(catalog.resolve("detector").absolutePath, Module.LOAD_MODE_MMAP, /*numThreads=*/4)
val backend = det.getMethodMetadata("forward").getBackends().joinToString()      // "XnnpackBackend"
// preprocess: letterbox 640x640 (pick ONE pad value and keep the un-pad math consistent), RGB, /255, CHW float32
val input = Tensor.fromBlob(chw, longArrayOf(1, 3, 640, 640))
val t0 = SystemClock.elapsedRealtimeNanos()
val out = det.forward(EValue.from(input))[0].toTensor()                           // [1, N, 6], post-NMS
val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
val d = out.getDataAsFloatArray(); val n = out.shape()[1].toInt()
for (i in 0 until n) { val o = i * 6 /* x1=d[o] y1=d[o+1] x2=d[o+2] y2=d[o+3] conf=d[o+4] cls=d[o+5].toInt() */ }
det.close()   // SRS: unload before the VLM runs, re-create after
```

`Tensor.fromBlob(float[], long[])` copies into a direct native-order buffer; `fromBlob(FloatBuffer, long[])` aliases (direct + nativeOrder); **no `EValue.from(int)`**: an `Int` variable needs `.toLong()` (VERIFIED). `Module.load` and every `LlmModule` constructor call `ExecuTorchRuntime.validateFilePath` → `IllegalArgumentException("Cannot load model path: …")`; assets and content URIs are not loadable (VERIFIED).

**VLM path** (VERIFIED signatures; valid ONLY for an `LlmModule`-compatible `.pte`, i.e. option C):

```kotlin
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmModule
import org.pytorch.executorch.extension.llm.LlmModuleConfig

val vlm = LlmModule(LlmModuleConfig.create()
    .modulePath(ptePath).tokenizerPath(tokJsonPath)
    .modelType(LlmModuleConfig.MODEL_TYPE_MULTIMODAL).temperature(0f).loadMode(LlmModuleConfig.LOAD_MODE_MMAP).build())
vlm.load()                                              // eager; else first generate() pays it
vlm.resetContext()
vlm.prefillImages(rgbChwUint8 /* IntArray 0..255 */, w, h, 3)   // or prefillNormalizedImage(directFloatBuf, w, h, 3)
val sb = StringBuilder()
vlm.generate(PromptLibrary.READ, /*seqLen=*/ 512, object : LlmCallback {
    override fun onResult(result: String) { sb.append(result) }          // stream sentence-by-sentence into TTS
    override fun onStats(stats: String) { /* parse JSON: prompt_tokens, generated_tokens, first_token_ms, inference_start_ms … */ }
    override fun onError(errorCode: Int, message: String) { }
}, /*echo=*/ false)
// follow-up on the same frame (FR-D2): vlm.generate(followUp, seqLen + more, cb, false)  -- KV cache keeps the image
// "stop": vlm.stop() from any thread; generate() returns; only then close()
```

Semantics that bite (VERIFIED): `generate()` BLOCKS (never on Main); any `LlmModule` call from inside a callback throws `IllegalStateException`; `stop()` is lock-free and safe from another thread (FR-G4); `close()` throws if `generate()` is in flight; `seqLen` is an ABSOLUTE position budget (`max_new = min(seqLen, max_context_len) − pos`, `pos` counts image soft tokens and prior turns) and `maxNewTokens` is NOT forwarded in 1.3.1 → `ExecuTorchInferenceEngine.seqLenBudget(prefilled, maxNewTokens, maxContext)`; constructor `temperature` has no fallback (negative inverts the logits): pass `0f` for greedy read-verbatim; `prefillImages(int[])` truncates each int to uint8; TTFT = `first_token_ms − inference_start_ms` from `onStats`. `LlmModule` has no metadata API: open the same `.pte` once with `Module.load(path, LOAD_MODE_MMAP)`, read `getMethods()`/`getBackends()`, `close()` (VERIFIED).

**Exports** (`ml/README.md` has the full recipes):

```bash
# detector (VERIFIED at v1.3.1)
python export_and_validate.py --model_name yolo26n --backend xnnpack --quantize --video_path calib.mp4 --input_dims=[640,640]
# VLM option C (flag set VERIFIED as optimum-executorch's test config; CLI form/output layout UNVERIFIED)
optimum-cli export executorch --model google/gemma-3-4b-it --task multimodal-text-to-text --recipe xnnpack --qlinear 8da4w --qlinear_group_size 32 --qlinear_encoder 8da4w,8da8w --qlinear_encoder_group_size 32 --qembedding 8w --qembedding_encoder 8w --use_custom_sdpa --use_custom_kv_cache
# VLM option A (VERIFIED flags; NOT loadable by LlmModule, D5)
python examples/models/gemma4/export_gemma4.py --checkpoint_path ./gemma-4-E2B-it --variant e2b --no-audio --quantize 8da4w+emb8 --vision_quantize 8da8w --max_seq_len 1024 --max_patches 1260 --output_path gemma4_e2b_vision.pte
```

UNVERIFIED items this role touches (`docs/stack.md` bottom list): Gemma 3 4B preprocessing numbers (896×896, 256 soft tokens, mean = std = 0.5) → `ExecuTorchInferenceEngine.IMAGE_SOFT_TOKENS` and the lockfile dims carry `// stack.md: UNVERIFIED`; `extension.image.ImageProcessor` exists only at 1.5.1 (not used; `YoloPostProcessor.letterboxToChw` replaces it).

## Fallbacks

Rows from `docs/risks.md` that name R2, with the decide-by time. After that time nobody argues; the fallback ships.

| # | Risk | Mitigation / fallback | Decide by |
|---|---|---|---|
| RK-2 | Gemma 4 edge too slow or too large on the XR2+ Gen 2 | Measure by Sat 16:00; switch to SmolVLM-500M; cap output tokens at 60; prefill once, answer follow-ups from cache (see the decision tree below; at v1.3.1 the practical substitute is a smaller `LlmModule`-compatible optimum-executorch export) | Sat 16:00 |
| RK-3 | Memory pressure with VLM and detector loaded | Unload the detector during VLM calls (done: `unloadDetectorLocked` under the mutex); 4 GB budget on the overlay; test 10 minutes continuous (`--soak 600`) | Sat 22:00 (with R5) |
| RK-6 | Door one-shot returns the wrong side | Ask the VLM twice on two frames and require agreement; otherwise say "I can't find a door, turn slowly" (R1's `platform.DoorVote` returns no door for anything off the allowlist or negated, and the vote needs two agreeing answers; tune DOOR against `DoorVote`, not `parseDoorAnswer`) | Sun 09:00 (with R3) |
| RK-11 | Model download URLs in `ml/models.lock` change or need auth (Hugging Face gated repos) | Lockfile carries sha256 + size; `fetch_models.sh --from <dir>`; prebuilt `.pte` attached to the release tag | — (with R5) |
| RK-13 | The SRS VLM path does not exist as written (gemma4 export not loadable by `LlmModule` at 1.3.1/1.5.1/main; no SmolVLM example at 1.3.1) | Option C (Gemma 3 4B via optimum-executorch) by default; option A (Kotlin port of `Gemma4Runner` over plain `Module`) as the alternative; both recipes in `ml/README.md`; the `InferenceEngine` contract is the same either way | Sat 11:00 (R2 + Isaac) |

## How to test without the headset

- **`FakeInferenceEngine`** (wired by `-PuseFakes=true`, `AppGraph`): `warmUp()` publishes `ModelInfo("fake-vlm", "none", "FAKE", 0, 896, 896)` and `ModelInfo("fake-detector", "none", "FAKE", 0, 640, 640)`; `describe()` waits `describeDelayMs` (default 50) and returns the canned answer for the prompt (`READ` → "EXIT. Push bar to open. Alarm will sound.", `DOOR` → "center", `ARRIVAL` → handle on the right, `TRANSLATE_ES` → Spanish, unknown prompt → a follow-up answer), measuring the latency with `System.nanoTime()` so `lastVlmMs/medianVlmMs/p90VlmMs/vlmRuns` are real; `detectorLoaded` flips false during the call and back (ADR-0004 made visible); `detect()` waits `detectDelayMs` (default 20) and returns two boxes (person left of center, chair right) while `lastDetectMs` and the 5 s `detectorFps` update; `failNextDescribe` throws `InferenceException("fake failure")` once; `hangNextDescribe` suspends forever (cancellable) once, for R1's 15 s watchdog test; `describeStreaming` emits sentence by sentence; `cannedAnswers` and `cannedDetections` are mutable per test; `lastPrompt`, `describeCalls`, `detectCalls` let a test assert what R1 chose.
- **JUnit 4** (`./gradlew testDebugUnitTest`, no device): `PromptLibraryTest` (verbatim prompts, allowlist parser, locale), `LatencyStatsTest` (nearest-rank median/p90, ring eviction, fps window with a fake clock), `YoloPostProcessorTest` (letterbox both ways, both output layouts, class-wise NMS, pixel packing, COCO80), `ModelCatalogTest` (lockfile parse incl. the repo's real `ml/models.lock`, resolution order with temp dirs, `.part` never resolved, verified copy with sha256/bytes mismatch cleanup), `FakeInferenceEngineTest` (every behavior above, mutex serialization, watchdog cancellation, `seqLenBudget`). `android.graphics.Bitmap` is a stub on the JVM; `TestBitmaps.placeholder()` (in `FakeInferenceEngineTest.kt`) allocates one without a constructor so R1 can reuse it.
- **S25 Ultra** (R2's iteration device): same APK, `DeviceProfile.isXr == false`, real `ExecuTorchInferenceEngine` with sideloaded files; `describe`/`detect` run exactly as on the headset (no XR runtime involved). Latency numbers from the S25 are iteration feedback only; `docs/benchmarks.md` takes headset numbers.
- **Emulator**: `-PuseFakes=true` only (no arm64 ExecuTorch on an x86 image; the skeleton keeps arm64-v8a only).
- **Real engine without models**: the skeleton app launches, `warmUp()` logs `vlm unavailable: model not found …` and the `TODO(FR-T1)`/`TODO(FR-N2)` lines, `describe()`/`detect()` throw `InferenceException("… not loaded")`, and the controller speaks `Cues.inferenceFailed`. Nothing crashes.

## Definition of done

[`CONTRIBUTING.md`](../../CONTRIBUTING.md) "Definition of done for a task" applies. Device evidence R2 attaches for its P0 items:

1. Logcat lines under `vysion` / `vysion.latency` from the headset: `detector load ms=… backend=XnnpackBackend`, `detect ms=… fps=…` (≥ 5), `vlm ms=… median=… p90=… runs=…`, the first `describe(READ)` answer text.
2. `bench/out/vlm-<date>.json` and `bench/out/detector-<date>.json` from `scripts/benchmark.sh` in airplane mode, and the two Markdown rows pasted into `docs/benchmarks.md` (peak PSS from `dumpsys`, file size from `File.length()`).
3. `ml/models.lock` rows with real `sha256`/`bytes` and the release-tag `url`; `scripts/fetch_models.sh` prints `✓ … verified` for all three.
4. `scripts/check_privacy.sh` clean (the only flagged line is the `privacy-allow: model files only` copy in `ModelCatalog`).
5. `grep -rn "TODO(FR-" app/src/main/kotlin/com/vysion/xr/inference` empty for the P0 FR ids.

## Measurement protocol

SRS §7, verbatim: "20 runs per model on the headset in airplane mode; report median and p90 latency, peak memory, and model file size on the overlay and in the README. Numbers come from the device, never from a laptop."

| Quantity | Mechanism | Where it lands |
|---|---|---|
| VLM latency (READ prompt, 60 max tokens, on a live `captureStill()`, else a synthetic dark-gray frame: real latency, unrepresentative content, not pasted into `docs/benchmarks.md`; no bundled frame exists yet, r5 page P1 item) | `System.nanoTime()` around `describe()` inside the mutex; `LatencyStats` ring (64) → `lastVlmMs`, `medianVlmMs`, `p90VlmMs` (nearest-rank), `vlmRuns`; `onStats` JSON gives TTFT = `first_token_ms − inference_start_ms` | overlay (FR-G5), logcat `vysion.latency`, `bench/out/vlm-<date>.json` |
| Detector ms/frame and fps | `System.nanoTime()` around `detect()`; `FpsWindow` 5 s sliding → `detectorFps`; `lastDetectMs` | overlay, logcat, `bench/out/detector-<date>.json` |
| Peak memory | `scripts/benchmark.sh` samples `dumpsys meminfo` total PSS from outside every second (the app cannot under-report); R5's `MemorySampler` shows live PSS on the overlay | benchmark row "Peak PSS"; overlay "memory MB" |
| File size | `File.length()` of the resolved artifact (`ModelInfo.fileSizeBytes`), never the lockfile number | overlay, benchmark row |
| Load time | `System.nanoTime()` around `Module.load` / `LlmModule.load()` | logcat, benchmark `load_ms` |
| Thermal soak | `scripts/benchmark.sh --soak 600`: fps + PSS every 5 s for 10 min of Navigation; pass = detector never below 3 fps | `docs/benchmarks.md` "Thermal soak" |

`scripts/benchmark.sh` contract (R5 implements `diagnostics/BenchmarkReceiver`, debug builds only): `adb shell am broadcast -a com.vysion.xr.BENCH --es model <vlm|detector|soak> --ei runs <n> --ei seconds <s>`; the app writes `files/bench/<model>-latest.json` with `{ model, runs, latencies_ms[], median_ms, p90_ms, load_ms, file_bytes, backend, quantization, max_new_tokens, device, airplane_mode, started_at, finished_at }`; the script refuses to run with airplane mode off, pulls the JSON to `bench/out/`, and prints the Markdown row. R2 supplies the per-run numbers from `InferenceStats` and `LatencyStats`; medians in the JSON use the same nearest-rank rule as the overlay so the two never disagree.

Procedure: headset charged and cool; `adb shell cmd connectivity airplane-mode enable`; `scripts/benchmark.sh --model detector --runs 20`; `scripts/benchmark.sh --model vlm --runs 20`; paste both rows into `docs/benchmarks.md` and the README table; commit `r2: bench: <model> <date>`; attach the JSON to the PR, not the repo. A target that is missed is written as a miss.

### RK-2 decision tree (from `docs/model-plan.md`, decided by Sat 16:00)

```
Chosen VLM, 4-bit, on headset, 20 runs, READ prompt, 60 max tokens
├─ median ≤ 6 s AND peak PSS ≤ 4 GB  → ship it
├─ median ≤ 8 s                       → ship it for Describe; cap tokens at 40 for Read; note the miss
└─ otherwise                          → export the smaller fallback (same InferenceEngine, same prompts), A/B, ship the faster
```

Where "the smaller fallback" is SmolVLM-500M per the SRS if a runnable export path exists by then, else a smaller `LlmModule`-compatible optimum-executorch export (the SRS's Qualcomm SmolVLM example does not exist at v1.3.1, `docs/stack.md` §3-R2). Streaming (`describeStreaming`) turns "capture → first spoken word" into prefill + first token, so measure TTFT as well as total latency before deciding.

## Contract issues

None against `core/Contracts.kt` or `AppGraph.kt`: every signature matches and nothing was changed. Three deviations from the skeleton brief (items 1–3), none of them contract changes, so the reviewer knows; item 4 is an orchestrator addition:

1. `ModelCatalog` writes the model copy with `FileOutputStream(File(filesDir/models, "<dest>.part"))` (marker `privacy-allow: model files only`) rather than `openFileOutput`, because `openFileOutput` cannot address the `models/` subdirectory (it rejects path separators). `scripts/check_privacy.sh` honors the marker on either API.
2. The lockfile is parsed by a 60-line pure JSON reader (`LockJson`) instead of `org.json`: `org.json` is a stub in the unit-test `android.jar` (returns null under `isReturnDefaultValues`), so a parser built on it could not be JUnit-tested, and no JSON library is pinned (no new dependency without a `docs/stack.md` line). Same JSON, same lockfile.
3. `ml/models.lock` (and its assets copy) changed in two fields as the brief instructed: `vlm-tokenizer` dest `.model` → `.json` (both VLM paths ship `tokenizer.json`), and the detector row's `input_height` 480 → 640 (the verified export is `--input_dims=[640,640]`; 480 was the analysis stream's height, not the model's).
4. Added by the orchestrator, not by R2: `object LibraryPromptSet : PromptSet` at the end of `inference/PromptLibrary.kt`. `PromptSet` is a seam in `core/Contracts.kt`; `AppGraph` passes `prompts = LibraryPromptSet` to `ModeController` and exposes it as `AppGraph.prompts` (the debug `BenchmarkReceiver` reads `graph.prompts.read`), so no other package imports `inference` (ADR-0005). Keep its overrides in step with `PromptLibrary` when a prompt is added or renamed.

## Dependencies needed

None. ExecuTorch 1.3.1 (already pinned) covers `Module`, `LlmModule`, `Tensor`, `EValue`; `org.json` was avoided (see above); letterboxing is done in `YoloPostProcessor` so `extension.image.ImageProcessor` (1.5.1 only) is not needed. Python-side pins live in `ml/requirements.txt`.
