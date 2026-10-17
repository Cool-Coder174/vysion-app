# ADR-0007: Model artifacts are fetched by a script against a lockfile, never committed

**Status:** accepted (skeleton decision, 2026-10-17)

## Context

The `.pte` files are hundreds of MB to a few GB. The SRS requires `scripts/fetch_models.sh` to download or build them to `app/src/main/assets`, `./gradlew installDebug` to work from a fresh clone, and the prebuilt `.pte` files to be attached to a release tag. Reproducibility target: ≤ 30 min from clone to headset.

## Decision

`ml/models.lock` is the source of truth: one row per artifact with name, URL, sha256, byte size, the ExecuTorch version and the export commit. `scripts/fetch_models.sh` reads the lockfile, downloads (or copies from `--from <dir>`), verifies the hash, and places the files under `app/src/main/assets/models/`. That directory is gitignored except for its README. The release tag carries the same files as GitHub release assets, and the lockfile URLs point at them once the tag exists.

## Alternatives rejected

- Git LFS: quota and bandwidth limits on a public repo during a hackathon, and the clone-time cost hits everyone.
- Committing the files: repository bloat that cannot be undone.
- Downloading at app runtime: the app has no INTERNET permission (ADR-0003).

## Consequences

- A model swap (Gemma 4 → SmolVLM, RK-2) is one lockfile row plus one script run; the app reads `ModelInfo` from the lockfile copy bundled in assets so the overlay prints the right name.
- The script must be tested from a fresh clone by someone who did not write it (submission checklist).
