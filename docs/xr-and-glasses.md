# Galaxy XR quality rules and smart-glasses options (research, 2026-10-17)

Two research passes (official Android XR and Meta developer docs; sources in the session log). What we applied, what we
did not, and what a glasses target would take.

## Android XR rules we now follow

| Rule (Android XR design guides) | Value | In the app |
|---|---|---|
| Orbiters float off the panel edge, never cover its content | 20 dp gap, ≤ 50 % overlap | Rail and toolbar use `EdgeAlignment.Outside` + 20 dp (`SpatialShell.kt`) |
| One main panel in front of the user | ~1024 × 720 dp, 32 dp corners, 1.75 m | Status window 1040 dp wide, centered in a `SpatialBox`; side windows open beside it without moving it |
| Targets | 56 dp recommended, 48 dp minimum, 8 dp apart | Rail items 64 dp pill inside a ~102 dp item; toolbar pills 64 dp |
| Text | ≥ 14 dp, regular weight or heavier | Body 28 sp, answers larger, rail labels 18 sp semibold |
| Every action confirmed by sound or visuals; alternatives to gaze | | Earcons + spoken answers; every rail item has a visible label |
| A long pinch (≥ 2 s) opens system options on Galaxy XR | | **Talk is a tap toggle**, not press-and-hold; the recognizer sends on a pause |
| High contrast over passthrough | | Near-opaque graphite glass (85–96 % alpha) |
| TalkBack structure | `heading()` on titles, live regions for updates | Titles are headings; answers are NOT live regions (the app speaks them; TalkBack would repeat them) |

### Eyes-free gestures (blind users)

Galaxy XR owns the index pinch (select), the 2 s index pinch (options) and any pinch with the palm facing you (Home).
So the app's gestures never use the index finger and are ignored while the palm faces the wearer
(`perception/HandGestureRecognizer.kt`, tested; `platform/GestureCommands.kt`):

| Gesture | Does | Spoken / shown |
|---|---|---|
| Thumb to middle finger, hold | Talk (mic open while held, sends on release) | Listening earcon, Listening window |
| Thumb to middle finger, quick tap | Run the current page (Read, Describe or Find door) | The page's answer |
| Thumb to ring finger, tap | Next page (Read → Describe → Find door) | Ping + "Describe. Tap your thumb to your middle finger to describe." |
| Open hand, palm away, hold ½ s | Stop | Silence |

Thresholds: pinch on < 2.2 cm, off > 3.5 cm; tap < 400 ms; open hand = every fingertip > 11 cm from the wrist.
Tune them on device with `adb logcat -s vysion.gesture`.

Not adopted: `androidx.xr.compose.material3` (1.0.0-alpha17). It was built against Compose for XR alpha14 and calls
APIs removed in beta01 (`Orbiter(ContentEdge…)`, `SpatialPanel(…, DragPolicy, ResizePolicy)`), so it would likely
crash with `NoSuchMethodError`. Our own orbiter rail follows the same spec.

Known platform crash we cannot fix in app code: `ActivitySpaceImpl.onBoundsChanged` NPE inside SceneCore during fast
force-restarts (we are on the newest Jetpack XR: compose beta01, scenecore rc02).

## Meta smart glasses (Ray-Ban Meta, Oakley Meta, Meta Ray-Ban Display)

- **Wearables Device Access Toolkit 1.0.1** (Maven Central `com.meta.wearable:mwdat-core|camera|display|…`), an SDK for
  the paired phone app. Camera stream 360×640 to 720×1280 at 2–30 fps over Bluetooth, photo capture, mic over HFP
  (8 kHz), speakers over A2DP, a 600×600 display on Ray-Ban Display.
- Distribution is invite-only beta; public submissions "soon". Registration goes through the Meta AI app.
- **Conflict with ADR-0003:** the toolkit's manifest needs `INTERNET` (and Bluetooth). Our privacy gate forbids network
  permission, so a Meta target needs its own build variant or an ADR amendment before any code.
- Fit: the phone runs our ExecuTorch models on glasses frames and speaks through the glasses. Meta explicitly lists
  "scene description" accessibility apps; covert capture and face/voice identification are banned; the capture LED
  must never be hidden.
- Minimal path: `Wearables.initialize` → registration → `createSession` → `addCamera(LOW, 15 fps)` → frames into a
  `FrameSource` implementation → existing engine → `TextToSpeech` over A2DP; open HFP only while listening.

## Android XR glasses (Samsung and partners)

- "Audio glasses" and "display glasses" run a **projected activity** from the phone app
  (`androidx.xr.projected:1.0.0-alpha13`, Jetpack Compose Glimmer `1.0.0-alpha18` for display glasses).
- Camera through CameraX on a `ProjectedContext` (one stream; 640×480 at 10 fps recommended for vision), audio over
  Bluetooth, input from the touchpad and camera button. Reuses more of our Android code than Meta's SDK.
- No hardware on sale yet (audio glasses expected late 2026); everything is alpha.

## Recommendation

Finish the Galaxy XR experience first. For glasses, the cheapest next step is an Android XR projected activity that
reuses `CameraFrameSource`, the engine and the speaker (no network permission needed). A Meta target is worth it only
after an ADR decides how to reconcile its `INTERNET` requirement with ADR-0003.
