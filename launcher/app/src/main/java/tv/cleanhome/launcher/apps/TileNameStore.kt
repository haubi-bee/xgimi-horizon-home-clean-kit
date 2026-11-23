package tv.cleanhome.launcher.apps

import android.content.Context

/** Custom tile names chosen via "Rename", stored per tile key. */
class TileNameStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun names(): Map<String, String> =
        prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()

    /** Stores [name] for [key]; a blank name removes the custom name (back to the default label). */
    fun setName(key: String, name: String?) {
        val trimmed = name?.trim().orEmpty()
        val editor = prefs.edit()
        if (trimmed.isEmpty()) editor.remove(key) else editor.putString(key, trimmed)
        editor.apply()
    }

    private companion object {
        const val PREFS_NAME = "tile_names"
    }
}
