package org.librespot.embed

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL

/**
 * What is playing, filling the television: the cover, a blurred wash of it behind, the
 * title and where the track is.
 *
 * A view rather than a screen so it can live in two places: the app's own page, and a
 * window over everything else for when the television is a member of the group and
 * Google's receiver takes the screen as soon as the cast starts.
 *
 * It asks the native side twice a second while attached rather than being pushed to: a
 * snapshot is a lock and a string, and polling survives the bridge restarting
 * underneath without any wiring.
 */
class NowPlayingView(context: Context) : FrameLayout(context) {

    private val ui = Handler(Looper.getMainLooper())

    private val backdrop: ImageView
    private val cover: ImageView
    private val title: TextView
    private val artists: TextView
    private val album: TextView
    private val progress: ProgressBar
    private val elapsed: TextView
    private val total: TextView
    private val details: View
    private val idle: TextView

    /** The cover on screen or on its way, so each one is fetched once. */
    private var coverUrl: String? = null

    init {
        setBackgroundColor(BACKGROUND)

        backdrop = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.35f
            // The cover itself, blurred into a wash of its colours. RenderEffect is API
            // 31; older televisions get the same picture, dimmed but sharp.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRenderEffect(RenderEffect.createBlurEffect(80f, 80f, Shader.TileMode.CLAMP))
            }
        }
        // A slow drift, so a picture left on for an hour is not a still frame.
        listOf("scaleX", "scaleY").forEach { axis ->
            ObjectAnimator.ofFloat(backdrop, axis, 1.1f, 1.3f).apply {
                duration = 40_000
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }

        cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GradientDrawable().apply {
                setColor(CARD)
                cornerRadius = dp(12).toFloat()
            }
            elevation = dp(16).toFloat()
            layoutParams = LinearLayout.LayoutParams(dp(360), dp(360)).apply {
                rightMargin = dp(56)
            }
        }

        title = text(40f, TEXT, bold = true).apply { maxLines = 2 }
        artists = text(24f, TEXT).apply { setPadding(0, dp(12), 0, 0) }
        album = text(18f, MUTED).apply { setPadding(0, dp(6), 0, dp(32)) }
        progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressTintList = ColorStateList.valueOf(TEXT)
            progressBackgroundTintList = ColorStateList.valueOf(MUTED)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(6))
        }
        elapsed = text(14f, MUTED)
        total = text(14f, MUTED).apply {
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        val times = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
            addView(elapsed)
            addView(total)
        }
        val words = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            addView(title)
            addView(artists)
            addView(album)
            addView(progress)
            addView(times)
        }
        details = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(80), dp(48), dp(80), dp(48))
            addView(cover)
            addView(words)
        }
        idle = text(22f, MUTED).apply {
            text = context.getString(R.string.nothing_playing, BridgeService.DEVICE_NAME)
            gravity = Gravity.CENTER
        }

        addView(backdrop, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(details, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(idle, LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private val tick = object : Runnable {
        override fun run() {
            show(Librespot.nativeNowPlaying().orEmpty())
            ui.postDelayed(this, 500)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        tick.run()
    }

    override fun onDetachedFromWindow() {
        ui.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private fun show(snapshot: String) {
        val f = snapshot.split('\n')
        if (f.size < 7) {
            details.visibility = View.GONE
            idle.visibility = View.VISIBLE
            keepScreenOn = false
            return
        }
        details.visibility = View.VISIBLE
        idle.visibility = View.GONE

        title.text = f[0]
        artists.text = f[1]
        album.text = f[2]
        val duration = f[4].toIntOrNull() ?: 0
        val position = f[5].toIntOrNull() ?: 0
        progress.max = duration.coerceAtLeast(1)
        progress.progress = position
        elapsed.text = clock(position)
        total.text = clock(duration)
        // Paused, the television may dim and fall to its own screensaver like with any
        // other app; playing, it stays on the picture.
        keepScreenOn = f[6] == "1"

        if (f[3] != coverUrl) {
            coverUrl = f[3]
            fetch(f[3])
        }
    }

    /** Off the UI thread: the image host can take a second, and a frozen TV looks broken. */
    private fun fetch(url: String) {
        if (url.isBlank()) {
            cover.setImageDrawable(null)
            backdrop.setImageDrawable(null)
            return
        }
        Thread {
            val bitmap: Bitmap? = runCatching {
                (URL(url).openConnection() as HttpURLConnection).run {
                    connectTimeout = 5000
                    readTimeout = 5000
                    try {
                        inputStream.use { BitmapFactory.decodeStream(it) }
                    } finally {
                        disconnect()
                    }
                }
            }.getOrNull()
            ui.post {
                // A newer track may have started while this one downloaded.
                if (!isAttachedToWindow || url != coverUrl) return@post
                cover.setImageBitmap(bitmap)
                backdrop.setImageBitmap(bitmap)
            }
        }.start()
    }

    private fun text(size: Float, colour: Int, bold: Boolean = false) = TextView(context).apply {
        setTextColor(colour)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        ellipsize = TextUtils.TruncateAt.END
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun clock(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val BACKGROUND = Color.parseColor("#0B0E13")
        private val CARD = Color.parseColor("#151A22")
        private val TEXT = Color.parseColor("#E8EAED")
        private val MUTED = Color.parseColor("#9AA0A6")
    }
}
