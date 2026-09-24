package org.librespot.embed

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.Executors

/**
 * Keeps librespot alive while the television is off.
 *
 * Three locks, each for a different thing Android switches off to save power, and all
 * three are needed -- dropping any one produces a different flavour of "it works for a
 * while and then goes quiet".
 */
class BridgeService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var foreground = false
    private var destroyed = false

    enum class State { STOPPED, STARTING, RUNNING }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foreground) {
            startForeground(NOTIFICATION_ID, buildNotification())
            acquireLocks()
            foreground = true
        }

        // Read from the settings rather than the Intent: START_STICKY redelivers this
        // service with a null Intent after the system kills the app, and defaults picked
        // there would quietly cast nowhere and crossfade by a number nobody chose.
        if (Settings(this).group == null) {
            // The only audio backend is the HTTP one, so with nowhere to cast this would
            // serve a stream to nobody while claiming to play.
            Log.e(TAG, "no cast target chosen; stopping the service")
            stopSelf()
            return START_NOT_STICKY
        }

        // Start again on a service that is already up is not a no-op: the native worker
        // may have died under it, and pressing Start is how someone says it is not
        // working. Only a start that is in flight, or a worker still alive, is left be.
        if (state == State.STOPPED) launch()
        else if (state == State.RUNNING) checkAlive()
        return START_STICKY
    }

    /**
     * Starts the native side off the main thread.
     *
     * `nativeStart` waits for the stream to be served, up to ten seconds; on the main
     * thread that froze the screen and, worse, meant the screen asked "is it running?"
     * before the answer existed.
     */
    private fun launch() {
        publish(State.STARTING)
        val settings = Settings(this)
        val castTo = settings.group
        val secs = settings.crossfadeSecs
        val albums = settings.crossfadeAlbums
        val dir = filesDir.absolutePath
        NATIVE.execute {
            // A dead worker is still parked on the native side until stopped.
            Librespot.nativeStop()
            val started = Librespot.nativeStart(DEVICE_NAME, BIND, castTo, secs, albums, dir)
            main.post {
                if (destroyed) return@post
                if (started) {
                    publish(State.RUNNING)
                    main.postDelayed(watchdog, WATCHDOG_MS)
                    main.removeCallbacks(watchPlayback)
                    watchPlayback.run()
                } else {
                    // Almost always "no credentials yet". Staying up would just hold
                    // the locks for nothing and look like it is working.
                    Log.e(TAG, "librespot did not start; stopping the service")
                    Debug.report(this, "librespot no arrancó")
                    stopSelf()
                }
            }
        }
    }

    /**
     * Restarts the native side if its worker ended by itself.
     *
     * Spirc ends when its connection does, and after days up a dropped network or an
     * expired session does exactly that. Nothing else notices: the notification stays,
     * the screen says running, and the device is simply gone from Spotify until someone
     * presses Stop and Start. This is that, done for them.
     */
    private fun checkAlive() {
        NATIVE.execute {
            if (Librespot.nativeIsRunning()) return@execute
            main.post {
                if (destroyed || state != State.RUNNING) return@post
                Log.w(TAG, "the native worker died; restarting it")
                main.removeCallbacks(watchdog)
                launch()
            }
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (destroyed) return
            checkAlive()
            main.postDelayed(this, WATCHDOG_MS)
        }
    }

    /** Whether music was playing at the last look, and since when it has not been. */
    private var wasPlaying = false
    private var quietSince = 0L

    /**
     * Puts the picture over everything when a song starts, and takes it away after a
     * while without music.
     *
     * Only on the start: someone who closed it with music on did so on purpose, and it
     * coming straight back would be a fight with the remote.
     */
    private val watchPlayback = object : Runnable {
        override fun run() {
            if (destroyed) return
            val playing = Librespot.nativeNowPlaying().orEmpty().endsWith("\n1")
            val now = System.currentTimeMillis()
            if (playing && !wasPlaying) Overlay.show(this@BridgeService)
            if (playing) {
                quietSince = 0
            } else if (quietSince == 0L) {
                quietSince = now
            } else if (Overlay.isShown && now - quietSince > OVERLAY_QUIET_MS) {
                Overlay.hide(this@BridgeService)
            }
            wasPlaying = playing
            main.postDelayed(this, 2000)
        }
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacks(watchdog)
        main.removeCallbacks(watchPlayback)
        Overlay.hide(this)
        // Queued behind any start in flight, so a start that finishes after this cannot
        // leave a worker running with no service to stop it.
        NATIVE.execute { Librespot.nativeStop() }
        publish(State.STOPPED)
        releaseLocks()
        super.onDestroy()
    }

    private fun acquireLocks() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:cpu").apply {
            setReferenceCounted(false)
            acquire()
        }

        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // Stops Wi-Fi power saving from adding latency, and on some devices from dropping
        // the connection outright once the screen is off.
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$TAG:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }

        // The important one. Android filters out multicast packets unless this is held,
        // so without it mDNS discovery returns nothing and the caster reports that no
        // device by that name exists -- with no error anywhere to explain why.
        multicastLock = wifi.createMulticastLock("$TAG:mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        // Each release is guarded on its own: a lock that was never taken, or that the
        // system already dropped, must not stop the others from being let go.
        runCatching { if (multicastLock?.isHeld == true) multicastLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        multicastLock = null
        wifiLock = null
        wakeLock = null
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "CrossfadeBridge"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1

        private const val WATCHDOG_MS = 15_000L

        /**
         * Paused this long, the picture gives the screen back. Long enough to answer the
         * door without losing it; short enough not to sit on a screen nobody is using.
         */
        private const val OVERLAY_QUIET_MS = 5 * 60_000L

        /**
         * One thread for every native start and stop, shared by all instances: a Stop
         * then Start creates a new service while the old one is still winding down,
         * and the two must reach the native side in the order they were pressed.
         */
        private val NATIVE = Executors.newSingleThreadExecutor()
        private val main = Handler(Looper.getMainLooper())

        /** What the bridge is doing, so the screen can say so without binding to it. */
        @Volatile
        var state = State.STOPPED
            private set

        val isRunning get() = state == State.RUNNING

        /** Called on the main thread whenever [state] changes. */
        var onStateChanged: (() -> Unit)? = null

        private fun publish(new: State) {
            state = new
            main.post { onStateChanged?.invoke() }
        }

        const val DEVICE_NAME = "Crossfade Bridge"
        const val BIND = "0.0.0.0:8321"
        const val CROSSFADE_SECS = 8
    }
}
