package tv.cleanhome.launcher.recommendations

import org.junit.Assert.assertEquals
import org.junit.Test

class RecommendationMergerTest {

    private var nextId = 1L

    private fun row(
        pkg: String,
        title: String?,
        poster: String? = "https://img/$pkg/$title.jpg",
        intent: String? = "intent://$pkg/$title#Intent;end",
        weight: Int = 0,
        browsable: Boolean = true,
        engaged: Long = 0L,
        watchNext: Boolean = false,
    ) = ProgramRow(nextId++, pkg, title, null, poster, intent, weight, browsable, engaged, watchNext)

    private fun merge(
        watchNext: List<ProgramRow> = emptyList(),
        preview: List<ProgramRow> = emptyList(),
        installed: Set<String> = setOf("a", "b", "c"),
        order: List<String> = listOf("a", "b", "c"),
        max: Int = RecommendationMerger.MAX_ITEMS,
    ) = RecommendationMerger.merge(watchNext, preview, { it in installed }, { pkgs -> order.filter { it in pkgs } }, max)

    @Test
    fun watchNextIsCappedPerPackageAndInTotalSoPreviewStillAppears() {
        val watchNext = (1..10).map { row("a", "a$it", engaged = 1000L - it, watchNext = true) } +
            (1..10).map { row("b", "b$it", engaged = 500L - it, watchNext = true) }
        val result = merge(watchNext = watchNext, preview = listOf(row("c", "p1")))
        assertEquals(listOf("a1", "a2", "b1", "b2", "p1"), result.map { it.title })
    }

    @Test
    fun watchNextFirstNewestFirstThenPreview() {
        val result = merge(
            watchNext = listOf(row("b", "older", engaged = 100, watchNext = true), row("c", "newer", engaged = 500, watchNext = true)),
            preview = listOf(row("a", "p1")),
        )
        assertEquals(listOf("newer", "older", "p1"), result.map { it.title })
    }

    @Test
    fun previewIsRoundRobinOverPackagesByWeight() {
        val result = merge(
            preview = listOf(
                row("a", "a-low", weight = 1), row("a", "a-high", weight = 9), row("a", "a-mid", weight = 5),
                row("b", "b1"),
                row("c", "c1"), row("c", "c2"),
            ),
        )
        assertEquals(listOf("a-high", "b1", "c1", "a-mid", "c2", "a-low"), result.map { it.title })
    }

    @Test
    fun filtersMissingTitlePosterUninstalledAndNotBrowsable() {
        val result = merge(
            preview = listOf(
                row("a", null),
                row("a", " "),
                row("a", "no poster", poster = null),
                row("x", "not installed"),
                row("b", "hidden", browsable = false),
                row("b", "ok"),
            ),
        )
        assertEquals(listOf("ok"), result.map { it.title })
    }

    @Test
    fun removesDuplicatesByIntentAndByTitle() {
        val result = merge(
            watchNext = listOf(row("a", "Episode 1", intent = "intent:x", watchNext = true)),
            preview = listOf(
                row("a", "Other episode", intent = "intent:x"),
                row("a", "episode 1 ", intent = "intent:y"),
                row("a", "Episode 2", intent = "intent:z"),
            ),
        )
        assertEquals(listOf("Episode 1", "Episode 2"), result.map { it.title })
    }

    @Test
    fun limitsToMaxItems() {
        val preview = (1..40).map { row(listOf("a", "b", "c")[it % 3], "t$it") }
        assertEquals(24, merge(preview = preview).size)
        assertEquals(5, merge(preview = preview, max = 5).size)
    }

    @Test
    fun packagesUnknownToOrderComeAlphabeticallyAfterwards() {
        val result = merge(
            preview = listOf(row("c", "c1"), row("b", "b1"), row("a", "a1")),
            order = listOf("b"),
        )
        assertEquals(listOf("b1", "a1", "c1"), result.map { it.title })
    }
}
