package tv.cleanhome.launcher

import android.service.dreams.DreamService
import android.widget.TextView
import tv.cleanhome.launcher.photos.PhotoRepository
import tv.cleanhome.launcher.photos.SlideshowController
import tv.cleanhome.launcher.ui.ClockFormat
import tv.cleanhome.launcher.ui.ClockTicker
import kotlin.random.Random

/**
 * Screensaver "Clean Home Photos": photo slideshow with a Ken Burns zoom and a small clock.
 * Without photos: black background, and the clock moves to a new position every minute.
 */
class PhotoDreamService : DreamService() {
    private var slideshow: SlideshowController? = null
    private var clockTicker: ClockTicker? = null
    private var clockView: TextView? = null
    private var photosAvailable = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        setContentView(R.layout.dream_photos)

        clockView = findViewById(R.id.dream_clock)
        clockTicker = ClockTicker(this) {
            clockView?.text = ClockFormat.forContext(this).time()
            positionClock()
        }
        slideshow = SlideshowController(
            firstView = findViewById(R.id.dream_photo_a),
            secondView = findViewById(R.id.dream_photo_b),
            photos = PhotoRepository(),
            intervalMs = INTERVAL_MS,
            fadeMs = FADE_MS,
            kenBurns = true,
        ) { available ->
            photosAvailable = available
            positionClock()
        }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        clockTicker?.start()
        slideshow?.start()
    }

    override fun onDreamingStopped() {
        slideshow?.stop()
        clockTicker?.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        slideshow?.stop()
        slideshow?.releaseImages()
        slideshow = null
        clockTicker?.stop()
        clockTicker = null
        clockView = null
        super.onDetachedFromWindow()
    }

    /** With photos: fixed at the bottom right; without photos: random position (burn-in protection). */
    private fun positionClock() {
        val clock = clockView ?: return
        if (photosAvailable) {
            clock.translationX = 0f
            clock.translationY = 0f
            return
        }
        val parent = clock.parent as? android.view.View ?: return
        if (parent.width == 0 || clock.width == 0) {
            clock.post { positionClock() }
            return
        }
        val margin = resources.getDimensionPixelSize(R.dimen.dream_clock_margin)
        val freeX = (parent.width - clock.width - 2 * margin).coerceAtLeast(0)
        val freeY = (parent.height - clock.height - 2 * margin).coerceAtLeast(0)
        // The clock is anchored bottom right; a negative translation moves it left/up.
        clock.translationX = -Random.nextInt(freeX + 1).toFloat()
        clock.translationY = -Random.nextInt(freeY + 1).toFloat()
    }

    private companion object {
        const val INTERVAL_MS = 12_000L
        const val FADE_MS = 1_500L
    }
}
