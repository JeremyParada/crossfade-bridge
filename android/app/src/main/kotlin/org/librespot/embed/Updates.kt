package org.librespot.embed

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks GitHub whether a newer release has been published.
 *
 * Read-only and unauthenticated: releases of a public repository need no token, and
 * anonymous calls are allowed sixty an hour per address, which is far more than a
 * television opening this screen will ever use.
 */
object Updates {

    private const val TAG = "CrossfadeBridge"

    /** Where releases are published. One line to change if the project moves. */
    const val REPO = "JeremyParada/crossfade-bridge"

    /** The version of the running APK, from the manifest. */
    fun installed(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Throwable) {
        Log.e(TAG, "no se pudo leer la versión instalada", e)
        "?"
    }

    /**
     * Calls [onResult] on a background thread with the newest published tag, or null if
     * it matches what is installed, or the network or GitHub said nothing useful.
     *
     * Deliberately reports "different from what is installed" rather than "newer":
     * comparing versions properly means agreeing on a scheme, and getting that subtly
     * wrong either nags forever or stays quiet when it matters. A mismatch is the honest
     * signal, and a downgrade is not a case this ever hits in practice.
     */
    fun check(context: Context, onResult: (String?) -> Unit) {
        val current = installed(context)
        Thread {
            val latest = latestTag()
            onResult(latest?.takeIf { normalise(it) != normalise(current) })
        }.start()
    }

    private fun latestTag(): String? = try {
        val connection = (URL("https://api.github.com/repos/$REPO/releases/latest")
            .openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        // One field out of a large document: a full parser would be more code than the
        // whole check. A tag cannot contain a quote, so this cannot run past its value.
        Regex(""""tag_name"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
    } catch (e: Throwable) {
        // No releases yet answers 404, which lands here. Silence is the right outcome:
        // nobody wants an error on screen because the author has not tagged anything.
        Log.i(TAG, "no se pudo consultar la última versión: $e")
        null
    }

    /** `v1.2.0` and `1.2.0` are the same release wearing different hats. */
    private fun normalise(version: String) = version.trim().removePrefix("v")
}
