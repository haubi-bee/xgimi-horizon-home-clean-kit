package tv.cleanhome.launcher.apps

import java.text.Collator
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Orders tiles by how often they are used. Pure Kotlin, no Android dependencies.
 *
 * Every tile is identified by a stable key (package name for apps, `input:<id>` for HDMI inputs).
 *
 * Order:
 * 1. Usage score, highest first.
 * 2. On a tie: the fixed start order ([preferredOrder]).
 * 3. Then all other tiles alphabetically by label (locale-aware).
 * 4. Keys in [lastOnTie] (system settings) go to the end of their tie group.
 */
class AppRanker(
    private val preferredOrder: List<String> = DEFAULT_PREFERRED_ORDER,
    private val lastOnTie: Set<String> = setOf(SETTINGS_PACKAGE),
    private val labelComparator: Comparator<String> = labelComparator(Locale.getDefault()),
) {

    fun <T> rank(
        items: List<T>,
        scores: Map<String, Double>,
        keyOf: (T) -> String,
        labelOf: (T) -> String,
    ): List<T> {
        val comparator = compareByDescending<T> { scoreKey(scores[keyOf(it)] ?: 0.0) }
            .thenBy { tieGroup(keyOf(it)) }
            .thenBy { preferredIndex(keyOf(it)) }
            .thenBy(labelComparator) { labelOf(it) }
            .thenBy { keyOf(it) }
        return items.sortedWith(comparator)
    }

    private fun tieGroup(key: String): Int = when (key) {
        in preferredOrder -> GROUP_PREFERRED
        in lastOnTie -> GROUP_LAST
        else -> GROUP_ALPHABETICAL
    }

    private fun preferredIndex(key: String): Int =
        preferredOrder.indexOf(key).let { if (it < 0) Int.MAX_VALUE else it }

    companion object {
        const val SETTINGS_PACKAGE = "com.android.tv.settings"

        /** Start order for a fresh install; only decides ties. */
        val DEFAULT_PREFERRED_ORDER = listOf(
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus",
        )

        private const val GROUP_PREFERRED = 0
        private const val GROUP_ALPHABETICAL = 1
        private const val GROUP_LAST = 2

        /** Round scores to 6 decimals so that ties are exact and the ordering stays transitive. */
        private fun scoreKey(score: Double): Long = (score * 1_000_000).roundToLong()

        /** Case-insensitive, accent-aware comparison following the rules of [locale]. */
        fun labelComparator(locale: Locale): Comparator<String> {
            val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
            return Comparator { a, b -> collator.compare(a, b) }
        }
    }
}
