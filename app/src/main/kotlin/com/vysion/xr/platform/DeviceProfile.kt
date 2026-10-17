package com.vysion.xr.platform

import android.content.Context
import android.os.Build

/**
 * One APK for the Galaxy XR and the S25 Ultra (ADR-0006). Detected once at startup; `AppGraph`
 * picks the spatial or the flat 2D UI and the real or `NoOp` perception modules from it.
 *
 * Assumption A-12: [isXr] is `false` on the S25 Ultra. If it is not, the fallback is a
 * `BuildConfig` flag (see `docs/assumptions.md`).
 */
data class DeviceProfile(
    val isXr: Boolean,
    val model: String,
    val sdkInt: Int,
) {
    val displayName: String get() = if (isXr) "$model (Android XR)" else "$model (2D fallback)"

    companion object {
        /**
         * Android XR declares this system feature; the manifest `<uses-feature>` for it is
         * `required="false"` so the APK installs on the phone. Source and verification status:
         * `docs/stack.md` → R1.
         */
        const val FEATURE_XR_SPATIAL: String = "android.software.xr.api.spatial"

        fun detect(context: Context): DeviceProfile = DeviceProfile(
            isXr = context.packageManager.hasSystemFeature(FEATURE_XR_SPATIAL),
            model = Build.MODEL ?: "unknown",
            sdkInt = Build.VERSION.SDK_INT,
        )
    }
}
