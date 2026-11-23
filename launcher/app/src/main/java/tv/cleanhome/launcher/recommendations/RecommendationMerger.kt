package tv.cleanhome.launcher.recommendations

/**
 * Filters and merges "watch next" and preview programs. Pure Kotlin logic.
 *
 * - Only entries with title, poster, browsable, and from available packages.
 * - "Watch next" first (most recently engaged first), then preview programs round-robin across
 *   packages (within a package by weight, highest first).
 * - Duplicates (same intent URI or same title of the same app) are dropped.
 */
object RecommendationMerger {
    const val MAX_ITEMS = 24
    const val MAX_WATCH_NEXT = 6
    const val MAX_WATCH_NEXT_PER_PACKAGE = 2

    fun merge(
        watchNext: List<ProgramRow>,
        preview: List<ProgramRow>,
        isPackageAvailable: (String) -> Boolean,
        orderPackages: (Collection<String>) -> List<String>,
        maxItems: Int = MAX_ITEMS,
        maxWatchNext: Int = MAX_WATCH_NEXT,
        maxWatchNextPerPackage: Int = MAX_WATCH_NEXT_PER_PACKAGE,
    ): List<ProgramRow> {
        fun usable(row: ProgramRow) =
            !row.title.isNullOrBlank() &&
                !row.posterUri.isNullOrBlank() &&
                row.browsable &&
                isPackageAvailable(row.packageName)

        val result = ArrayList<ProgramRow>(maxItems)
        val seenKeys = HashSet<String>()

        fun add(row: ProgramRow): Boolean {
            val keys = listOfNotNull(row.intentUri?.takeIf { it.isNotBlank() }, row.titleKey)
            if (keys.any { it in seenKeys }) return false
            seenKeys.addAll(keys)
            result.add(row)
            return true
        }

        watchNext
            .filter(::usable)
            .sortedWith(compareByDescending<ProgramRow> { it.lastEngagementMillis }.thenByDescending { it.id })
            .forEach { row ->
                // Cap "watch next" (in total and per app); otherwise one heavily used app fills the
                // whole row and the other apps' suggestions never show up.
                val fromSamePackage = result.count { it.packageName == row.packageName }
                if (result.size < minOf(maxItems, maxWatchNext) && fromSamePackage < maxWatchNextPerPackage) add(row)
            }

        val byPackage = preview
            .filter(::usable)
            .groupBy { it.packageName }
            .mapValues { (_, rows) ->
                rows.sortedWith(compareByDescending<ProgramRow> { it.weight }.thenBy { it.id })
            }

        val ordered = orderPackages(byPackage.keys).filter { it in byPackage }.distinct()
        val packageOrder = ordered + (byPackage.keys - ordered.toSet()).sorted()
        val queues = packageOrder.mapNotNull { byPackage[it]?.iterator() }.toMutableList()

        while (result.size < maxItems && queues.isNotEmpty()) {
            val round = queues.iterator()
            while (round.hasNext() && result.size < maxItems) {
                val queue = round.next()
                while (queue.hasNext()) {
                    if (add(queue.next())) break
                }
                if (!queue.hasNext()) round.remove()
            }
        }
        return result
    }
}
