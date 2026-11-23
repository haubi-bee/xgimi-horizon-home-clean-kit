package tv.cleanhome.launcher.photos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Places a photo fully visible on a display-sized canvas. If the aspect ratio does not match
 * (for example a portrait photo), a heavily blurred, dimmed version of the same photo fills the
 * borders, so the subject is never cropped.
 */
object PhotoFrameComposer {
    /** A nearly fitting photo may be cropped up to this fraction instead of showing thin borders. */
    private const val MAX_CROP_FRACTION = 0.10f
    private const val BACKDROP_WIDTH = 96
    private const val BLUR_RADIUS = 5
    private const val BLUR_PASSES = 3
    private const val BACKDROP_DIM_ALPHA = 90

    fun compose(photo: Bitmap, width: Int, height: Int): Bitmap {
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        val fitScale = min(width / photo.width.toFloat(), height / photo.height.toFloat())
        val fillScale = max(width / photo.width.toFloat(), height / photo.height.toFloat())
        val cropFraction = 1f - (fitScale / fillScale)

        if (cropFraction <= MAX_CROP_FRACTION) {
            canvas.drawBitmap(photo, null, centered(photo, fillScale, width, height), paint)
            return result
        }

        val backdrop = blurredBackdrop(photo, width, height)
        canvas.drawBitmap(backdrop, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
        backdrop.recycle()
        canvas.drawColor(Color.argb(BACKDROP_DIM_ALPHA, 0, 0, 0))
        canvas.drawBitmap(photo, null, centered(photo, fitScale, width, height), paint)
        return result
    }

    private fun centered(photo: Bitmap, scale: Float, width: Int, height: Int): RectF {
        val w = photo.width * scale
        val h = photo.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        return RectF(left, top, left + w, top + h)
    }

    /** Small, cropped-to-fill and blurred version; scaled up when drawn. */
    private fun blurredBackdrop(photo: Bitmap, width: Int, height: Int): Bitmap {
        val smallW = BACKDROP_WIDTH
        val smallH = max(1, (BACKDROP_WIDTH * height / width.toFloat()).roundToInt())
        val small = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
        val scale = max(smallW / photo.width.toFloat(), smallH / photo.height.toFloat())
        Canvas(small).drawBitmap(photo, null, centered(photo, scale, smallW, smallH), Paint(Paint.FILTER_BITMAP_FLAG))

        val pixels = IntArray(smallW * smallH)
        small.getPixels(pixels, 0, smallW, 0, 0, smallW, smallH)
        val scratch = IntArray(pixels.size)
        repeat(BLUR_PASSES) {
            boxBlur(pixels, scratch, smallW, smallH, horizontal = true)
            boxBlur(scratch, pixels, smallW, smallH, horizontal = false)
        }
        small.setPixels(pixels, 0, smallW, 0, 0, smallW, smallH)
        return small
    }

    /** One box-blur pass along one axis; edges are handled by repeating the edge pixels. */
    internal fun boxBlur(src: IntArray, dst: IntArray, width: Int, height: Int, horizontal: Boolean) {
        val lineLength = if (horizontal) width else height
        val lineCount = if (horizontal) height else width
        val window = 2 * BLUR_RADIUS + 1
        for (line in 0 until lineCount) {
            fun index(i: Int): Int {
                val c = i.coerceIn(0, lineLength - 1)
                return if (horizontal) line * width + c else c * width + line
            }
            var r = 0
            var g = 0
            var b = 0
            for (i in -BLUR_RADIUS..BLUR_RADIUS) {
                val p = src[index(i)]
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
            }
            for (i in 0 until lineLength) {
                dst[index(i)] = (0xFF shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                val leaving = src[index(i - BLUR_RADIUS)]
                val entering = src[index(i + BLUR_RADIUS + 1)]
                r += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
                g += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
                b += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
    }
}
