package tv.cleanhome.launcher.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class ShuffleQueueTest {

    @Test
    fun eachCycleContainsEveryItemOnceAndReloads() {
        var reloads = 0
        val queue = ShuffleQueue<String>(Random(1))
        val items = listOf("a", "b", "c", "d")
        val cycle1 = List(4) { queue.next { reloads++; items }!! }
        val cycle2 = List(4) { queue.next { reloads++; items }!! }
        assertEquals(items.toSet(), cycle1.toSet())
        assertEquals(items.toSet(), cycle2.toSet())
        assertEquals(2, reloads)
    }

    @Test
    fun startNewCycleReloadsImmediately() {
        val queue = ShuffleQueue<String>(Random(1))
        queue.next { listOf("a", "b", "c") }
        queue.startNewCycle()
        assertEquals("x", queue.next { listOf("x") })
    }

    @Test
    fun neverRepeatsDirectlyAcrossCycles() {
        val queue = ShuffleQueue<Int>(Random(42))
        var previous: Int? = null
        repeat(3_000) {
            val current = queue.next { listOf(1, 2, 3) }
            assertNotEquals(previous, current)
            previous = current
        }
    }

    @Test
    fun picksUpChangedFolderContentOnNextCycle() {
        val queue = ShuffleQueue<String>(Random(3))
        var content = listOf("x")
        assertEquals("x", queue.next { content })
        content = listOf("y")
        assertEquals("y", queue.next { content })
    }

    @Test
    fun emptyReturnsNullAndSingleItemRepeats() {
        val queue = ShuffleQueue<String>()
        assertNull(queue.next { emptyList() })
        assertEquals("only", queue.next { listOf("only") })
        assertEquals("only", queue.next { listOf("only") })
    }
}
