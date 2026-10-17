# ADR-0003: No INTERNET permission; camera frames never touch storage

**Status:** accepted (restates SRS §5 Offline and Privacy rows)

## Context

15 rubric points are for on-device AI and efficiency, judged by "local processing, privacy, thoughtful resource use". The demo runs in airplane mode and the overlay prints "Network permission: none". Users are people with low vision pointing a camera at their surroundings.

## Decision

1. The manifest never declares `android.permission.INTERNET` (or `ACCESS_NETWORK_STATE`). Both strings appear in `app/src/main` only as `tools:node="remove"` guards that strip them when a library adds them; `scripts/check_privacy.sh` fails on any other occurrence and on any network permission in the merged manifest.
2. Camera frames exist only as in-memory `Bitmap`s owned by the active command and are recycled after use. No code path writes a frame to disk, to `MediaStore`, or to a log.
3. No analytics, no crash reporter, no third-party SDK that has a network code path.
4. The only files the app writes are the model files copied from `assets/models/` to `filesDir/models/`.

## Alternatives rejected

- Logging a downscaled thumbnail for debugging: convenient on Saturday, indefensible in front of a judge, and a line nobody remembers to delete.
- Using a dependency with network transitive permissions (some analytics/telemetry libs merge `INTERNET` through the manifest merger): the merged manifest is checked, not just ours.

## Consequences

- `scripts/check_privacy.sh` runs in CI and in the PR template.
- The merged manifest (`app/build/intermediates/merged_manifest/`) is what the CI grep inspects after `assembleDebug`, so a transitive dependency cannot smuggle a permission in.
- The runtime audit (`ManifestAudit`) reads `requestedPermissions` and prints the result on the overlay; it is the judge-visible proof.
