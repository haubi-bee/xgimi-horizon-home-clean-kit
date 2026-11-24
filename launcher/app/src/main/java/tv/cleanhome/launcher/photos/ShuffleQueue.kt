package tv.cleanhome.launcher.photos

import kotlin.random.Random

/**
 * Returns items in random order. Every cycle reloads the list (via `reload`), and the first item
 * of a new cycle is never the one returned last.
 */
class ShuffleQueue<T : Any>(private val random: Random = Random.Default) {
    private val pending = ArrayDeque<T>()
    private var last: T? = null

    fun next(reload: () -> List<T>): T? {
        if (pending.isEmpty()) {
            val items = reload().distinct().shuffled(random).toMutableList()
            if (items.isEmpty()) {
                last = null
                return null
            }
            if (items.size > 1 && items[0] == last) {
                val swapWith = 1 + random.nextInt(items.size - 1)
                items[0] = items[swapWith].also { items[swapWith] = items[0] }
            }
            pending.addAll(items)
        }
        return pending.removeFirst().also { last = it }
    }

    /** Forgets the rest of the current cycle, so the next call reloads the list. */
    fun startNewCycle() = pending.clear()
}
