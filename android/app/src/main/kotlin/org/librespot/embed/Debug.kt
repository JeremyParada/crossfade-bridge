package org.librespot.embed

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Failure reports, for when something goes wrong on a television nobody can attach a
 * debugger to.
 *
 * Off unless the person turns "Informes de fallo" on: nothing is written and nothing
 * leaves the device otherwise. When it is on, a failure is written to disk and sent the
 * next time the app opens -- see [flush] for why not straight away -- through a relay
 * that files it as a GitHub issue. See [RELAY_URL] for why the token lives there.
 */
object Debug {

    private const val TAG = "CrossfadeBridge"

    /**
     * Where reports are sent: a relay that holds the GitHub token on its side.
     *
     * The token is never here. An APK is a zip, and a token inside one can be read out
     * of it in a minute by anyone who has the file; whoever does can spam, edit and
     * close issues as its owner. The relay keeps the secret server-side, can drop abuse
     * before it reaches GitHub, and can be re-keyed without publishing a new version.
     *
     * See relay/worker.js. Until it is deployed, reports simply stay on the device.
     */
    private const val RELAY_URL = "https://crossfade-bridge-relay.jeremy-parada.workers.dev"

    /** Optional override of [RELAY_URL], placed by hand. See [relay]. */
    private const val CONFIG_FILE = "debug.conf"
    private const val REPORTS_DIR = "reports"

    /** Log lines to include. Enough for a stack trace and what led to it. */
    private const val LOG_LINES = 400

    /** How many reports to keep when the relay is not taking them. */
    private const val MAX_REPORTS = 20

    /**
     * The relay refuses anything past 64 KiB, and 400 log lines land close enough to
     * that to cross it on a chatty run -- after which that report is refused forever.
     * Cut here instead, keeping the end of the log, which is where the failure is.
     */
    private const val MAX_BODY_BYTES = 60 * 1024

    /**
     * Routes uncaught crashes through [report] before Android tears the process down.
     *
     * Chains to the previous handler rather than replacing it, so the app still dies and
     * the system still records the crash: swallowing it would turn a crash into a freeze.
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { report(appContext, "Crash en ${thread.name}", error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Writes a report, if the person asked for that.
     *
     * Returns where it was written, or null when reporting is off or something went
     * wrong. Never throws: a failure to report a failure must not become the failure.
     */
    fun report(context: Context, title: String, error: Throwable? = null): File? {
        // Checked here rather than at every call site, so no future caller can send a
        // report from a television whose owner said no.
        if (!Settings(context).debug) return null
        return write(context, title, error)
    }

    private fun write(context: Context, title: String, error: Throwable?): File? = try {
        val body = buildString {
            append("- Aparato: ").append(Build.MODEL).append(" (").append(Build.DEVICE).append(")\n")
            append("- Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("- ABI: ").append(Build.SUPPORTED_ABIS.firstOrNull()).append("\n\n")
            // Redacted like the log below: an exception message can carry a URL with
            // a token in its query, and this goes to the same public issue.
            error?.let {
                append("```\n").append(Redact.scrub(Log.getStackTraceString(it)))
                    .append("\n```\n\n")
            }
            append("Log:\n\n```\n").append(Redact.scrub(recentLog())).append("\n```\n")
        }

        val trimmed = if (body.toByteArray().size > MAX_BODY_BYTES) {
            "[recortado]\n\n" + body.takeLast(MAX_BODY_BYTES / 2)
        } else {
            body
        }

        val dir = File(context.filesDir, REPORTS_DIR).apply { mkdirs() }
        val file = File(dir, "report-${System.currentTimeMillis()}.md")
        // Title first, body after: one file, and no format to parse.
        file.writeText(title + "\n" + trimmed)

        // Written, not sent. A report is nearly always made by a process on its way out,
        // and a network call started there dies with it -- which is exactly how the
        // crashes this exists for went unreported. [flush] takes them next time.
        trim(dir)
        file
    } catch (e: Throwable) {
        Log.e(TAG, "could not write a report", e)
        null
    }

    /** How many reports are waiting on the device. */
    fun pending(context: Context): Int = reports(context).size

    /**
     * Sends what is waiting, oldest first, deleting whatever the relay accepts.
     *
     * Called when the app opens, which is the only moment a report from a crashed run
     * has a process alive long enough to leave the device.
     */
    fun flush(context: Context) {
        val appContext = context.applicationContext
        if (!Settings(appContext).debug) return

        Thread {
            val relay = relay(appContext)
            for (file in reports(appContext).sortedBy { it.name }) {
                val text = runCatching { file.readText() }.getOrNull() ?: continue
                when (send(relay, text.substringBefore('\n'), text.substringAfter('\n'))) {
                    Sent.Accepted -> file.delete()
                    // The relay will never take this one: empty, malformed, too large.
                    // Keeping it would block every report queued behind it, for good.
                    Sent.Refused -> {
                        Log.i(TAG, "informe rechazado de forma permanente: " + file.name)
                        file.delete()
                    }
                    // No relay, no network, or asked to slow down. Leave the rest for
                    // next time instead of hammering it report by report.
                    Sent.Later -> break
                }
            }
        }.start()
    }

    /** What the relay did with a report, which decides whether the file survives. */
    private enum class Sent { Accepted, Refused, Later }

    private fun reports(context: Context): List<File> =
        File(context.filesDir, REPORTS_DIR)
            .listFiles { file -> file.name.startsWith("report-") }
            ?.toList()
            .orEmpty()

    /** Keeps the newest [MAX_REPORTS]. Nothing else ever deletes them. */
    private fun trim(dir: File) {
        dir.listFiles()?.sortedBy { it.name }?.dropLast(MAX_REPORTS)?.forEach { it.delete() }
    }

    /**
     * The relay to post to: [RELAY_URL], unless `relay=https://...` sits in a
     * `debug.conf` in the app's own directory.
     *
     * Placing that file needs a debuggable build, which a released APK is not, so this
     * is a switch for whoever builds the app rather than for whoever installs it. It is
     * a URL, never a credential.
     */
    private fun relay(context: Context): String {
        val file = File(context.filesDir, CONFIG_FILE)
        if (!file.isFile) return RELAY_URL
        // Guarded like everything else here: this runs first on the flush thread, and an
        // unreadable file would take the process down over a config nobody set.
        return runCatching { file.readLines() }.getOrDefault(emptyList())
            .firstOrNull { it.substringBefore('=', "").trim() == "relay" }
            ?.substringAfter('=', "")
            ?.trim()
            ?.takeIf { it.startsWith("https://") }
            ?: RELAY_URL
    }

    /** This process's own log. Android only ever hands an app its own lines. */
    private fun recentLog(): String = try {
        val process = Runtime.getRuntime()
            .exec(arrayOf("logcat", "-d", "-v", "time", "-t", LOG_LINES.toString()))
        process.inputStream.bufferedReader().use { it.readText() }
    } catch (e: Throwable) {
        "no se pudo leer el log: $e"
    }

    private fun send(relay: String, title: String, body: String): Sent = try {
        val connection = (URL(relay).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
        }
        val payload = """{"title":${quote(title)},"body":${quote(body)}}"""
        connection.outputStream.use { it.write(payload.toByteArray()) }
        val code = connection.responseCode
        connection.disconnect()
        Log.i(TAG, "informe enviado: HTTP $code")
        when {
            code in 200..299 -> Sent.Accepted
            // 429 means later. The other 4xx are about this report and will not change
            // however many times it is sent again.
            code == 429 -> Sent.Later
            code in 400..499 -> Sent.Refused
            else -> Sent.Later
        }
    } catch (e: Throwable) {
        // The report is already on disk, so a failure here loses nothing: an undeployed
        // relay must not turn into a second problem on screen.
        Log.i(TAG, "no se pudo enviar el informe, queda en el aparato: $e")
        Sent.Later
    }

    /** JSON string literal. Hand-rolled because the platform's JSON is not worth pulling in. */
    private fun quote(s: String): String {
        val out = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        return out.append('"').toString()
    }
}
