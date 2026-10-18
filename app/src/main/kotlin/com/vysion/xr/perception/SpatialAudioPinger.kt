package com.vysion.xr.perception

import android.media.SoundPool
import android.util.Log
import com.vysion.xr.core.AnchorId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What [XrNavigationEngine] needs from a pinger: attach to the current target, ping on each guidance
 * tick, detach when the target is cleared. Internal so tests can fake it (ADR-0005: nothing here crosses
 * the package).
 */
internal interface AnchorPinger {
    /**
     * A-6: `null` until known, `true` while spatial pings play, `false` when the last ping could not play
     * spatially. [XrNavigationEngine] reads it right after [ping] to set `NavigationCue.DoorBearing.pinged`.
     */
    val isSpatial: StateFlow<Boolean?>

    suspend fun attach(anchor: AnchorId)
    fun ping()
    fun detach()
    fun release()
}

/**
 * SceneCore point-source ping at the door anchor, every 3 s while guiding (FR-N3 "with a spatial-audio
 * ping from the anchor"). The engine calls [ping] on its guidance tick; this class owns the `SoundPool`
 * and the anchor-parented entity. All device calls are stubs in the skeleton.
 *
 * - Owner: R3. SRS due Sat 21:00 ("a SceneCore point source at the anchor playing a ping every 3 s
 *   while guiding"); the A-6 check is Sat 13:00 (play one ping, is it audible and positioned?).
 * - API (docs/stack.md → R3 "Spatial audio", "Entities", VERIFIED by javap on scenecore rc02):
 *   `AnchorSpace.create(session, anchor)`, `Entity.create(session, "doorPing")`, `anchorSpace.addChild(entity)`,
 *   `SpatialSoundPool.play(session, soundPool, soundID, PointSourceParams(), entity, volume, priority, loop, rate)`,
 *   gated on `session.scene.spatialCapabilities.contains(SpatialCapability.SPATIAL_AUDIO)`.
 *   SoundPool attributes: `USAGE_ASSISTANCE_SONIFICATION` + `CONTENT_TYPE_SONIFICATION` (never
 *   `USAGE_MEDIA` / `CONTENT_TYPE_MUSIC`, which SceneCore refuses to spatialize).
 * - Assumption A-6: "SceneCore spatial audio point source is available and audible through the headset
 *   speakers" (Sat 13:00). If it fails: "non-spatial earcon through `Speaker.earcon(PING)`; say the
 *   bearing more often (every 2 s)". [isSpatial] drives both halves: [XrNavigationEngine] copies it into
 *   `NavigationCue.DoorBearing.pinged` (R1 plays R4's `Earcon.PING` through the `Speaker` when false)
 *   and shortens its guidance period to 2 s. A ping that cannot play for any reason (no entity, no
 *   sound loaded, `SPATIAL_AUDIO` missing, `play` failed) reports `false`, so the user always hears a
 *   ping. The ping sound itself is an R4 earcon asset (`Earcons`), not a string; see
 *   docs/modules/r3-spatial-perception.md "Dependencies needed".
 */
class SpatialAudioPinger(private val session: XrPerceptionSession) : AnchorPinger {

    private var entity: AnchorEntity? = null
    private var soundPool: SoundPool? = null
    private var soundId: Int = -1

    private val _isSpatial = MutableStateFlow<Boolean?>(null)

    /** `null` until the A-6 probe ran; `false` means R1 should play the plain `Earcon.PING` instead. */
    override val isSpatial: StateFlow<Boolean?> = _isSpatial.asStateFlow()

    /**
     * Real implementation:
     * ```
     * detach()
     * entity = session.createAnchorEntity(anchor, "doorPing") ?: return
     * if (soundPool == null) {
     *     val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
     *         .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
     *     soundPool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attrs).build().also { pool ->
     *         // Loading is asynchronous: the id is usable only after the listener reports status 0.
     *         pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) soundId = id }
     *         pool.load(session.assets.openFd(PING_ASSET), 1)   // asset from R4 (r4 page, Speaker P0 item)
     *     }
     * }
     * _isSpatial.value = session.spatialAudioSupported.value   // set by XrPerceptionSession.open() (A-6 probe)
     * ```
     * `XrPerceptionSession.app` is private: add `internal val assets: AssetManager get() = app.assets` to
     * `XrPerceptionSession` (package-internal, ADR-0005 unaffected) for the `openFd` above. `openFd` needs the
     * asset stored uncompressed; AAPT leaves `.ogg` uncompressed by default. Keep [soundId] at -1 until the
     * listener fires, and in [ping] return early WITHOUT touching [isSpatial] while `soundPool != null` but the
     * sound is still loading (a few ms for a 4 KB file), so a load still in flight never reports A-6 as failed.
     */
    override suspend fun attach(anchor: AnchorId) {
        detach()
        entity = session.createAnchorEntity(anchor, ENTITY_NAME)
        Log.w(
            XrPerceptionSession.TAG,
            "TODO(FR-N3) SpatialAudioPinger.attach: SoundPool(USAGE_ASSISTANCE_SONIFICATION).load(\"$PING_ASSET\"); " +
                "probe SpatialCapability.SPATIAL_AUDIO (A-6)",
        )
    }

    /**
     * One ping from the anchor entity; called every 3 s by the guidance loop (2 s while [isSpatial] is
     * false). Never throws. Updates [isSpatial] on every call so the cue of this tick carries the truth.
     */
    override fun ping() {
        val e = entity
        val pool = soundPool
        if (e == null || pool == null || soundId < 0) {
            Log.w(
                XrPerceptionSession.TAG,
                "TODO(FR-N3) SpatialAudioPinger.ping: SpatialSoundPool.play(session, soundPool, soundID, " +
                    "PointSourceParams(), entity, 1f, 0, 0, 1f)",
            )
            _isSpatial.value = false // nothing played: R1 plays the non-spatial Earcon.PING (A-6 fallback)
            return
        }
        // SoundPool.play returns 0 on failure; playPointSource returns -1 when SPATIAL_AUDIO is missing.
        val stream = session.playPointSource(e, pool, soundId)
        _isSpatial.value = stream > 0
    }

    override fun detach() {
        entity?.let(session::disposeAnchorEntity)
        entity = null
    }

    override fun release() {
        detach()
        soundPool?.release()
        soundPool = null
        soundId = -1
    }

    companion object {
        const val ENTITY_NAME: String = "doorPing"

        /** Asset path of the ping earcon; the file is R4's (Earcons), see the module doc. */
        const val PING_ASSET: String = "sounds/ping.ogg"
    }
}
