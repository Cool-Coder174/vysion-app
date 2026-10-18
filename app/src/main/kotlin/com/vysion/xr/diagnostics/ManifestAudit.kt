package com.vysion.xr.diagnostics

import android.app.Application
import android.content.pm.PackageManager
import android.util.Log

/**
 * Runtime proof of the privacy claim (ADR-0003; NFR Offline): reads the permissions the installed APK
 * actually requests and reduces them to one string for the overlay line `Network permission: <value>`.
 *
 * - Provides: the `networkPermission` field of [com.vysion.xr.core.DiagnosticsSnapshot] (FR-G5).
 * - Owner: R5. SRS §8: "Diagnostics overlay ... 'Network permission: none'", due Sat 18:00.
 * - API: `PackageManager.getPackageInfo(String, PackageInfoFlags)` + `PackageInfo.requestedPermissions`,
 *   docs/stack.md §3 → R5 (VERIFIED, DV-PS + DV-AC). `PackageInfoFlags` is API 33, unguarded with minSdk 34.
 * - Assumption: none on the device; A-14 (the pinned build assembles) is what makes the merged manifest real.
 *
 * Why the MERGED manifest matters: our own manifest has no network permission, but a library can merge
 * one in. `PackageManager` reports what was actually installed, so the overlay cannot lie (NFR Honesty).
 * The classification set is identical to `scripts/check_privacy.sh` NET_PERMS, so CI and the overlay
 * agree on what counts as a network permission (docs/stack.md §2.7 / §3-R5 and ADR-0003 name the full set;
 * the manifest strips INTERNET and ACCESS_NETWORK_STATE with tools:node="remove" guards).
 *
 * The audit runs once (the manifest cannot change while the process lives) and never throws: any
 * failure yields `null`, which the overlay renders as "not measured" (INV-7).
 */
class ManifestAudit(app: Application) {

    private val packageManager: PackageManager? = app.packageManager
    private val packageName: String? = app.packageName

    /**
     * `"none"` when no network permission is requested, otherwise the offending short names, sorted and
     * comma-separated (e.g. `"ACCESS_NETWORK_STATE, INTERNET"`). `null` if the audit itself failed.
     */
    fun networkPermission(): String? {
        val pm = packageManager ?: return null
        val name = packageName ?: return null
        return try {
            val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
            val requested: Array<String>? = pm.getPackageInfo(name, flags).requestedPermissions
            val result = classify(requested)
            // plan.md ## Security "Detection": the audit result goes to logcat under vysion.security.
            Log.i(SECURITY_TAG, "manifest audit: network=$result requested=${requested.orEmpty().joinToString()}")
            result
        } catch (t: Throwable) {
            Log.w(TAG, "ManifestAudit.networkPermission failed; overlay shows 'not measured'", t)
            null
        }
    }

    companion object {
        private const val TAG = "vysion"
        private const val SECURITY_TAG = "vysion.security"

        /** Overlay value when the APK requests no network permission (SRS §11, FR-G5). */
        const val NONE: String = "none"

        private const val PERMISSION_PREFIX = "android.permission."

        /**
         * Exactly the set `scripts/check_privacy.sh` greps for (NET_PERMS), so the CI gate and the runtime
         * audit cannot disagree. String literals on purpose: no Android class in the pure, unit-tested path.
         */
        val NETWORK_PERMISSIONS: Set<String> = setOf(
            "${PERMISSION_PREFIX}INTERNET",
            "${PERMISSION_PREFIX}ACCESS_NETWORK_STATE",
            "${PERMISSION_PREFIX}ACCESS_WIFI_STATE",
            "${PERMISSION_PREFIX}CHANGE_NETWORK_STATE",
        )

        /**
         * Pure classification (unit-tested): `null` or no network permission → [NONE]; otherwise the
         * offending permissions without the `android.permission.` prefix, de-duplicated, sorted, joined
         * with `", "`. `WAKE_LOCK`, `CAMERA` and the XR permissions are never network permissions.
         */
        fun classify(requestedPermissions: Array<String>?): String {
            val offending = requestedPermissions
                .orEmpty()
                .filter { it in NETWORK_PERMISSIONS }
                .map { it.removePrefix(PERMISSION_PREFIX) }
                .distinct()
                .sorted()
            return if (offending.isEmpty()) NONE else offending.joinToString(", ")
        }
    }
}
