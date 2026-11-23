package tv.cleanhome.launcher.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AppRankerTest {

    private data class Tile(val key: String, val label: String)

    private val youtube = Tile("com.google.android.youtube.tv", "YouTube")
    private val netflix = Tile("com.netflix.ninja", "Netflix")
    private val prime = Tile("com.amazon.amazonvideo.livingroom", "Prime Video")
    private val disney = Tile("com.disney.disneyplus", "Disney+")
    private val zattoo = Tile("com.zattoo.player", "Zattoo")
    private val arte = Tile("tv.arte.plus7", "Arte")
    private val kodi = Tile("org.xbmc.kodi", "Kodi")
    private val settings = Tile(AppRanker.SETTINGS_PACKAGE, "Settings")

    private val all = listOf(settings, zattoo, kodi, disney, arte, prime, netflix, youtube)
    private val ranker = AppRanker(labelComparator = AppRanker.labelComparator(Locale.ENGLISH))

    private fun rank(scores: Map<String, Double>, items: List<Tile> = all) =
        ranker.rank(items, scores, { it.key }, { it.label })

    private fun scoresAfter(vararg launches: Tile): Map<String, Double> =
        launches.fold(emptyMap()) { scores, tile -> UsageScores.afterLaunch(scores, tile.key) }

    @Test
    fun withoutUsage_startOrderThenAlphabeticalThenSettings() {
        assertEquals(
            listOf(youtube, netflix, prime, disney, arte, kodi, zattoo, settings),
            rank(emptyMap()),
        )
    }

    @Test
    fun umlautsAreSortedLikeTheirBaseLettersInGerman() {
        val list = AppRanker(labelComparator = AppRanker.labelComparator(Locale.GERMAN)).rank(
            listOf(Tile("p.z", "Zebra"), Tile("p.oe", "Öko"), Tile("p.o", "Oper"), Tile("p.a", "Ärger"), Tile("p.b", "Bad")),
            emptyMap(), { it.key }, { it.label },
        )
        // Ä sorts like A and Ö like O (not after Z).
        assertEquals(listOf("Ärger", "Bad", "Öko", "Oper", "Zebra"), list.map { it.label })
    }

    @Test
    fun appMovesUpAfterSeveralLaunches() {
        val ranked = rank(scoresAfter(zattoo, zattoo, zattoo))
        assertEquals(zattoo, ranked.first())
        // The rest keeps its start order.
        assertEquals(listOf(youtube, netflix, prime, disney, arte, kodi, settings), ranked.drop(1))
    }

    @Test
    fun moreFrequentAppBeatsLessFrequentApp() {
        val ranked = rank(scoresAfter(netflix, arte, arte, netflix, arte))
        assertEquals(listOf(arte, netflix), ranked.take(2))
    }

    @Test
    fun recentLaunchWinsOnEqualCountBecauseOfDecay() {
        val ranked = rank(scoresAfter(netflix, arte))
        assertEquals(listOf(arte, netflix), ranked.take(2))
    }

    @Test
    fun settingsOnlyLastOnTie() {
        val ranked = rank(scoresAfter(settings))
        assertEquals(settings, ranked.first())

        val tied = rank(mapOf(settings.key to 2.0, zattoo.key to 2.0, youtube.key to 2.0))
        assertEquals(listOf(youtube, zattoo, settings), tied.take(3))
    }

    @Test
    fun hdmiInputsAreTrackedSeparatelyByKey() {
        val hdmi1 = Tile(AppEntry.inputKey("com.xgimi.tvinput/.inputservice.Hdmi1TvInputService/HW1"), "HDMI 1")
        val hdmi2 = Tile(AppEntry.inputKey("com.xgimi.tvinput/.inputservice.Hdmi2TvInputService/HW2"), "PlayStation")
        val items = all + hdmi1 + hdmi2

        val ranked = rank(scoresAfter(hdmi2, hdmi2), items)
        assertEquals(hdmi2, ranked.first())
        // HDMI 1 has no usage and is sorted alphabetically among the other unused tiles.
        assertEquals(listOf(youtube, netflix, prime, disney, arte, hdmi1, kodi, zattoo, settings), ranked.drop(1))

        val both = rank(scoresAfter(hdmi1, hdmi2, hdmi2), items)
        assertEquals(listOf(hdmi2, hdmi1), both.take(2))
    }

    @Test
    fun customLabelDecidesAlphabeticalPosition() {
        val renamed = Tile(AppEntry.inputKey("input/.Hdmi/HW3"), "Apple TV")
        val ranked = rank(emptyMap(), all + renamed)
        assertEquals(listOf(youtube, netflix, prime, disney, renamed, arte), ranked.take(6))
    }

    @Test
    fun inputKeysDoNotCollideWithPackageNames() {
        val key = AppEntry.inputKey("com.netflix.ninja")
        assertEquals("input:com.netflix.ninja", key)
        assertTrue(key != netflix.key)
    }

    @Test
    fun decayMultipliesAllScoresAndAddsOneToLaunchedTile() {
        val after = UsageScores.afterLaunch(mapOf("a" to 1.0, "b" to 2.0), "b")
        assertEquals(0.97, after.getValue("a"), 1e-9)
        assertEquals(2.0 * 0.97 + 1.0, after.getValue("b"), 1e-9)

        val first = UsageScores.afterLaunch(emptyMap(), "c")
        assertEquals(1.0, first.getValue("c"), 1e-9)
    }

    @Test
    fun negligibleScoresArePruned() {
        var scores = UsageScores.afterLaunch(emptyMap(), "old")
        repeat(200) { scores = UsageScores.afterLaunch(scores, "new") }
        assertTrue("old" !in scores)
        assertTrue(scores.getValue("new") > 1.0)
    }
}
