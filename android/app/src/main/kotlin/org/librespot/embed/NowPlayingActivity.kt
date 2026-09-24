package org.librespot.embed

import android.app.Activity
import android.os.Bundle
import android.view.Window

/**
 * What is playing, as a screen of the app: opened from the button, or on its own when a
 * song starts while the app is in front and nothing may draw over other apps.
 *
 * The picture itself is [NowPlayingView], shared with [Overlay].
 */
class NowPlayingActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(NowPlayingView(this))
    }
}
