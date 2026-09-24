package org.librespot.embed

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings as SystemSettings
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * [NowPlayingView] in a window over every app, for when the television is in the group.
 *
 * Casting to a group the television belongs to makes it open Google's Default Media
 * Receiver, full screen, on top of this app -- with nothing to show. An activity cannot
 * come back over it: Android 14 blocks activity starts from the background, and the
 * receiver's launch even reports itself as the person leaving. A window drawn by the
 * service is not an activity start, so it stays on top. It needs "Display over other
 * apps", which the person grants once in the television's settings.
 *
 * Back closes it, and so does Home: it must never be something the remote cannot get out
 * of. The other keys do nothing -- closing on an arrow nudged by accident only uncovered
 * the blank receiver underneath.
 */
object Overlay {
    private const val TAG = "CrossfadeBridge"

    private var root: FrameLayout? = null
    private var homeWatcher: BroadcastReceiver? = null

    val isShown get() = root != null

    fun allowed(context: Context) = SystemSettings.canDrawOverlays(context)

    /** The settings page where the person turns the permission on for this app. */
    fun permissionIntent(context: Context) =
        Intent(
            SystemSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun show(context: Context) {
        if (isShown || !allowed(context)) return
        val app = context.applicationContext
        val wm = app.getSystemService(WindowManager::class.java)

        val view = object : FrameLayout(app) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                // Volume is for the speakers, not for closing the picture.
                if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                    event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                    event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
                ) return super.dispatchKeyEvent(event)
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    hide(app)
                }
                return true
            }
        }.apply {
            addView(NowPlayingView(app))
            isFocusable = true
            isFocusableInTouchMode = true
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        )

        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            // Permission revoked between the check and here: nothing to show, and nothing
            // worth crashing the service over.
            Log.w(TAG, "could not show the overlay: $e")
            return
        }
        view.requestFocus()
        root = view

        // Home goes to the system, never to this window; this broadcast is how an overlay
        // learns the person pressed it.
        val watcher = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                // The same broadcast closes every system dialog; only leaving for the home
                // screen or the app switcher should take the picture down with it.
                val reason = intent.getStringExtra("reason")
                if (reason == null || reason == "homekey" || reason == "recentapps") hide(app)
            }
        }
        val filter = IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(watcher, filter, Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(watcher, filter)
        }
        homeWatcher = watcher
    }

    fun hide(context: Context) {
        val app = context.applicationContext
        root?.let { view ->
            runCatching { app.getSystemService(WindowManager::class.java).removeView(view) }
        }
        root = null
        homeWatcher?.let { runCatching { app.unregisterReceiver(it) } }
        homeWatcher = null
    }
}
