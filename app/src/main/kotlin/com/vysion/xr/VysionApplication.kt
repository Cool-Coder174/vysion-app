package com.vysion.xr

import android.app.Application

/**
 * Process entry point. Owns the single [AppGraph] (ADR-0008: manual wiring, no DI framework).
 * Nothing here touches the network; there is no network permission (ADR-0003).
 */
class VysionApplication : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
