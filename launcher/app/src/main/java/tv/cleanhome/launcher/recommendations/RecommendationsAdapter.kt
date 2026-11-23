package tv.cleanhome.launcher.recommendations

import android.annotation.SuppressLint
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import coil.size.Scale
import tv.cleanhome.launcher.R
import tv.cleanhome.launcher.ui.FocusEffect

/** Large 16:9 cards of the recommendations row. */
class RecommendationsAdapter(
    private val onClick: (Recommendation) -> Unit,
    private val onFocused: (Recommendation) -> Unit,
) : RecyclerView.Adapter<RecommendationsAdapter.Holder>() {

    private var items: List<Recommendation> = emptyList()

    init {
        setHasStableIds(true)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Recommendation>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].key.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_recommendation, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    override fun onViewRecycled(holder: Holder) = holder.unbind()

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val poster: ImageView = view.findViewById(R.id.recommendation_poster)
        private val cardWidth = view.resources.getDimensionPixelSize(R.dimen.rec_card_width)
        private val cardHeight = view.resources.getDimensionPixelSize(R.dimen.rec_card_height)
        private val placeholder = ColorDrawable(view.context.getColor(R.color.card_background))

        init {
            view.clipToOutline = true
            view.setOnClickListener { current()?.let(onClick) }
            view.setOnFocusChangeListener { v, hasFocus ->
                FocusEffect.animate(v, hasFocus)
                if (hasFocus) current()?.let(onFocused)
            }
        }

        fun bind(item: Recommendation) {
            itemView.contentDescription = item.row.title
            poster.load(Uri.parse(item.row.posterUri)) {
                size(cardWidth, cardHeight)
                scale(Scale.FILL)
                placeholder(placeholder)
                error(placeholder)
                crossfade(150)
            }
            FocusEffect.applyImmediately(itemView, itemView.hasFocus())
        }

        fun unbind() {
            poster.dispose()
            poster.setImageDrawable(null)
            FocusEffect.applyImmediately(itemView, false)
        }

        private fun current(): Recommendation? =
            bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { items.getOrNull(it) }
    }
}
