package tv.cleanhome.launcher.apps

import android.content.Context

/** Keeps the usage score per tile key in SharedPreferences. */
class UsageStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun scores(): Map<String, Double> =
        prefs.all.mapNotNull { (key, value) ->
            (value as? Float)?.let { key to it.toDouble() }
        }.toMap()

    fun recordLaunch(key: String) {
        val updated = UsageScores.afterLaunch(scores(), key)
        val editor = prefs.edit().clear()
        for ((tileKey, score) in updated) editor.putFloat(tileKey, score.toFloat())
        editor.apply()
    }

    private companion object {
        const val PREFS_NAME = "usage_scores"
    }
}
