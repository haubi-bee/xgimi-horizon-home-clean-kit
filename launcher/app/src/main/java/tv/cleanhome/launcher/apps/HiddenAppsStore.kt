package tv.cleanhome.launcher.apps

import android.content.Context

/** Tile keys the user has hidden from the app row (a SharedPreferences string set). */
class HiddenAppsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun keys(): Set<String> = prefs.getStringSet(KEY_HIDDEN, null)?.toSet() ?: emptySet()

    fun hide(key: String) {
        prefs.edit().putStringSet(KEY_HIDDEN, keys() + key).apply()
    }

    fun unhideAll() {
        prefs.edit().remove(KEY_HIDDEN).apply()
    }

    private companion object {
        const val PREFS_NAME = "hidden_apps"
        const val KEY_HIDDEN = "keys"
    }
}
