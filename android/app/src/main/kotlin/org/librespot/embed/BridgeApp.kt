package org.librespot.embed

import android.app.Application

/**
 * Exists for one reason: to catch failures in the parts of the app that have no screen.
 *
 * [Debug.install] used to run from the launcher screen, so a crash in [BridgeService] --
 * which Android can start on its own after killing the process, with no Activity
 * anywhere -- went unreported. That is exactly the case the reports were written for: a
 * television nobody can attach a debugger to.
 *
 * This runs before any component of the app, screen or not.
 */
class BridgeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (!Settings(this).debug) return

        Debug.install(this)
        // Anything a crashed run left behind goes now: that process never lived long
        // enough to send it.
        Debug.flush(this)
    }
}
