package com.vysion.xr.platform

import android.content.Context

/**
 * Whether first-launch onboarding has been finished (designs pages 2-8). One boolean in one private
 * SharedPreferences file, and nothing else: no frame, no transcript, no user content (ADR-0009 amends
 * ADR-0003 for exactly this flag). Owner: R1.
 */
class OnboardingStore(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var done: Boolean
        get() = prefs.getBoolean(KEY_DONE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DONE, value).apply()
        }

    private companion object {
        const val FILE = "vysion_onboarding"
        const val KEY_DONE = "onboarding_done"
    }
}
