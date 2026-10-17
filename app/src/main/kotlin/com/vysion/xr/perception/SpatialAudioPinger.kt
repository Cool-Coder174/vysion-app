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
 *   bearing more often (every 2 s)". That fallback is R4's earcon played by R1 on each `DoorBearing`
 *   cue; [isSpatial] tells R1 (through the module doc's contract note) whether it is needed. The ping
 *   sound itself is an R4 earcon asset (`Earcons`), not a string; see docs/modules/r3-spatial-perception.md
 *   "Dependencies needed".
 */
class SpatialAudioPinger(private val session: XrPerceptionSession) : AnchorPinger {

    private var entity: AnchorEntity? = null
    private var soundPool: SoundPool? = null
    private var soundId: Int = -1

    private val _isSpatial = MutableStateFlow<Boolean?>(null)

    /** `null` until the A-6 probe ran; `false` means R1 should play the plain `Earcon.PING` instead. */
    val isSpatial: StateFlow<Boolean?> = _isSpatial.asStateFlow()

    /**
     * Real implementation:
     * ```
     * detach()
     * entity = session.createAnchorEntity(anchor, "doorPing") ?: return
     * if (soundPool == null) {
     *     val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
     *         .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
     *     soundPool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attrs).build().also { pool ->
     *         soundId = pool.load(session.app.assets.openFd(PING_ASSET), 1)   // asset from R4's Earcons
     *     }
     * }
     * _isSpatial.value = session.spatialAudioSupported.value
     * ```
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

    /** One ping from the anchor entity; called every 3 s by the guidance loop. Never throws. */
    override fun ping() {
        val e = entity
        val pool = soundPool
        if (e == null || pool == null || soundId < 0) {
            Log.w(XrPerceptionSession.TAG, "TODO(FR-N3) SpatialAudioPinger.ping: SpatialSoundPool.play(session, soundPool, soundID, PointSourceParams(), entity, 1f, 0, 0, 1f)")
            return
        }
        val stream = session.playPointSource(e, pool, soundId)
        if (stream < 0) _isSpatial.value = false
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
