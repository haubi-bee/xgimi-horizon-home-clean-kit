package tv.cleanhome.launcher.ui

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Horizontal row for D-pad navigation: at the start or end of the row the focus stays put instead
 * of jumping into another row on LEFT/RIGHT.
 */
class TvRowLayoutManager(context: Context) : LinearLayoutManager(context, HORIZONTAL, false) {

    override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
        if (focused.layoutParams !is RecyclerView.LayoutParams) {
            return super.onInterceptFocusSearch(focused, direction)
        }
        val position = getPosition(focused)
        return when {
            direction == View.FOCUS_LEFT && position == 0 -> focused
            direction == View.FOCUS_RIGHT && position == itemCount - 1 -> focused
            else -> super.onInterceptFocusSearch(focused, direction)
        }
    }
}
