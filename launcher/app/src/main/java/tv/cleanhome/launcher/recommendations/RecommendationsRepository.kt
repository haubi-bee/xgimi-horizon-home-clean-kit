package tv.cleanhome.launcher.recommendations

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.tv.TvContract
import android.net.Uri
import android.util.Log
import tv.cleanhome.launcher.CleanHomeApp.Companion.TAG

/**
 * Reads the preview content ("watch next" and preview programs) of all apps from the TvProvider.
 * Blocking; call on an IO thread. Errors never throw; they lead to an empty list and a diagnostic
 * message in logcat (tag "CleanHome").
 */
class RecommendationsRepository(private val context: Context) {

    fun load(orderPackages: (Collection<String>) -> List<String>): List<Recommendation> {
        val watchNext = readTable("WatchNextPrograms", TvContract.WatchNextPrograms.CONTENT_URI, isWatchNext = true)
        val preview = readTable("PreviewPrograms", TvContract.PreviewPrograms.CONTENT_URI, isWatchNext = false)

        val labels = HashMap<String, String?>()
        fun availableLabel(packageName: String): String? =
            labels.getOrPut(packageName) { enabledAppLabel(packageName) }

        val merged = try {
            RecommendationMerger.merge(
                watchNext = watchNext.rows,
                preview = preview.rows,
                isPackageAvailable = { availableLabel(it) != null },
                orderPackages = orderPackages,
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "Recommendations: merging failed", e)
            emptyList()
        }
        val result = merged.mapNotNull { row -> availableLabel(row.packageName)?.let { Recommendation(row, it) } }

        val summary = "Recommendations: ${watchNext.describe()}; ${preview.describe()}; shown ${result.size} " +
            "(${result.groupingBy { it.packageName }.eachCount()})"
        if (result.isEmpty() || watchNext.error != null || preview.error != null) {
            Log.w(TAG, summary)
        } else {
            Log.i(TAG, summary)
        }
        return result
    }

    private fun readTable(name: String, uri: Uri, isWatchNext: Boolean): TableRead = try {
        context.contentResolver.query(uri, null, null, null, null).use { cursor ->
            if (cursor == null) {
                TableRead(name, emptyList(), "cursor is null (TvProvider unavailable?)")
            } else {
                val columns = Columns(cursor)
                val rows = ArrayList<ProgramRow>(cursor.count.coerceAtLeast(0))
                while (cursor.moveToNext()) {
                    columns.read(cursor, isWatchNext)?.let(rows::add)
                }
                TableRead(name, rows, columns.missingDescription())
            }
        }
    } catch (e: SecurityException) {
        TableRead(name, emptyList(), "SecurityException (READ_TV_LISTINGS/READ_EPG_DATA missing?): ${e.message}")
    } catch (e: Exception) {
        TableRead(name, emptyList(), "${e.javaClass.simpleName}: ${e.message}")
    }

    private fun enabledAppLabel(packageName: String): String? = try {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(packageName, 0)
        if (info.enabled) pm.getApplicationLabel(info).toString() else null
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private class TableRead(val name: String, val rows: List<ProgramRow>, val error: String?) {
        fun describe(): String {
            val perPackage = rows.groupingBy { it.packageName }.eachCount()
            return "$name ${rows.size} rows $perPackage" + (error?.let { " [error: $it]" } ?: "")
        }
    }

    /** Column indices; missing columns yield -1 and are replaced with defaults. */
    private class Columns(cursor: Cursor) {
        private val id = cursor.getColumnIndex("_id")
        private val packageName = cursor.getColumnIndex("package_name")
        private val title = cursor.getColumnIndex("title")
        private val description = cursor.getColumnIndex("short_description")
        private val poster = cursor.getColumnIndex("poster_art_uri")
        private val intent = cursor.getColumnIndex("intent_uri")
        private val weight = cursor.getColumnIndex("weight")
        private val browsable = cursor.getColumnIndex("browsable")
        private val lastEngagement = cursor.getColumnIndex("last_engagement_time_utc_millis")

        private val required = mapOf("package_name" to packageName, "title" to title, "poster_art_uri" to poster)

        fun read(c: Cursor, isWatchNext: Boolean): ProgramRow? {
            val pkg = c.stringOrNull(packageName) ?: return null
            return ProgramRow(
                id = c.longOr(id, 0L),
                packageName = pkg,
                title = c.stringOrNull(title),
                description = c.stringOrNull(description),
                posterUri = c.stringOrNull(poster),
                intentUri = c.stringOrNull(intent),
                weight = c.longOr(weight, 0L).toInt(),
                browsable = c.longOr(browsable, 1L) != 0L,
                lastEngagementMillis = c.longOr(lastEngagement, 0L),
                isWatchNext = isWatchNext,
            )
        }

        fun missingDescription(): String? =
            required.filterValues { it < 0 }.keys.takeIf { it.isNotEmpty() }?.let { "missing columns: $it" }

        private fun Cursor.stringOrNull(index: Int): String? =
            if (index < 0 || isNull(index)) null else getString(index)

        private fun Cursor.longOr(index: Int, default: Long): Long =
            if (index < 0 || isNull(index)) default else getLong(index)
    }
}
