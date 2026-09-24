package org.librespot.embed

/**
 * The Rust side, loaded into this process.
 *
 * The names below must match the exported symbols in liblibrespot_embed.so exactly; a
 * mismatch is not a compile error, it is an UnsatisfiedLinkError on the device.
 */
object Librespot {
    init {
        System.loadLibrary("librespot_embed")
        // Before anything else: the native side builds its user agent from this, and an
        // absent or wrong API level is only rejected later, by login5, as BAD_REQUEST.
        nativeSetOsVersion(android.os.Build.VERSION.SDK_INT.toString())
    }

    private external fun nativeSetOsVersion(apiLevel: String)

    /** Returns false if it could not start, including when nobody has signed in yet. */
    external fun nativeStart(
        name: String,
        bind: String,
        castTo: String?,
        crossfadeSecs: Int,
        crossfadeAlbums: Boolean,
        cacheDir: String,
    ): Boolean

    external fun nativeStop()

    /** False once the worker has died on its own, which it does without telling anyone. */
    external fun nativeIsRunning(): Boolean

    /**
     * Seven lines -- title, artists, album, cover URL, duration ms, position ms, playing
     * (1/0) -- or empty when nothing is loaded. Nullable for the same reason as
     * [nativeAuthBegin].
     */
    external fun nativeNowPlaying(): String?

    /** A QR code for [text] as rows of 0 and 1, or empty. */
    external fun nativeQr(text: String): String?

    /**
     * Cast devices and groups on the network, one per line. Blocks: never on the UI
     * thread. Null for the same reason as [nativeAuthBegin].
     */
    external fun nativeDiscover(): String?

    /**
     * Returns "CODE|URL" to display, or "" if sign-in could not even be started.
     *
     * Nullable because it can be: building a string is the last thing the native side
     * does, and if that fails JNI hands back a null reference, not an empty one.
     */
    external fun nativeAuthBegin(cacheDir: String): String?

    /** 0 idle, 1 waiting for the user, 2 done, 3 failed. */
    external fun nativeAuthStatus(): Int

    const val AUTH_PENDING = 1
    const val AUTH_DONE = 2
    const val AUTH_FAILED = 3
}
