package org.librespot.embed

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

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
    private var running = false
        set(value) {
            field = value
            isRunning = value
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY

        startForeground(NOTIFICATION_ID, buildNotification())
        acquireLocks()

        // Read from the settings rather than the Intent: START_STICKY redelivers this
        // service with a null Intent after the system kills the app, and defaults picked
        // there would quietly cast nowhere and crossfade by a number nobody chose.
        val settings = Settings(this)
        val castTo = settings.group

        if (castTo == null) {
            // The only audio backend is the HTTP one, so with nowhere to cast this would
            // serve a stream to nobody while claiming to play.
            Log.e(TAG, "no cast target chosen; stopping the service")
            stopSelf()
            return START_NOT_STICKY
        }

        val started = Librespot.nativeStart(
            DEVICE_NAME,
            BIND,
            castTo,
            settings.crossfadeSecs,
            settings.crossfadeAlbums,
            filesDir.absolutePath,
        )

        if (!started) {
            // Almost always "no credentials yet". Staying up would just hold the locks
            // for nothing and look like it is working.
            Log.e(TAG, "librespot did not start; stopping the service")
            Debug.report(this, "librespot no arrancó")
            stopSelf()
            return START_NOT_STICKY
        }

        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        if (running) {
            Librespot.nativeStop()
            running = false
        }
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

        /** Whether the bridge is up, so the screen can say so without binding to it. */
        @Volatile
        var isRunning = false
            private set

        const val DEVICE_NAME = "Crossfade Bridge"
        const val BIND = "0.0.0.0:8321"
        const val CROSSFADE_SECS = 8
    }
}
