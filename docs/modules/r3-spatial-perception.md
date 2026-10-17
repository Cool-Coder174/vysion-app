# R3 Spatial Perception and Navigation

## Purpose

R3 turns the headset's own perception stack into the three things Navigation speaks: a proximity warning from the depth map, the door's bearing and distance from a native anchor and the head pose, and (P1) wall / floor cues from plane labels. SRS §8 says R3 **Owns** "ARCore for Jetpack XR session, depth, planes, anchors, hands, `NavigationEngine`, spatial audio cues" and **Hands off** "`NavigationCues` flow (proximity, bearing, distance) and `AnchorStore`", due **Sat 19:00**. Nothing in this package builds a sentence (R4 words every cue in `voice/Cues.kt`, INV-4) and nothing touches the camera image (R2 runs the detector; R3 only loops it).

## Files you own

| Path | What it is | Status |
|---|---|---|
| `app/src/main/kotlin/com/vysion/xr/perception/XrPerceptionSession.kt` | Value types (`Vec3`, `DepthFrame`, `PlaneObservation`, `HandObservation`, `RawHeadPose`, `AnchorPoseSample`), `HeadAngles` (pure), the internal `PerceptionSource` port, and `XrPerceptionSession(Application, CoroutineScope)`: creates/configures the ARCore for Jetpack XR `Session`, feeds depth / planes / hands / head-pose flows, owns native anchors, exposes the SceneCore entity hook for the pinger | stub (device calls) + pure (`HeadAngles`) |
| `.../perception/XrHeadPoseSource.kt` | `AngularSpeed` + `HeadPoseTracker` (pure, FR-T5) and `XrHeadPoseSource(XrPerceptionSession)` implementing `HeadPoseSource` | pure + real mapping |
| `.../perception/NoOpHeadPoseSource.kt` | `HeadPoseSource` for the S25 Ultra / emulator / `-PuseFakes=true`: `pose` always `null` | fake (NoOp, fully working) |
| `.../perception/ProximityMonitor.kt` | `DepthCone` (60° cone pixel math, trusted-minimum, bearing → pixel, median window) and `ProximityMonitor` (1.0 m threshold, 2 s debounce, stale-frame guard) | pure, tested |
| `.../perception/PlaneSemantics.kt` | `PlaneGeometry` (ray/plane, point-in-polygon, ray/polygon exit, wall distance, floor edge) and `PlaneSemantics` (`WallAhead` / `FloorEnds` within 1.5 m) | pure, tested |
| `.../perception/XrAnchorStore.kt` | `BearingAngles` (LEFT −30°, CENTER 0°, RIGHT +30°), `RelativeGeometry` (anchor + head → `RelativeTarget`), `XrAnchorStore(XrPerceptionSession, HeadPoseSource)` implementing `AnchorStore` | pure + real logic; device calls behind the session stubs |
| `.../perception/NoOpAnchorStore.kt` | `AnchorStore` for non-XR devices: `placeFromBearing` → `null`, `track` completes at once | fake (NoOp, fully working) |
| `.../perception/XrNavigationEngine.kt` | `Clocks`, `RepeatGate`, and `XrNavigationEngine(session, frames, engine, anchors, headPose, scope)` implementing `NavigationEngine`: one Job, four loops (proximity 10 Hz, planes, detector, guidance 3 s), pause/resume gating, `cues` SharedFlow(16, DROP_OLDEST) | real loop code; device calls behind `PerceptionSource` / `AnchorPinger` |
| `.../perception/NoOpNavigationEngine.kt` | `NavigationEngine` for non-XR devices: `isRunning` false, empty flows, no-op calls | fake (NoOp, fully working) |
| `.../perception/SpatialAudioPinger.kt` | internal `AnchorPinger` port and `SpatialAudioPinger(XrPerceptionSession)`: SceneCore point-source ping at the anchor entity every 3 s while guiding | stub |
| `app/src/test/kotlin/com/vysion/xr/perception/PerceptionFakes.kt` | `FakePerceptionSource`, `FakeFrameSource`, `FakeInferenceEngine`, `FakeAnchorStore`, `FakePinger`, `TestBitmaps`, `depthFrame()` / `head()` builders | fake (test only) |
| `.../perception/ProximityMonitorTest.kt`, `PlaneSemanticsTest.kt`, `RelativeGeometryTest.kt`, `AngularSpeedTest.kt`, `XrAnchorStoreTest.kt`, `XrNavigationEngineTest.kt` | JUnit 4 specs for every pure function and for the engine's loop structure with `kotlinx-coroutines-test` | tests |
| `docs/modules/r3-spatial-perception.md` | this page | doc |

## Contracts you provide / consume

| Direction | Kotlin signature (`core/Contracts.kt`) | Other side |
|---|---|---|
| provide | `interface NavigationEngine { val cues: Flow<NavigationCue>; val detections: Flow<List<Detection>>; val isRunning: StateFlow<Boolean>; suspend fun start(); suspend fun pause(); suspend fun resume(); suspend fun stop(); suspend fun guideTo(anchor: AnchorId); suspend fun clearTarget() }` → `perception.XrNavigationEngine` / `NoOpNavigationEngine` | R1 `ModeController` (calls `pause` before and `resume` after every one-shot command, ADR-0004; turns `cues` into `Speaker.say(Cues.…)`), R1 `OverlayRenderer` (`detections`), R4 (`Cues.kt` wording of every `NavigationCue`) |
| provide | `sealed interface NavigationCue { Proximity(distanceM) · DoorBearing(clock, distanceM, inView) · WallAhead(distanceM) · FloorEnds(distanceM) · Arrived · TargetLost }` | R4 words them; R1 routes `Arrived` to the ARRIVAL prompt (FR-N6) |
| provide | `interface AnchorStore { suspend fun placeFromBearing(bearing: HorizontalBearing): AnchorId?; fun track(id: AnchorId): Flow<RelativeTarget>; suspend fun remove(id: AnchorId); suspend fun clear() }` → `perception.XrAnchorStore` / `NoOpAnchorStore` | R1 `ModeController` (after the RK-6 door vote: `placeFromBearing` then `navigation.guideTo(id)`), R3 `XrNavigationEngine` (`track`) |
| provide | `data class RelativeTarget(bearingDeg: Float, clock: Int, distanceM: Float, inView: Boolean)` | R1 (door highlight when `inView`) |
| provide | `interface HeadPoseSource { val pose: StateFlow<HeadPose?> }` with `data class HeadPose(yawDeg, pitchDeg, rollDeg, angularSpeedDegPerS, timestampNanos)` → `perception.XrHeadPoseSource` / `NoOpHeadPoseSource` | R1 (FR-T5 "hold still" when `angularSpeedDegPerS` is high at capture; `null` = no pose, skip the check) |
| consume | `interface FrameSource { val analysisFrames: Flow<Bitmap>; … }` | R1 `platform.CameraFrameSource` / `FakeFrameSource` (bitmaps owned by R1's pool; R3 never recycles or logs them, INV-3/S-53) |
| consume | `interface InferenceEngine { suspend fun detect(bitmap: Bitmap): List<Detection>; … }` | R2 `inference.ExecuTorchInferenceEngine` / `FakeInferenceEngine` (one `Mutex`, INV-1; `InferenceException` → detector off, Navigation continues on depth) |
| consume | `object ClockFace { fun fromRelativeYawDeg(yawDeg: Float): Int }`, `object Distances { fun roundToHalfMeter(m): Float; PROXIMITY_THRESHOLD_M = 1.0; ARRIVAL_RADIUS_M = 1.0; PLANE_CUE_RANGE_M = 1.5 }` (`core/Units.kt`) | R5 (owner of `core`) |
| constructors (fixed by `AppGraph.kt`) | `XrPerceptionSession(Application, CoroutineScope)` · `XrHeadPoseSource(XrPerceptionSession)` / `NoOpHeadPoseSource()` · `XrAnchorStore(XrPerceptionSession, HeadPoseSource)` / `NoOpAnchorStore()` · `XrNavigationEngine(XrPerceptionSession, FrameSource, InferenceEngine, AnchorStore, HeadPoseSource, CoroutineScope)` / `NoOpNavigationEngine()` | R1 (`AppGraph`): the XR set is wired only when `DeviceProfile.isXr && !BuildConfig.USE_FAKES` |

Inside the package, `PerceptionSource` (device port) and `AnchorPinger` are `internal` interfaces so the engine, store and head-pose source can be unit-tested against `FakePerceptionSource` / `FakePinger`; they never cross the package (ADR-0005).

### Bearing and clock conventions (every R3 file, `core.ClockFace`, R4's phrasing)

- World frame is OpenXR / Jetpack XR: **+X right, +Y up, −Z forward**, meters (`Pose.forward = rotation * (0, 0, −1)`).
- `RawHeadPose.yawDeg`: 0 = facing world −Z, **positive = turned to the right** (clockwise seen from above), wrapped to (−180, 180]. `pitchDeg` positive = looking up; `rollDeg` positive = right ear down. Built from `Pose.forward` / `Pose.up` by `HeadAngles.fromForwardUp` (tested).
- World bearing of a point from the head: `atan2(dx, −dz)`. Relative bearing = `wrap(worldBearing − headYaw)`, 0 = ahead, positive = right (matches `RelativeTarget.bearingDeg`).
- Clock face (`ClockFace.fromRelativeYawDeg`, 30° per hour): **12 = ahead, 3 = right, 6 = behind, 9 = left**; the SRS example "door, 2 o'clock, 3 meters" is a relative bearing of +60° at 3.0 m.
- Distance spoken for the door is horizontal (XZ, the walking distance) rounded to 0.5 m (`Distances.roundToHalfMeter`); `inView` = |relative bearing| ≤ 45° (R1 draws the door highlight only then).
- VLM answer → bearing offset (`BearingAngles`): LEFT = −30°, CENTER = 0°, RIGHT = +30° from the head yaw at capture time. The thirds of the still are ±hfov/3 of the world camera; ±30° is the placeholder until Q3 logs the camera FOV, then set `±hfov/3`.
- Head angular speed (FR-T5): `sqrt(Δyaw² + Δpitch²) / Δt`, Δyaw wrapped the short way, monotonic nanos; `0` when Δt ≤ 0.

### Cone math (FR-N1, `DepthCone`)

Pinhole model of the depth image of size W×H with measured FOV (`RenderViewpoint.…fieldOfView`): focal lengths `fx = (W/2) / tan(hfov/2)`, `fy = (H/2) / tan(vfov/2)` pixels; optical center `cx = (W−1)/2`, `cy = (H−1)/2`. Pixel (x, y) looks along `((x−cx)/fx, (y−cy)/fy, 1)`, so its angle off the axis is `θ = atan(sqrt(u² + v²))` with `u = (x−cx)/fx`, `v = (y−cy)/fy`. **A pixel is inside the 60° cone iff `u² + v² ≤ tan²(30°) ≈ 0.333`**, i.e. the disc of radius `0.577·fx` (horizontally) / `0.577·fy` (vertically) around the center, clamped to the image when the FOV is narrower than 60°. `minTrustedDepth` takes the smallest depth in that disc among pixels with depth ≥ 0.1 m (0 = no data) and confidence ≥ 128/255; `ProximityMonitor` emits `Proximity(min)` when `min < 1.0 m`, at most once per 2 s, and ignores maps older than 500 ms (a map buffered during a pause). Bearing → pixel for FR-N3: `x = cx + fx·tan(relYaw)`, `y = cy − fy·tan(relPitch)`; the depth there is the median of a 7×7 trusted window.

## Assumptions to verify first

| # | Owner | Assumption | By | Result | Evidence | Checked by |
|---|---|---|---|---|---|---|
| A-5 | R3 | ARCore for Jetpack XR `1.0.0-alpha15` depth and plane tracking return data on the headset with the `SCENE_UNDERSTANDING_*` permissions granted | Sat 12:00 | | | |
| A-6 | R3 | SceneCore spatial audio point source is available and audible through the headset speakers | Sat 13:00 | | | |

If it fails (verbatim from `docs/assumptions.md`):

| Failed | Then |
|---|---|
| A-5 | RK-5 fallback: plane hit tests and detector box height as the distance proxy |
| A-6 | Non-spatial earcon through `Speaker.earcon(PING)`; say the bearing more often (every 2 s) |

Note the pin is `arcore = "1.0.0-rc02"` (`gradle/libs.versions.toml`, stack.md §1); the SRS's alpha15 is four releases stale. `docs/stack.md` Q5 (does `configure` succeed with all four modes?) and Q6 (does `Depth.left/mono` deliver a non-null `smoothDepthMap`?) are the two yes/no checks that answer A-5; Q11 answers A-6. Record results in `docs/assumptions.md`, not here.

## Task checklist

- [ ] P0 ARCore for Jetpack XR session (`arcore:1.0.0-alpha15`) created from the Compose for XR session; depth, plane, hand, and device-pose configs enabled. Sat 12:00
    Files: `XrPerceptionSession.kt` (`start(activity)`, the four feeders) · FR: FR-N1, FR-N3, FR-N5, A-5 · Verify: `scripts/install_headset.sh --logcat | grep -E "vysion.*(SessionConfigureSuccess|depth [0-9]+x[0-9]+|plane (WALL|FLOOR))"` prints one depth line (`depth WxH, min trusted depth N m`) and one plane line; `XrPerceptionSession.state` reads `RUNNING`
- [ ] P0 Proximity: sample the depth texture in the forward 60-degree cone at 10 Hz; emit `Proximity(distanceM)` when under 1.0 m with 2 s debounce. Sat 15:00
    Files: `ProximityMonitor.kt` (done, pure), `XrPerceptionSession.kt` depth feeder, `XrNavigationEngine.proximityLoop` (done) · FR: FR-N1 · Verify: `./gradlew testDebugUnitTest --tests "com.vysion.xr.perception.ProximityMonitorTest"` green; on the headset walk at a wall: `adb logcat -s vysion.latency` prints `proximity cue N ms after the depth sample (FR-N1, target <= 300 ms)` with N ≤ 300 and lines at least 2 s apart; copy N to `docs/benchmarks.md`
- [ ] P0 `AnchorStore`: given a bearing (left/center/right from the VLM) and a depth sample along it, create an anchor; expose bearing and distance from the current head pose. Sat 18:00
    Files: `XrAnchorStore.kt` (logic done), `XrPerceptionSession.kt` (`sampleDepthAlongBearing`, `createAnchor`, `anchorPose`) · FR: FR-N3, FR-N4 · Verify: `./gradlew testDebugUnitTest --tests "com.vysion.xr.perception.XrAnchorStoreTest" --tests "com.vysion.xr.perception.RelativeGeometryTest"` green; on the headset `adb logcat -s vysion | grep "anchor .* placed"` shows `anchor door-0 placed: bearing CENTER (0.0°), N m, clock 12 (FR-N3)` for a door straight ahead
- [ ] P0 `NavigationCues` flow: proximity, door bearing in clock-face form, distance in meters rounded to 0.5. Sat 19:00
    Files: `XrNavigationEngine.kt` (done), `NoOpNavigationEngine.kt` (done) · FR: FR-N1, FR-N2, FR-N3, FR-N6 · Verify: `./gradlew testDebugUnitTest --tests "com.vysion.xr.perception.XrNavigationEngineTest"` green (start idempotent, pause/resume gating, 3 s cadence, Arrived, TargetLost); on the headset R1's collector logs `vysion: cue DoorBearing(clock=2, distanceM=3.0, inView=true)` every 3 s while guiding
- [ ] P0 Spatial audio: a SceneCore point source at the anchor playing a ping every 3 s while guiding. Sat 21:00
    Files: `SpatialAudioPinger.kt`, `XrPerceptionSession.kt` (`createAnchorEntity`, `playPointSource`) · FR: FR-N3, A-6 · Verify: `adb logcat -s vysion | grep -c "TODO(FR-N3) SpatialAudioPinger"` is 0 while guiding; a blindfolded teammate points at the ping source within ±30° on 3 of 3 tries (log the result in `docs/assumptions.md` A-6)
- [ ] P1 Plane semantics: wall-ahead and floor-ends cues from plane labels within 1.5 m on the heading. Sun 09:00
    Files: `PlaneSemantics.kt` (done, pure), `XrPerceptionSession.kt` plane feeder, `XrNavigationEngine.planeLoop` (done) · FR: FR-N5 · Verify: `./gradlew testDebugUnitTest --tests "com.vysion.xr.perception.PlaneSemanticsTest"` green; on the headset walk toward a wall: `vysion: cue WallAhead(distanceM=1.5)` appears once per 3 s inside 1.5 m and not outside; a FLOOR plane's logged normal is ≈ (0, 1, 0) (confirms the plane-frame assumption)
- [ ] P1 Keep guiding when the door leaves the field of view; test by turning 180 degrees and back. Sun 10:00
    Files: `XrAnchorStore.track` (done), `XrNavigationEngine.guidanceLoop` (done) · FR: FR-N4 · Verify: procedure in "How to test without the headset → The 180° test"; logcat shows `DoorBearing(clock=6, …, inView=false)` while turned away and `clock=12, inView=true` after turning back, with no `TargetLost` in between
- [ ] P2 Pointing: index-finger ray from hand tracking to a region for region-only describe. Sun 11:00
    Files: `XrPerceptionSession.kt` hand feeder (`HandObservation.indexProximal` → `indexTip` ray), a new pure `PointingRay` helper · FR: FR-D4 · Verify: unit test that a ray from proximal to tip hits a 1 m box at 2 m inside a `NormalizedBox`; headset: pointing at a cup makes R1 pass the region box to R2's prompt (needs an additive contract change, see Contract issues)

## API cheat-sheet

Copied from `docs/stack.md` §3 → R3 (sources: API files at `https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/xr/{runtime/runtime,arcore/arcore,scenecore/scenecore}/api/1.0.0-rc01.txt`; guides `https://developer.android.com/develop/xr/jetpack-xr-sdk/arcore` (`/planes`, `/depth`, `/hands`, `/anchors`, `/device-pose`) and `https://developer.android.com/develop/xr/jetpack-xr-sdk/add-spatial-audio`). Tag per row: **VERIFIED** = stack.md XR-PS + XR-AC, re-checked on 2026-10-17 by `javap` against the cached `arcore-1.0.0-rc02` / `scenecore-1.0.0-rc02` / `runtime-1.1.0-alpha01` jars (stack.md D2: rc02 is identical but one constant); **UNVERIFIED** = nobody checked, use only behind a `// stack.md: UNVERIFIED` comment.

| Need | Exact API | Tag |
|---|---|---|
| Create | `suspend fun Session.Companion.create(context: Context, coroutineContext: CoroutineContext = EmptyCoroutineContext, lifecycleOwner: LifecycleOwner = context as LifecycleOwner): SessionCreateResult`; call with **named** `context =` inside `withContext(Dispatchers.IO)`; never `applicationContext` (D7) | VERIFIED |
| Create results | `SessionCreateSuccess(session)`, `SessionCreateUnsupportedDevice`, `SessionCreateApkRequired`, `SessionCreateTimedOut`, `SessionCreateUnknownError`; sealed nonexhaustive → keep `else ->`; a missing permission is a `SecurityException` | VERIFIED |
| Configure | `Config.Builder(session.config).setPlaneTracking(PlaneTrackingMode.HORIZONTAL_AND_VERTICAL).setDepthEstimation(DepthEstimationMode.SMOOTH_ONLY).setHandTracking(HandTrackingMode.BOTH).setDeviceTracking(DeviceTrackingMode.SPATIAL).setAnchorPersistence(AnchorPersistenceMode.LOCAL).build()`; `session.configure(cfg): SessionConfigureResult` (`SessionConfigureSuccess` …); throws `SecurityException` / `UnsupportedOperationException`; no `headTracking` field: head = `DeviceTrackingMode.SPATIAL` + `HEAD_TRACKING` | VERIFIED |
| Capability probe | `XrDevice.getCurrentDevice(context).isDepthEstimationModeSupported(mode)` / `.isHandTrackingModeSupported(mode)` / `.isRenderingModeSupported(RenderingMode.STEREO)` | VERIFIED |
| Depth | `Depth.left(session)` / `right` / `mono`; `state: StateFlow<Depth.State>` → `width`, `height`, `smoothDepthMap: FloatBuffer?` (m), `rawDepthMap`, `smoothConfidenceMap: ByteBuffer?` (0..255), `rawConfidenceMap`; unsupported map → `IllegalStateException` | VERIFIED |
| Depth FOV | `RenderViewpoint.left/right/mono(session).state.value.fieldOfView: androidx.xr.runtime.math.FieldOfView(angleLeft, angleRight, angleUp, angleDown)`; package is `runtime.math` (stack.md row says `RenderViewpoint … fieldOfView` without the class); angles assumed radians per OpenXR `XrFovf`, left/down negative | class VERIFIED, unit UNVERIFIED |
| Planes | `Plane.subscribe(session): StateFlow<Collection<Plane>>`; `plane.state.value` → `trackingState: TrackingState {TRACKING, PAUSED, STOPPED}`, `label: PlaneLabel {UNKNOWN, WALL, FLOOR, CEILING, TABLE}`, `centerPose: Pose`, `extents: FloatSize2d`, `vertices: List<Vector2>`; `plane.type: PlaneType`; `plane.createAnchor(pose): AnchorResult` | VERIFIED (which `centerPose` axis is the normal: UNVERIFIED, see `PlaneObservation`) |
| Hands | `Hand.left(session)` / `Hand.right(session)`; `state.value.handJoints: Map<HandJointType, Pose>`, `trackingState`; `Hand.getPrimaryHandSide(contentResolver): HandSide`; pinch = `Vector3.distance(thumbTip.translation, indexTip.translation) < 0.05f` after `session.scene.perceptionSpace.transformPoseTo(pose, session.scene.activitySpace)` | VERIFIED |
| Anchors | `Anchor.create(session, pose): AnchorResult` → `AnchorCreateSuccess(anchor)` / `AnchorCreateResourcesExhausted` / `AnchorCreateTrackingUnavailable`; `anchor.state.value.pose / .trackingState`; `anchor.detach()`; `suspend fun persist(): UUID`; `Anchor.load(session, uuid)` | VERIFIED |
| Hit test | top-level `hitTest(session, Ray(origin, direction)): List<HitResult>` (`import androidx.xr.arcore.hitTest`; Java facade `Interaction.hitTest`); `HitResult(distance, hitPose, trackable)`; `Ray` / `Pose` / `Vector3` in `androidx.xr.runtime.math` | VERIFIED |
| Head pose | `ArDevice.getInstance(session).state.value.devicePose: Pose` (+ `trackingState`); `Pose.translation / rotation / forward / up / right`; `session.scene.perceptionSpace.getScenePoseFromPerceptionPose(pose)` bridges to SceneCore | VERIFIED |
| Spatial audio | `SpatialSoundPool.play(session, soundPool: SoundPool, soundID: Int, params: PointSourceParams, entity: Entity, volume: Float, priority: Int, loop: Int, rate: Float): Int` (pass all four trailing args); `PointSourceParams()` no-arg; gate on `session.scene.spatialCapabilities.contains(SpatialCapability.SPATIAL_AUDIO)`; `AudioAttributes` must not be `USAGE_MEDIA` nor `CONTENT_TYPE_MUSIC/MOVIE/SPEECH` → use `USAGE_ASSISTANCE_SONIFICATION` + `CONTENT_TYPE_SONIFICATION` | VERIFIED |
| Entities | `AnchorSpace.create(session, anchor: androidx.xr.arcore.Anchor)`; `Entity.create(session, name, pose, parent)` with 1–4-arg overloads; `entity.addChild(child)`, `entity.dispose()`; `import androidx.xr.scenecore.scene` for `session.scene` (`SessionExt`) | VERIFIED |

```kotlin
// docs/stack.md §3 → R3, verbatim snippet (VERIFIED names). Imports one per line.
import androidx.xr.arcore.Anchor
import androidx.xr.arcore.AnchorCreateSuccess
import androidx.xr.arcore.ArDevice
import androidx.xr.arcore.Depth
import androidx.xr.arcore.Hand
import androidx.xr.arcore.HandJointType
import androidx.xr.arcore.Plane
import androidx.xr.arcore.PlaneLabel
import androidx.xr.arcore.RenderViewpoint
import androidx.xr.arcore.TrackingState
import androidx.xr.arcore.hitTest
import androidx.xr.runtime.AnchorPersistenceMode
import androidx.xr.runtime.Config
import androidx.xr.runtime.DepthEstimationMode
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.HandTrackingMode
import androidx.xr.runtime.PlaneTrackingMode
import androidx.xr.runtime.RenderingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.runtime.SessionCreateSuccess
import androidx.xr.runtime.XrDevice
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Ray
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.AnchorSpace
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.PointSourceParams
import androidx.xr.scenecore.SpatialCapability
import androidx.xr.scenecore.SpatialSoundPool
import androidx.xr.scenecore.scene

// 1. Session (D7): named arg, off the main thread
val r = withContext(Dispatchers.IO) { Session.create(context = activity) }
val session = (r as? SessionCreateSuccess)?.session ?: return
val cfg = Config.Builder(session.config)
    .setPlaneTracking(PlaneTrackingMode.HORIZONTAL_AND_VERTICAL)   // SCENE_UNDERSTANDING_COARSE
    .setDepthEstimation(DepthEstimationMode.SMOOTH_ONLY)           // SCENE_UNDERSTANDING_FINE
    .setHandTracking(HandTrackingMode.BOTH)                        // HAND_TRACKING
    .setDeviceTracking(DeviceTrackingMode.SPATIAL)                 // HEAD_TRACKING
    .setAnchorPersistence(AnchorPersistenceMode.LOCAL)
    .build()
try { when (session.configure(cfg)) { is SessionConfigureSuccess -> Unit; else -> Unit /* Q5: drop modes one at a time */ } }
catch (e: SecurityException) { /* permission missing */ } catch (e: UnsupportedOperationException) { /* mode unsupported */ }

// 2. Depth → DepthFrame (proximity cone, meters)
val stereo = XrDevice.getCurrentDevice(app).isRenderingModeSupported(RenderingMode.STEREO)
val depth = if (stereo) Depth.left(session) else Depth.mono(session)
val view = if (stereo) RenderViewpoint.left(session) else RenderViewpoint.mono(session)
depth.state.collect { d -> val buf = d.smoothDepthMap ?: return@collect; val fov = view.state.value.fieldOfView /* … */ }

// 3. Planes → PlaneObservation ("wall ahead" / "floor ends")
Plane.subscribe(session).collect { planes -> planes.forEach { p -> val s = p.state.value
    if (s.trackingState == TrackingState.TRACKING && s.label == PlaneLabel.WALL) { s.centerPose; s.extents; s.vertices } } }

// 4. Head pose + hit test + anchor (door)
val head = ArDevice.getInstance(session).state.value.devicePose
val hits = hitTest(session, Ray(origin = head.translation, direction = head.forward))
when (val a = Anchor.create(session, hits.firstOrNull()?.hitPose ?: return)) {
    is AnchorCreateSuccess -> a.anchor   // later: a.anchor.state.value.pose; a.anchor.detach()
    else -> null                         // → AnchorStore.placeFromBearing returns null → Cues.doorNotFound (R4)
}

// 5. Spatial ping at the anchor (SceneCore)
val anchorSpace = AnchorSpace.create(session, anchor)
val pingEntity: Entity = Entity.create(session, "doorPing")
anchorSpace.addChild(pingEntity)
if (session.scene.spatialCapabilities.contains(SpatialCapability.SPATIAL_AUDIO)) {
    SpatialSoundPool.play(session = session, soundPool = pool, soundID = id, params = PointSourceParams(),
        entity = pingEntity, volume = 1f, priority = 0, loop = 0, rate = 1f)   // every 3 s from XrNavigationEngine
}
```

Hour-one probes (stack.md §5): Q5 `configure` → `SessionConfigureSuccess` with all four modes; Q6 non-null `smoothDepthMap`; Q11 an audibly positioned ping. Device applicability is "XR Headsets" per the guides with no per-device matrix: treat `SessionConfigureSuccess` on the Galaxy XR as the proof.

## Fallbacks

| # | Risk | Likelihood | Impact | Mitigation | Owner | Decide by |
|---|---|---|---|---|---|---|
| RK-5 | ARCore depth noisy or missing | Low | Proximity cue unreliable | Fall back to plane hit tests and detector box height as a distance proxy; only speak distances we trust | R3 | Sat 15:00 |
| RK-6 | Door one-shot returns the wrong side | Medium | Guides the user to a wall | Ask the VLM twice on two frames and require agreement; otherwise say "I can't find a door, turn slowly" | R2, R3 | Sun 09:00 |

How the code already leans toward each fallback: RK-5 → `DepthCone.minTrustedDepth` drops low-confidence pixels and returns `null` (silence, not a guess) when nothing is trusted; `XrPerceptionSession.sampleDepthAlongBearing` documents the `hitTest` path first, the depth-map path second; the detector-box-height proxy would feed a second `ProximityMonitor`-like source in `XrNavigationEngine.proximityLoop` (not written: decide at Sat 15:00). RK-6 → the vote is R1's; `XrAnchorStore.placeFromBearing` places whatever bearing wins and returns `null` for `NONE`, so the controller speaks `Cues.doorNotFound`. A-6 → `SpatialAudioPinger.isSpatial == false` after the probe; R1 then plays `Earcon.PING` on each `DoorBearing` (see Contract issues).

## How to test without the headset

- **What the NoOps do.** `AppGraph` wires `NoOpHeadPoseSource` / `NoOpAnchorStore` / `NoOpNavigationEngine` whenever `!DeviceProfile.isXr || BuildConfig.USE_FAKES`, i.e. on the S25 Ultra, on the laptop emulator and for `./gradlew installDebug -PuseFakes=true` on any device. They are complete: Navigation reports not running, `cues` and `detections` are empty flows, `placeFromBearing` returns `null` (so "find the door" ends in `Cues.doorNotFound`, not a crash), `pose` is `null` (R1 skips the hold-still check). Nothing logs a `TODO`.
- **What the test fakes do** (`app/src/test/kotlin/com/vysion/xr/perception/PerceptionFakes.kt`): `FakePerceptionSource` lets a test push depth maps (`depth.tryEmit(depthFrame(centerM = 0.5f, …))`), planes, head poses and anchor poses, and records `sampleDepthAlongBearing` calls; `FakeFrameSource` / `FakeInferenceEngine` are the minimum of R1's and R2's contracts (frames are allocated `Bitmap` shells, never read); `FakeAnchorStore.track` is a channel the test fills and closes to simulate tracking loss; `FakePinger` counts attach / ping / detach.
- **Unit tests** (JUnit 4, JVM, `./gradlew testDebugUnitTest --tests "com.vysion.xr.perception.*"`): `ProximityMonitorTest` (12: cone bounds, exact cone test, trusted minimum, 1.0 m threshold, 2 s debounce, stale maps, bearing → pixel, median window), `PlaneSemanticsTest` (11: wall ahead / beyond / off-polygon / turned, nearest wall, floor ends, adjacent floor continues, out of range, untracked, primitives), `RelativeGeometryTest` (10: 12 / 3 / 6 / 10 o'clock, the SRS "2 o'clock, 3 meters" example, half-meter rounding, bearing offsets, wrap, yaw/pitch/roll from forward/up), `AngularSpeedTest` (5), `XrAnchorStoreTest` (5: placement needs a tracked pose, samples at yaw + offset, NONE / no depth / no anchor, `track` follows a 180° turn and completes on STOPPED), `XrNavigationEngineTest` (7: idempotent start / stop, proximity gated by pause, detector skips frames while paused and survives `InferenceException`, 3 s bearing + ping then `Arrived`, guidance paused with the rest, `TargetLost` on completion and on 6 s silence, plane cues gated per type). 57 tests including `core/UnitsTest` ran green on 2026-10-17 against the pinned jars (Kotlin 2.4.21, coroutines 1.11.0, JUnit 4.13.2) outside Gradle, because the other roles' packages were still empty and `AppGraph` could not compile; the first `./gradlew testDebugUnitTest` on a complete tree is the real gate.
- **`-PuseFakes=true` on the headset** gives you the NoOps for R3 (not the XR classes) so R1/R2/R4 can work without a session; to exercise the XR classes you need the headset without the flag.
- **The 180° test (FR-N4, SRS "test by turning 180 degrees and back").** On the headset: (1) stand 3 m from a door, say "find the door"; wait for `DoorBearing(clock=12, distanceM=3.0, inView=true)` and the ping ahead. (2) Turn in place 180° over ~3 s; the next cues must read `clock=6, inView=false`, the ping must sound behind you, and `adb logcat -s vysion | grep TargetLost` must stay empty. (3) Hold for 10 s turned away (longer than `TRACK_STALE_NANOS` = 6 s): still no `TargetLost`, because the native anchor keeps TRACKING out of view and the head pose keeps the samples flowing. (4) Turn back: `clock=12, inView=true` within one 3 s tick. (5) Say "stop" (R1 calls `clearTarget`): exactly one `pinger.detach`, no further bearings. Attach the logcat excerpt to the PR.

## Definition of done

Per [`CONTRIBUTING.md`](../../CONTRIBUTING.md) "Definition of done for a task": FR id in the PR title (`r3: FR-N1: …`), `./gradlew assembleDebug testDebugUnitTest` and `scripts/check_privacy.sh` green, the module checkbox above ticked in the same PR, every number in `docs/benchmarks.md`. Device evidence R3 must attach:

1. A-5: one `vysion` logcat line with the depth map size and a measured minimum depth, one with a plane label and extents, and `XrPerceptionSession.state == RUNNING` (Sat 12:00).
2. A-6: the ping probe result (audible? positioned?) written into `docs/assumptions.md` (Sat 13:00).
3. FR-N1: `vysion.latency` lines "proximity cue N ms after the depth sample" with N ≤ 300 and ≥ 2 s apart, copied into `docs/benchmarks.md` "Proximity warning after threshold crossed".
4. FR-N3: an `anchor … placed` line and three consecutive successful door finds from different starting bearings in the demo room (gate 7 verification in `plan.md`), each with its spoken bearing/distance.
5. FR-N4: the 180° test transcript above. FR-N5: a `WallAhead` line inside 1.5 m and none outside.

## Contract issues

1. **No path for the `Activity` that `Session.create` needs.** `AppGraph` keeps `perceptionSession` private and `AppGraph.start()` only calls `modeController.start()`, while `Session.create(context = activity)` requires the resumed `Activity` as `LifecycleOwner` (stack.md D7). Workaround in place: `XrPerceptionSession` registers `Application.ActivityLifecycleCallbacks` in its constructor, remembers the resumed activity weakly, and `ensureStarted()` (called by `XrNavigationEngine.start()` and `XrAnchorStore.placeFromBearing`) creates the session from it. Proposed change (R1, `AppGraph.kt`, additive): expose `val perception: XrPerceptionSession?` and call `graph.perception?.start(this)` from `MainActivity.onStart` after the XR permissions are granted, `stop()` from `onStop`.
2. **A-6 fallback has no contract path.** If spatial audio is unavailable, the SRS fallback is `Speaker.earcon(PING)` on each bearing, but R3 holds no `Speaker` and `NavigationCue.DoorBearing` carries no "pinged" flag. Workaround: `SpatialAudioPinger.isSpatial: StateFlow<Boolean?>` (package-local) and this note. Proposed change (R5 merges alone, additive): `data class DoorBearing(val clock: Int, val distanceM: Float, val inView: Boolean, val pinged: Boolean = true)`; R1 plays `Earcon.PING` when `pinged == false`.
3. **`HeadPose` has no position.** Bearing and distance need the head position, so R3 keeps an internal `RawHeadPose` with it; no contract change requested. If R1 ever needs the position (transcript anchoring, FR-T2 P1), add `positionM: Vec3?` additively.
4. **Stale rows in `docs/stack.md` §3-R3 found by `javap` on 2026-10-17** (for R5 to append under "Corrections found at build time"): `FieldOfView` lives in `androidx.xr.runtime.math` (not `androidx.xr.runtime`); `Session` also exposes `coroutineScope` and `activity`; `DeviceTrackingMode` has `LAST_KNOWN`, `SPATIAL_LAST_KNOWN`, `INERTIAL`, `INERTIAL_LAST_KNOWN` besides `DISABLED` / `SPATIAL`; the runtime jar cached locally is `1.1.0-alpha01` (rc02's POM only), consistent with D2.
5. **FR-D4 (P2) needs a region in the describe call.** `InferenceEngine.describe(bitmap, prompt)` has no region parameter; pointing would need `describe(bitmap.crop(region), …)` done by R1 from a `NormalizedBox` R3 provides. Proposed additive type: `data class PointingRegion(val box: NormalizedBox)` in `core`, emitted by a future `PointingSource`. Not started (P2).

## Dependencies needed

None: everything R3 imports is pinned in `gradle/libs.versions.toml` (`androidx.xr.runtime:runtime`, `androidx.xr.arcore:arcore`, `androidx.xr.scenecore:scenecore` 1.0.0-rc02, `kotlinx-coroutines` 1.11.0, `junit` 4.13.2, `kotlinx-coroutines-test` 1.11.0). One **asset**, not a dependency: `app/src/main/assets/sounds/ping.ogg`, the ping earcon `SpatialAudioPinger` loads (`PING_ASSET`); R4 owns earcons (`voice/Earcons`), so R4 adds the file (mono, short, `USAGE_ASSISTANCE_SONIFICATION`) and R3 references the path only.
