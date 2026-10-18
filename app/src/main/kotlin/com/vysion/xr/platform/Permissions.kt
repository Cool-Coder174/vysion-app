package com.vysion.xr.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * The runtime permission list and the request helper for `MainActivity` (FR-G1, SRS §8 R1 task
 * "permissions requested with spoken explanations", due Sat 11:00). Owner: R1.
 *
 * Every string here is exactly the `<uses-permission>` name in `app/src/main/AndroidManifest.xml`.
 * The XR ones equal the `androidx.xr.runtime.manifest.ManifestPermission` constants (docs/stack.md
 * → R1 "Runtime permission request", VERIFIED XR-PS/XR-AC; values re-checked with `javap -constants`
 * against runtime-manifest in the Gradle cache), spelled out as literals because `runtime-manifest`
 * is not on the compile classpath of the pinned `androidx.xr.runtime:runtime` (module doc,
 * "Dependencies needed"). Per docs/stack.md §2.7 all six are "dangerous", so they are declared in
 * the manifest AND requested at runtime.
 *
 * Assumption A-12: on the S25 Ultra ([DeviceProfile.isXr] false) the XR permissions do not exist,
 * so the platform denies them silently without a dialog; [required] therefore lists only
 * `CAMERA` + `RECORD_AUDIO` there, and the app never blocks on an XR grant on the phone (ADR-0006).
 *
 * The spoken explanation itself is `Cues.permissionExplanation` (R4, voice/Cues.kt), reached
 * through [ModeController.speakPermissionExplanation]; nothing here builds a sentence (INV-4).
 */
object Permissions {

    /** World-facing camera, Camera2 id "0" (A-1, FR-T1, FR-D1, FR-N2). */
    const val CAMERA: String = Manifest.permission.CAMERA

    /** `SpeechRecognizer` input (FR-G2). */
    const val RECORD_AUDIO: String = Manifest.permission.RECORD_AUDIO

    /** Planes, anchors, hit tests (R3, FR-N3/FR-N5). = `ManifestPermission.SCENE_UNDERSTANDING_COARSE`. */
    const val SCENE_UNDERSTANDING_COARSE: String = "android.permission.SCENE_UNDERSTANDING_COARSE"

    /** Depth maps: proximity (FR-N1). = `ManifestPermission.SCENE_UNDERSTANDING_FINE`. */
    const val SCENE_UNDERSTANDING_FINE: String = "android.permission.SCENE_UNDERSTANDING_FINE"

    /** Pinch-and-hold push-to-talk (FR-G2, P1). = `ManifestPermission.HAND_TRACKING`. */
    const val HAND_TRACKING: String = "android.permission.HAND_TRACKING"

    /** Head pose for bearings (FR-N3/FR-N4). = `ManifestPermission.HEAD_TRACKING`. */
    const val HEAD_TRACKING: String = "android.permission.HEAD_TRACKING"

    /** The two permissions without which no mode works, on either device. */
    val core: List<String> = listOf(CAMERA, RECORD_AUDIO)

    /** The Android XR permissions; only meaningful when [DeviceProfile.isXr] is true. */
    val xr: List<String> = listOf(
        SCENE_UNDERSTANDING_COARSE,
        SCENE_UNDERSTANDING_FINE,
        HAND_TRACKING,
        HEAD_TRACKING,
    )

    /** Everything this app may ask for, in manifest order. */
    val all: List<String> = core + xr

    /** What to request on this device: all six on the headset, the two core ones on the phone (A-12). */
    fun required(isXr: Boolean): List<String> = if (isXr) all else core

    /** `true` when [permission] is granted right now. */
    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * FR-N9 "where am I", asked only the first time the command is used, never at launch, so they are not in [all].
     * Both names go in one request (Android 12+ ignores a fine-only request); only a grant of fine location counts,
     * since approximate location is fuzzed to kilometers and names no street.
     */
    val location: List<String> = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** FR-N9: whether the location dialog's [grants] (or an earlier grant) include precise location. */
    fun preciseLocationGranted(grants: Map<String, Boolean>, context: Context): Boolean =
        grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    /** The subset of [required] that is not granted yet; empty means "start the app". */
    fun missing(context: Context, isXr: Boolean): List<String> =
        required(isXr).filterNot { isGranted(context, it) }

    /** The app can start when the core pair is granted; XR denials degrade R3 features without blocking (ADR-0006). */
    fun canStart(context: Context): Boolean = core.all { isGranted(context, it) }

    /** Pure form of [canStart] for a grant map returned by the launcher. */
    fun canStart(grants: Map<String, Boolean>, context: Context): Boolean =
        core.all { grants[it] == true || isGranted(context, it) }

    /**
     * Registers the standard multiple-permission contract. Must be called before the activity is
     * STARTED (a property initializer or `onCreate`), per `ActivityResultCaller` rules. The caller
     * launches it with `launch(missing.toTypedArray())` after speaking the explanation.
     */
    fun register(
        activity: ComponentActivity,
        onResult: (Map<String, Boolean>) -> Unit,
    ): ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions(), onResult)
}
