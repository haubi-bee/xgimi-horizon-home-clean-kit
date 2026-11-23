package tv.cleanhome.launcher.ui

import android.view.View
import tv.cleanhome.launcher.R

/** Visible focus: slight zoom and lift above the neighbouring cards (the frame comes from a selector). */
object FocusEffect {
    private const val FOCUSED_SCALE = 1.08f
    private const val DURATION_MS = 140L

    fun animate(view: View, focused: Boolean) {
        val scale = if (focused) FOCUSED_SCALE else 1f
        view.animate()
            .scaleX(scale)
            .scaleY(scale)
            .translationZ(elevation(view, focused))
            .setDuration(DURATION_MS)
            .start()
    }

    fun applyImmediately(view: View, focused: Boolean) {
        view.animate().cancel()
        val scale = if (focused) FOCUSED_SCALE else 1f
        view.scaleX = scale
        view.scaleY = scale
        view.translationZ = elevation(view, focused)
    }

    private fun elevation(view: View, focused: Boolean): Float =
        if (focused) view.resources.getDimension(R.dimen.focus_elevation) else 0f
}
