# ADR-0009: One "onboarding finished" flag is the only preference the app writes

**Status:** accepted (2026-10-17, Isaac; amends ADR-0003 rule 4)

## Context

Michelle Bui's designs add a seven-card first-launch onboarding (welcome, privacy, talk, stop, sounds, safety, all set). Showing it on every launch would make every relaunch during the demo start with seven spoken cards. ADR-0003 rule 4 says the only files the app writes are the model files copied to `filesDir/models/`.

## Decision

`platform/OnboardingStore` keeps one boolean, `onboarding_done`, in a private SharedPreferences file (`vysion_onboarding`). Nothing else is stored there: no frame, no transcript, no command, no user content. ADR-0003 rule 4 now reads: the app writes the model files and this one flag.

## Alternatives rejected

- Show onboarding on every launch: honest about storage, hostile in the demo and to returning users.
- Remember it in memory only: the card sequence returns whenever the process is killed, which on a headset is often.

## Consequences

- To see onboarding again: `adb shell pm clear com.vysion.xr`, or in a debug build `adb shell am start -n com.vysion.xr/.MainActivity --ei vysion.onboarding 1`.
- `scripts/check_privacy.sh` does not flag SharedPreferences; review keeps this file to the one key.
