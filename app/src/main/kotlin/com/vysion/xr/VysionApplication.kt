package com.vysion.xr

import android.app.Application

/**
 * Process entry point. Owns the current [AppGraph] (ADR-0008: manual wiring, no DI framework).
 * Nothing here touches the network; there is no network permission (ADR-0003).
 *
 * `AppGraph.release()` (from `MainActivity.onDestroy` when finishing) is terminal: it destroys the
 * recognizer, shuts down TTS and cancels the scope. When the process stays cached and the app is launched
 * again, the next `MainActivity` (or a debug `BenchmarkReceiver` broadcast) must not get that dead graph,
 * so [graph] builds a fresh one once the current one [AppGraph.isReleased].
 */
class VysionApplication : Application() {

    private var current: AppGraph? = null

    /**
     * The live graph. Read on the main thread only (Application.onCreate, MainActivity.onCreate,
     * BenchmarkReceiver.onReceive), as the AppGraph constructor requires.
     */
    val graph: AppGraph
        get() = current?.takeUnless { it.isReleased } ?: AppGraph(this).also { current = it }

    override fun onCreate() {
        super.onCreate()
        current = AppGraph(this)
    }
}
