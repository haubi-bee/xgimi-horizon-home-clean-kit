package tv.cleanhome.launcher.recommendations

/** One row of the TvProvider's WatchNextPrograms or PreviewPrograms table. */
data class ProgramRow(
    val id: Long,
    val packageName: String,
    val title: String?,
    val description: String?,
    val posterUri: String?,
    val intentUri: String?,
    val weight: Int,
    val browsable: Boolean,
    val lastEngagementMillis: Long,
    val isWatchNext: Boolean,
) {
    /** Stable key for duplicate detection and RecyclerView ids. */
    val key: String
        get() = intentUri?.takeIf { it.isNotBlank() } ?: titleKey

    val titleKey: String
        get() = packageName + "\u0000" + (title?.trim()?.lowercase() ?: "")
}

/** A displayed recommendation including the name of its source app. */
data class Recommendation(
    val row: ProgramRow,
    val sourceLabel: String,
) {
    val key: String get() = row.key
    val packageName: String get() = row.packageName
}
