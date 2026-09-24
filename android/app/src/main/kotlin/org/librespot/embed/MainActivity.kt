package org.librespot.embed

import android.Manifest.permission.POST_NOTIFICATIONS
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.File

/**
 * The launcher entry: sign in once, choose where to play, then start the bridge.
 *
 * Signing in has to happen here rather than by copying credentials from a computer.
 * Stored credentials are tied to the client id that obtained them, and that id is chosen
 * per operating system, so a desktop copy authenticates the session and is then rejected
 * by Spirc -- an error that looks like a wrong password and is not one.
 *
 * Everything is built in code on purpose: the APK is assembled without Gradle (see
 * android/build-apk.sh), so there is no dependency resolution and no library widgets --
 * only what android.jar itself provides. Wording, though, lives in res/values*, which is
 * what makes the screen follow the television's own language.
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: Settings

    private lateinit var status: TextView
    private lateinit var groupRow: Row
    private lateinit var crossfadeRow: Row
    private lateinit var albumRow: Row
    private lateinit var debugRow: Row
    private lateinit var versionRow: Row
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var signInButton: Button

    /** Set when an option changed while the bridge was running, which Start applies. */
    private var settingsAreStale = false

    /** The tag GitHub last reported, when it differs from what is installed. */
    private var newerRelease: String? = null

    // Read once and kept, rather than hitting the disk on every redraw: `refresh` runs
    // on every setting change, every dialog and every update check.
    private var signedIn = false
    private var pendingReports = 0
    private var version = "?"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The page carries its own heading; the system title bar would only repeat it
        // and steal a band of a screen that is already being watched from the sofa.
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        prefs = Settings(this)
        setContentView(buildLayout())
        askAboutNotifications()
        reread()
        refresh()
        checkForUpdate()
        // Whoever opens this is nearly always here to press it.
        startButton.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        // The service starts on its own thread and can restart itself, so the screen
        // follows it rather than guessing right after a button is pressed.
        BridgeService.onStateChanged = { if (!isFinishing && !isDestroyed) refresh() }
        refresh()
    }

    override fun onPause() {
        BridgeService.onStateChanged = null
        super.onPause()
    }

    /**
     * Asks for the notification permission, which API 33 made a runtime one.
     *
     * The manifest declares it and nothing ever requested it, so on a fresh install the
     * foreground service runs while its notification is silently dropped -- the one
     * visible sign that the bridge is up. Asked once; Android remembers a refusal.
     */
    private fun askAboutNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(POST_NOTIFICATIONS), 1)
    }

    /** Re-reads what lives on disk. Cheap, but not for the UI thread to repeat. */
    private fun reread() {
        Thread {
            val signed = File(filesDir, "credentials.json").length() > 0
            val waiting = Debug.pending(this)
            val installed = Updates.installed(this)
            ui.post {
                if (isFinishing || isDestroyed) return@post
                signedIn = signed
                pendingReports = waiting
                version = installed
                refresh()
            }
        }.start()
    }

    // --- layout -------------------------------------------------------------------

    private fun buildLayout(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
            setPadding(dp(48), dp(36), dp(48), dp(40))
        }

        page.addView(header())
        page.addView(settingsCard())
        page.addView(supportCard())

        // A television can be overscanned and the rows grow with their subtitles, so the
        // whole page scrolls rather than pushing anything off the screen.
        return ScrollView(this).apply {
            setBackgroundColor(BACKGROUND)
            addView(page, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    /**
     * Title on the left, the buttons that matter on the right.
     *
     * Start and Stop are what someone touches daily and everything below is set once, so
     * they sit at the top where the remote already is, rather than a page away.
     */
    private fun header(): View {
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            addView(text(getString(R.string.app_name), 34f, TEXT, bold = true))
            status = text("", 16f, MUTED)
            addView(status)
        }

        startButton = button(getString(R.string.start), primary = true) { primaryAction() }
        stopButton = button(getString(R.string.stop)) { stop() }
        signInButton = button(getString(R.string.sign_in)) { beginAuth() }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(28))
            addView(titles)
            addView(startButton)
            addView(stopButton)
            addView(signInButton)
        }
    }

    private fun settingsCard(): View {
        groupRow = Row(R.string.output_title, R.string.output_subtitle) { chooseGroup() }
        crossfadeRow = Row(R.string.crossfade_title, R.string.crossfade_subtitle) {
            chooseCrossfade()
        }
        albumRow = Row(R.string.albums_title, R.string.albums_subtitle) {
            prefs.crossfadeAlbums = !prefs.crossfadeAlbums
            onSettingChanged()
        }
        debugRow = Row(R.string.reports_title, R.string.reports_subtitle) {
            prefs.debug = !prefs.debug
            // Installed for this run too: BridgeApp only sees the setting at startup,
            // and someone who just turned it on should not have to restart the app to
            // have the next failure recorded.
            if (prefs.debug) Debug.install(this)
            onSettingChanged()
        }
        versionRow = Row(R.string.version_title, R.string.version_subtitle) { checkForUpdate() }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(groupRow.view)
            addView(crossfadeRow.view)
            addView(albumRow.view)
            addView(debugRow.view)
            addView(versionRow.view)
        }
    }

    /**
     * Who made this, and one square anyone can point a phone at.
     *
     * A television has no browser worth opening and no keyboard, so a link on screen is
     * something you retype by hand. A QR turns it into a gesture people already know.
     * It points at the project page rather than at three separate places, so the links
     * behind it can change without shipping a new APK -- and the encoder ran at build
     * time, because this build resolves no dependencies.
     */
    private fun supportCard(): View {
        val qr = ImageView(this).apply {
            setImageResource(R.drawable.qr_contact)
            contentDescription = getString(R.string.support_qr_alt)
            layoutParams = LinearLayout.LayoutParams(dp(168), dp(168)).apply {
                rightMargin = dp(24)
            }
        }

        val words = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            addView(text(getString(R.string.support_title), 20f, TEXT, bold = true))
            addView(text(getString(R.string.support_subtitle), 14f, MUTED).apply {
                setPadding(0, dp(4), 0, dp(12))
            })
        }
        LINKS.forEach { (label, value) ->
            words.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
                addView(text(label, 14f, MUTED).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(96), WRAP_CONTENT)
                })
                addView(text(value, 14f, ACCENT))
            })
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(CARD)
            // Focusable so a remote can reach it: a ScrollView on a television scrolls by
            // following focus, and on a 720p screen this card sits below the fold. With
            // nothing here to focus, the QR would exist and be unreachable.
            isFocusable = true
            setOnFocusChangeListener { v, focused ->
                v.background = rounded(if (focused) FOCUS else CARD)
            }
            setPadding(dp(28), dp(24), dp(28), dp(24))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(20)
            }
            addView(qr)
            addView(words)
        }
    }

    /** One focusable setting: a title, an explanation, and the value it currently has. */
    private inner class Row(title: Int, subtitle: Int, private val onClick: () -> Unit) {
        private val value = text("", 18f, ACCENT, bold = true)
        val view: LinearLayout

        init {
            val labels = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                addView(text(getString(title), 20f, TEXT))
                addView(text(getString(subtitle), 14f, MUTED))
            }
            view = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(18), dp(20), dp(18))
                isFocusable = true
                background = rounded(CARD)
                addView(labels)
                addView(value)
                setOnClickListener { onClick() }
                // The only cue a remote gives is the focus ring, so it has to be obvious.
                setOnFocusChangeListener { v, focused ->
                    v.background = rounded(if (focused) FOCUS else CARD)
                }
            }
        }

        fun show(text: String) {
            value.text = text
        }
    }

    // --- state --------------------------------------------------------------------

    private fun refresh() {
        groupRow.show(prefs.group ?: getString(R.string.none))
        crossfadeRow.show(
            if (prefs.crossfadeSecs > 0) getString(R.string.seconds, prefs.crossfadeSecs)
            else getString(R.string.off)
        )
        albumRow.show(getString(if (prefs.crossfadeAlbums) R.string.yes else R.string.no))
        debugRow.show(
            when {
                !prefs.debug -> getString(R.string.no)
                pendingReports > 0 -> getString(R.string.yes_with_reports, pendingReports)
                else -> getString(R.string.yes)
            }
        )
        versionRow.show(newerRelease ?: version)

        signInButton.visibility = if (signedIn) View.GONE else View.VISIBLE

        // One button that says what pressing it will do, coloured by what the bridge is
        // doing now: a status line alone was too easy to miss from the sofa.
        val state = BridgeService.state
        if (state != BridgeService.State.RUNNING) settingsAreStale = false
        when {
            settingsAreStale ->
                paint(startButton, R.string.apply_and_restart, ACCENT, ACCENT_BRIGHT, BACKGROUND)
            state == BridgeService.State.STARTING ->
                paint(startButton, R.string.starting, CARD, FOCUS, MUTED)
            state == BridgeService.State.RUNNING ->
                paint(startButton, R.string.stop, DANGER, DANGER_BRIGHT, TEXT)
            else -> paint(startButton, R.string.start, ACCENT, ACCENT_BRIGHT, BACKGROUND)
        }
        // A separate Stop is only needed while the main button means something else.
        stopButton.visibility = if (settingsAreStale) View.VISIBLE else View.GONE

        status.setTextColor(
            when {
                settingsAreStale || state == BridgeService.State.STARTING -> WARNING
                state == BridgeService.State.RUNNING -> ACCENT
                else -> MUTED
            }
        )
        val name = BridgeService.DEVICE_NAME
        status.text = when {
            !signedIn -> getString(R.string.status_not_signed_in)
            settingsAreStale -> getString(R.string.status_settings_changed)
            state == BridgeService.State.STARTING -> getString(R.string.status_starting)
            state == BridgeService.State.RUNNING -> prefs.group
                ?.let { getString(R.string.status_running_on, name, it) }
                ?: getString(R.string.status_running, name)
            prefs.group == null -> getString(R.string.status_pick_output)
            else -> getString(R.string.status_ready)
        }
    }

    private fun onSettingChanged() {
        // Options are read when the native side starts, so a change while it runs only
        // takes effect on the next start. Saying so beats pretending it applied.
        if (BridgeService.isRunning) settingsAreStale = true
        refresh()
    }

    // --- actions ------------------------------------------------------------------

    private fun primaryAction() {
        when {
            settingsAreStale -> start()
            BridgeService.state == BridgeService.State.STARTING -> Unit
            BridgeService.state == BridgeService.State.RUNNING -> stop()
            else -> start()
        }
    }

    private fun start() {
        if (!signedIn) {
            status.text = getString(R.string.status_sign_in_first)
            return
        }
        // The only audio backend is the HTTP one, so with nowhere to cast to the bridge
        // serves a stream nobody listens to. Rather than refuse and leave the person to
        // work out what is missing, ask for it: the list is the next thing they need
        // anyway, and picking from it carries on into starting.
        if (prefs.group == null) {
            chooseGroup(thenStart = true)
            return
        }
        val restart = settingsAreStale
        settingsAreStale = false
        if (restart) stopService(Intent(this, BridgeService::class.java))
        // No extras: the service reads the same settings this screen writes, so a
        // restart by the system starts with what the person actually chose. It reports
        // Starting and Running itself, through onStateChanged.
        startForegroundService(Intent(this, BridgeService::class.java))
    }

    private fun stop() {
        settingsAreStale = false
        stopService(Intent(this, BridgeService::class.java))
        // A service never started sends no state change, so redraw here as well.
        refresh()
        // Stop can disappear from under the remote; put the focus back somewhere useful.
        if (!startButton.hasFocus()) startButton.requestFocus()
    }

    /**
     * Offers every Cast device on the network, plus the option of not casting at all.
     *
     * The browse blocks for several seconds, so it runs off the UI thread; a television
     * that freezes for five seconds looks broken.
     */
    private fun chooseGroup(thenStart: Boolean = false) {
        status.text = getString(R.string.status_searching)
        Thread {
            val found = Librespot.nativeDiscover().orEmpty().split('\n').filter { it.isNotBlank() }
            ui.post {
                if (isFinishing || isDestroyed) return@post
                if (found.isEmpty()) {
                    status.text = getString(R.string.status_nothing_found)
                    return@post
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.output_title)
                    .setItems(found.toTypedArray()) { _, which ->
                        prefs.group = found[which]
                        onSettingChanged()
                        // Pressing Start is what got us here, so finish the job.
                        if (thenStart) start()
                    }
                    .show()
                refresh()
            }
        }.start()
    }

    /**
     * Picks the overlap in seconds, zero being no crossfade at all.
     *
     * A slider rather than a list because the useful difference between 6 and 8 seconds
     * is something you feel rather than pick from a menu, and left and right on a remote
     * are exactly that gesture.
     */
    private fun chooseCrossfade() {
        val label = text("", 22f, ACCENT, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(6))
        }

        fun describe(value: Int) =
            if (value > 0) getString(R.string.seconds, value) else getString(R.string.off)

        val slider = SeekBar(this).apply {
            max = MAX_CROSSFADE_SECS
            progress = prefs.crossfadeSecs
            setPadding(dp(32), dp(16), dp(32), dp(24))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    label.text = describe(value)
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        label.text = describe(slider.progress)

        AlertDialog.Builder(this)
            .setTitle(R.string.crossfade_dialog_title)
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(label)
                addView(slider)
            })
            .setPositiveButton(R.string.save) { _, _ ->
                prefs.crossfadeSecs = slider.progress
                onSettingChanged()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Looks for a newer release, quietly.
     *
     * A television is not the place to install anything -- the APK still has to be
     * sideloaded -- so this only ever says that one exists, and where to get it.
     */
    private fun checkForUpdate() {
        Updates.check(this) { tag ->
            ui.post {
                if (isFinishing || isDestroyed) return@post
                newerRelease = tag
                if (tag != null) {
                    status.text = getString(R.string.status_update, tag, Updates.REPO)
                }
                refresh()
            }
        }
    }

    /**
     * Starts the sign-in and shows the code.
     *
     * Off the UI thread: asking Spotify for a device code is a blocking HTTP request,
     * and because the socket belongs to the native side, StrictMode never complains --
     * the only symptom is a television frozen for several seconds.
     */
    private fun beginAuth() {
        status.text = getString(R.string.status_searching)
        Thread {
            val shown = Librespot.nativeAuthBegin(filesDir.absolutePath).orEmpty()
            ui.post {
                if (isFinishing || isDestroyed) return@post
                if (shown.isEmpty()) {
                    status.text = getString(R.string.status_auth_error)
                    return@post
                }
                status.text = getString(
                    R.string.status_auth_code,
                    shown.substringAfter('|'),
                    shown.substringBefore('|'),
                )
                pollAuth()
            }
        }.start()
    }

    /**
     * Device auth finishes on its own thread; this only reflects it on screen.
     *
     * Gives up when the screen is gone. Without that, the lambda holds a destroyed
     * Activity alive forever, writing into a TextView nobody can see, because a code
     * that is never entered leaves the native side pending indefinitely.
     */
    private fun pollAuth() {
        ui.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            when (Librespot.nativeAuthStatus()) {
                Librespot.AUTH_DONE -> {
                    status.text = getString(R.string.status_signed_in)
                    reread()
                }
                Librespot.AUTH_FAILED -> status.text = getString(R.string.status_auth_failed)
                else -> pollAuth()
            }
        }, 2000)
    }

    // --- small helpers ------------------------------------------------------------

    private fun text(s: String, size: Float, colour: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = s
            setTextColor(colour)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(28), dp(14), dp(28), dp(14))
            stateListAnimator = null
            if (primary) paint(this, null, ACCENT, ACCENT_BRIGHT, BACKGROUND)
            else paint(this, null, CARD, FOCUS, TEXT)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                leftMargin = dp(12)
            }
        }

    /**
     * Colours a button at rest and under focus.
     *
     * Focus brightens rather than recolours: the button has to stay recognisably what it
     * is while the remote sits on it, which is where the main one starts. Replacing the
     * background drops the platform's own focus indication, and a remote has nothing
     * else to show where it is.
     */
    private fun paint(b: Button, label: Int?, resting: Int, focused: Int, textColour: Int) {
        if (label != null) b.text = getString(label)
        b.setTextColor(textColour)
        b.background = rounded(if (b.hasFocus()) focused else resting)
        b.setOnFocusChangeListener { v, has -> v.background = rounded(if (has) focused else resting) }
    }

    private fun rounded(colour: Int) = GradientDrawable().apply {
        setColor(colour)
        cornerRadius = dp(10).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** Above this the overlap eats more of a track than it joins. */
        private const val MAX_CROSSFADE_SECS = 12

        private val LINKS = listOf(
            "PayPal" to "paypal.me/jereparada",
            "GitHub" to "github.com/JeremyParada",
            "LinkedIn" to "linkedin.com/in/jeremy-parada",
        )

        private val BACKGROUND = Color.parseColor("#0B0E13")
        private val CARD = Color.parseColor("#151A22")
        private val FOCUS = Color.parseColor("#26303D")
        private val TEXT = Color.parseColor("#E8EAED")
        private val MUTED = Color.parseColor("#9AA0A6")
        private val ACCENT = Color.parseColor("#35C46B")
        private val ACCENT_BRIGHT = Color.parseColor("#5BE093")
        private val DANGER = Color.parseColor("#B3382F")
        private val DANGER_BRIGHT = Color.parseColor("#E0544A")
        private val WARNING = Color.parseColor("#F2B84B")
    }
}

/** The chosen options, kept across restarts of both the app and the television. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    var group: String?
        get() = prefs.getString("group", null)
        set(value) = prefs.edit().putString("group", value).apply()

    /** Seconds of overlap; 0 is no crossfade. */
    var crossfadeSecs: Int
        get() = prefs.getInt("crossfade_secs", 8)
        set(value) = prefs.edit().putInt("crossfade_secs", value).apply()

    var crossfadeAlbums: Boolean
        get() = prefs.getBoolean("crossfade_albums", false)
        set(value) = prefs.edit().putBoolean("crossfade_albums", value).apply()

    var debug: Boolean
        get() = prefs.getBoolean("debug", false)
        set(value) = prefs.edit().putBoolean("debug", value).apply()
}
