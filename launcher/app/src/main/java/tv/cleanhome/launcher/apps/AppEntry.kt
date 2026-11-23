package tv.cleanhome.launcher.apps

import android.content.Intent
import android.graphics.drawable.Drawable

/**
 * One launchable tile of the app row: an installed app or a hardware HDMI input.
 *
 * [key] identifies the tile everywhere state is kept (usage scores, hidden set, custom names,
 * preferred order): the package name for apps, `input:<inputId>` for HDMI inputs.
 */
data class AppEntry(
    val key: String,
    val kind: Kind,
    /** Package that owns the tile; used for "App info". For HDMI inputs: the input service package. */
    val packageName: String,
    val defaultLabel: String,
    val banner: Drawable?,
    val icon: Drawable?,
    val launchIntent: Intent,
    /** Name chosen by the user via "Rename", or null. */
    val customLabel: String? = null,
) {
    enum class Kind { APP, HDMI_INPUT }

    val label: String get() = customLabel ?: defaultLabel

    val isHdmiInput: Boolean get() = kind == Kind.HDMI_INPUT

    val stableId: Long get() = key.hashCode().toLong()

    companion object {
        private const val INPUT_KEY_PREFIX = "input:"

        fun inputKey(inputId: String): String = INPUT_KEY_PREFIX + inputId
    }
}
