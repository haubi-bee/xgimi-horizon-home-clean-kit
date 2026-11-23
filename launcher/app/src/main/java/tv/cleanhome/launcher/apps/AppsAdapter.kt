package tv.cleanhome.launcher.apps

import android.annotation.SuppressLint
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tv.cleanhome.launcher.R
import tv.cleanhome.launcher.ui.FocusEffect

/**
 * Banner cards of the app row. OK launches, long OK (or MENU) opens the context menu.
 * Tiles without a banner show icon + label; a renamed banner tile shows its new name as a caption.
 */
class AppsAdapter(
    private val onClick: (AppEntry) -> Unit,
    private val onMenu: (AppEntry) -> Unit,
) : RecyclerView.Adapter<AppsAdapter.Holder>() {

    private var items: List<AppEntry> = emptyList()

    init {
        setHasStableIds(true)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<AppEntry>) {
        items = list
        notifyDataSetChanged()
    }

    fun positionOf(app: AppEntry): Int = items.indexOfFirst { it.key == app.key }.coerceAtLeast(0)

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].stableId

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    override fun onViewRecycled(holder: Holder) = holder.unbind()

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val banner: ImageView = view.findViewById(R.id.app_banner)
        private val caption: TextView = view.findViewById(R.id.app_caption)
        private val fallback: View = view.findViewById(R.id.app_fallback)
        private val icon: ImageView = view.findViewById(R.id.app_icon)
        private val label: TextView = view.findViewById(R.id.app_label)

        init {
            view.clipToOutline = true
            view.setOnClickListener { current()?.let(onClick) }
            view.setOnLongClickListener {
                current()?.let(onMenu)
                true
            }
            view.setOnKeyListener { _, keyCode, event ->
                if (keyCode != KeyEvent.KEYCODE_MENU) return@setOnKeyListener false
                if (event.action == KeyEvent.ACTION_UP) current()?.let(onMenu)
                true
            }
            view.setOnFocusChangeListener { v, hasFocus -> FocusEffect.animate(v, hasFocus) }
        }

        fun bind(app: AppEntry) {
            itemView.contentDescription = app.label
            if (app.banner != null) {
                banner.setImageDrawable(app.banner)
                banner.visibility = View.VISIBLE
                fallback.visibility = View.GONE
                caption.text = app.customLabel
                caption.visibility = if (app.customLabel != null) View.VISIBLE else View.GONE
            } else {
                banner.setImageDrawable(null)
                banner.visibility = View.GONE
                caption.visibility = View.GONE
                icon.setImageDrawable(app.icon)
                label.text = app.label
                fallback.visibility = View.VISIBLE
            }
            FocusEffect.applyImmediately(itemView, itemView.hasFocus())
        }

        fun unbind() {
            banner.setImageDrawable(null)
            icon.setImageDrawable(null)
            FocusEffect.applyImmediately(itemView, false)
        }

        private fun current(): AppEntry? =
            bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { items.getOrNull(it) }
    }
}
