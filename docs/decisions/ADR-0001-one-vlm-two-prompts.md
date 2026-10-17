# ADR-0001: One VLM with different prompts; the detector is the only second model

**Status:** accepted (restates SRS §2 "One-model strategy")

## Context

Three modes could suggest three models (OCR, captioning, detection). Five people have ~30 working hours, and the hardest engineering step is exporting a multimodal model to ExecuTorch and getting it to run on the headset.

## Decision

Text Reader and Audio Description are the same vision-language model with different prompts (`PromptLibrary.READ`, `PromptLibrary.DESCRIBE`). Door finding and the arrival check are also VLM one-shots (`DOOR`, `ARRIVAL`). The only second model is a light object detector (YOLO26-n int8), and it exists only because the VLM is too slow to run continuously during Navigation.

## Alternatives rejected

- A dedicated OCR model for Text Reader: a third export, a third tokenizer/post-processing path, and no rubric points over VLM-read-verbatim.
- Detector-only navigation with a fine-tuned door class: cheaper at runtime but needs a fine-tune on Saturday; kept as P2 (FR-N8).

## Consequences

- The VLM export (R2) is on the critical path and is measured on the headset before anything is built on it (gate 4, Sat 16:00).
- Prompt wording is product behavior: it lives in one file and is quoted in the README.
- If Gemma 4 misses the 6 s / 8 s targets, the fallback (SmolVLM-500M) must keep the same `InferenceEngine.describe(bitmap, prompt)` contract so nothing above it changes.
