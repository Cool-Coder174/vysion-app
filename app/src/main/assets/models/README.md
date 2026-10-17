# Model assets (fetched, not committed)

This directory is filled by `scripts/fetch_models.sh` from the lockfile `ml/models.lock` (ADR-0007). Everything here except this README is gitignored.

Expected layout after a successful fetch:

```
vlm.pte             # Gemma 4 edge multimodal, 4-bit, XNNPACK (or the SmolVLM-500M fallback, same filename)
vlm-tokenizer.*     # tokenizer artifact the ExecuTorch multimodal runner needs; exact name in ml/README.md
detector.pte        # YOLO26-n (COCO), int8, XNNPACK
models.lock         # copy of ml/models.lock so the app can print name/quantization/backend/size at runtime
```

On first launch the app copies the `.pte` files to `filesDir/models/` because the ExecuTorch `Module` API loads from a filesystem path (assumption A-11). That copy is the only thing the app ever writes to storage (ADR-0003).

If the fetch fails, `ml/README.md` has the export commands to build these locally, and the GitHub release tag carries prebuilt copies.
