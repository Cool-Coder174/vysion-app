package com.vysion.xr.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-as-tests for the pure classification behind "Network permission: <value>" (FR-G5, ADR-0003).
 * The set under test is the same one `scripts/check_privacy.sh` greps for, so CI and the overlay agree.
 */
class ManifestAuditTest {

    @Test
    fun `no requested permissions is none`() {
        assertEquals("none", ManifestAudit.classify(null))
        assertEquals("none", ManifestAudit.classify(emptyArray()))
    }

    @Test
    fun `the demo manifest is none`() {
        // Exactly what app/src/main/AndroidManifest.xml declares, plus the transitive WAKE_LOCK the merged
        // debug APK carried in the verified build (docs/stack.md §2.7). None of these is a network permission.
        val demoManifest = arrayOf(
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.SCENE_UNDERSTANDING_COARSE",
            "android.permission.SCENE_UNDERSTANDING_FINE",
            "android.permission.HAND_TRACKING",
            "android.permission.HEAD_TRACKING",
            "android.permission.WAKE_LOCK",
            "com.vysion.xr.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        )
        assertEquals(ManifestAudit.NONE, ManifestAudit.classify(demoManifest))
    }

    @Test
    fun `internet is reported by its short name`() {
        assertEquals(
            "INTERNET",
            ManifestAudit.classify(arrayOf("android.permission.CAMERA", "android.permission.INTERNET")),
        )
    }

    @Test
    fun `every offending permission is listed, sorted and de-duplicated`() {
        val merged = arrayOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.INTERNET",
            "android.permission.CHANGE_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
        )
        assertEquals(
            "ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_NETWORK_STATE, INTERNET",
            ManifestAudit.classify(merged),
        )
    }

    @Test
    fun `a look-alike from another namespace is not a network permission`() {
        assertEquals("none", ManifestAudit.classify(arrayOf("com.example.permission.INTERNET", "INTERNET")))
    }

    @Test
    fun `classification set matches the privacy gate regex`() {
        // scripts/check_privacy.sh NET_PERMS=
        //   'android\.permission\.(INTERNET|ACCESS_NETWORK_STATE|ACCESS_WIFI_STATE|CHANGE_NETWORK_STATE)'
        val gate = Regex(
            "android\\.permission\\.(INTERNET|ACCESS_NETWORK_STATE|ACCESS_WIFI_STATE|CHANGE_NETWORK_STATE)",
        )
        assertEquals(4, ManifestAudit.NETWORK_PERMISSIONS.size)
        assertTrue(ManifestAudit.NETWORK_PERMISSIONS.all { gate.matches(it) })
    }
}
