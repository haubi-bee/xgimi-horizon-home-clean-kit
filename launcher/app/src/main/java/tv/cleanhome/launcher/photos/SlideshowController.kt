package tv.cleanhome.launcher.photos

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import coil.size.Scale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.cleanhome.launcher.CleanHomeApp.Companion.TAG
import java.io.File
import kotlin.random.Random

/**
 * Photo slideshow with two stacked ImageViews that share their own parent container. Used by the
 * home screen background and by the screensaver.
 *
 * Memory: photos are decoded at display size only, never kept in the memory cache, and the
 * outgoing image is released right after the crossfade.
 */
class SlideshowController(
    firstView: ImageView,
    secondView: ImageView,
    private val photos: PhotoRepository,
    private val intervalMs: Long,
    private val fadeMs: Long,
    private val kenBurns: Boolean,
    private val onAvailabilityChanged: (Boolean) -> Unit = {},
) {
    private var front: ImageView = firstView
    private var back: ImageView = secondView
    private var job: Job? = null
    private var fadingOut: ImageView? = null
    private var hidingAll = false
    private var kenBurnsAnimator: Animator? = null
    private var available: Boolean? = null

    init {
        firstView.alpha = 0f
        secondView.alpha = 0f
    }

    fun start() {
        if (job != null) return
        job = CoroutineScope(Dispatchers.Main.immediate).launch { run() }
    }

    /** Pauses the slideshow; the current image stays on screen. */
    fun stop() {
        job?.cancel()
        job = null
        kenBurnsAnimator?.cancel()
        kenBurnsAnimator = null
        settleAnimations()
    }

    /** Releases the bitmaps (for example in onTrimMemory). Only effective while the slideshow is paused. */
    fun releaseImages() {
        if (job != null) return
        clear(front)
        clear(back)
    }

    private suspend fun run() {
        if (front.drawable != null) delay(intervalMs)
        while (true) {
            val file = withContext(Dispatchers.IO) { photos.next() }
            if (file == null) {
                setAvailable(false)
                fadeOutAll()
                delay(EMPTY_RETRY_MS)
                continue
            }
            val drawable = decode(file)
            if (drawable == null) {
                delay(FAILED_RETRY_MS)
                continue
            }
            setAvailable(true)
            crossfadeTo(drawable)
            delay(intervalMs)
        }
    }

    private suspend fun decode(file: File): Drawable? {
        val metrics = front.resources.displayMetrics
        val request = ImageRequest.Builder(front.context)
            .data(file)
            .size(metrics.widthPixels, metrics.heightPixels)
            // FIT: the whole photo is needed, PhotoFrameComposer adds the framing.
            .scale(Scale.FIT)
            .precision(Precision.INEXACT)
            // Software bitmaps: safe for alpha/scale animations on every render path.
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()
        return when (val result = front.context.imageLoader.execute(request)) {
            is SuccessResult -> {
                val photo = (result.drawable as? BitmapDrawable)?.bitmap ?: return result.drawable
                // Show the whole photo; mismatching aspect ratios get a blurred border.
                val framed = withContext(Dispatchers.Default) {
                    PhotoFrameComposer.compose(photo, metrics.widthPixels, metrics.heightPixels)
                }
                // Without the memory cache, the decoded photo belongs to this call only.
                photo.recycle()
                BitmapDrawable(front.resources, framed)
            }
            is ErrorResult -> {
                Log.w(TAG, "Photo not readable: ${file.path}", result.throwable)
                null
            }
        }
    }

    private fun crossfadeTo(drawable: Drawable) {
        settleAnimations()
        kenBurnsAnimator?.cancel()
        kenBurnsAnimator = null

        val incoming = back
        val outgoing = front
        incoming.animate().cancel()
        incoming.scaleX = 1f
        incoming.scaleY = 1f
        incoming.alpha = 0f
        incoming.setImageDrawable(drawable)
        incoming.bringToFront()

        front = incoming
        back = outgoing
        fadingOut = if (outgoing.drawable != null) outgoing else null

        incoming.animate()
            .alpha(1f)
            .setDuration(fadeMs)
            .withEndAction {
                if (fadingOut === outgoing) {
                    fadingOut = null
                    clear(outgoing)
                }
            }
            .start()
        if (kenBurns) startKenBurns(incoming)
    }

    /** Moves running crossfades to their end state immediately. */
    private fun settleAnimations() {
        front.animate().cancel()
        if (hidingAll) {
            hidingAll = false
            clear(front)
        } else if (front.drawable != null) {
            front.alpha = 1f
        }
        fadingOut?.let(::clear)
        fadingOut = null
    }

    private fun fadeOutAll() {
        settleAnimations()
        kenBurnsAnimator?.cancel()
        kenBurnsAnimator = null
        val view = front
        if (view.drawable == null) return
        hidingAll = true
        view.animate()
            .alpha(0f)
            .setDuration(fadeMs)
            .withEndAction {
                if (hidingAll && front === view) {
                    hidingAll = false
                    clear(view)
                }
            }
            .start()
    }

    private fun startKenBurns(view: ImageView) {
        val metrics = view.resources.displayMetrics
        val width = view.width.takeIf { it > 0 } ?: metrics.widthPixels
        val height = view.height.takeIf { it > 0 } ?: metrics.heightPixels
        view.pivotX = width * (0.25f + 0.5f * Random.nextFloat())
        view.pivotY = height * (0.25f + 0.5f * Random.nextFloat())
        kenBurnsAnimator = ObjectAnimator.ofPropertyValuesHolder(
            view,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, KEN_BURNS_SCALE),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, KEN_BURNS_SCALE),
        ).apply {
            duration = intervalMs + fadeMs
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun clear(view: ImageView) {
        view.setImageDrawable(null)
        view.alpha = 0f
        view.scaleX = 1f
        view.scaleY = 1f
    }

    private fun setAvailable(value: Boolean) {
        if (available == value) return
        available = value
        onAvailabilityChanged(value)
    }

    private companion object {
        const val EMPTY_RETRY_MS = 60_000L
        const val FAILED_RETRY_MS = 2_000L
        const val KEN_BURNS_SCALE = 1.12f
    }
}
