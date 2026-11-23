package tv.cleanhome.launcher.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.util.Log
import tv.cleanhome.launcher.CleanHomeApp.Companion.TAG
import tv.cleanhome.launcher.R
import tv.cleanhome.launcher.inputs.HdmiInputs

/**
 * Builds the tiles of the app row: every MAIN+LEANBACK_LAUNCHER activity, MAIN+LAUNCHER activities of
 * non-system apps without a leanback entry, and one tile per hardware HDMI input. One tile per key
 * (package name / input). The system settings are not a tile; they sit on the gear in the header.
 *
 * Blocking; call on an IO thread.
 */
class AppsRepository(private val context: Context) {

    private val hdmiInputs = HdmiInputs(context)

    fun loadTiles(): List<AppEntry> {
        val pm = context.packageManager
        val excluded = setOf(context.packageName, AppRanker.SETTINGS_PACKAGE) + listOfNotNull(settingsPackage(pm))
        val result = LinkedHashMap<String, AppEntry>()

        val leanbackPackages = HashSet<String>()
        for (info in queryActivities(pm, Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            if (info.packageName in excluded) continue
            leanbackPackages += info.packageName
            if (info.packageName in result) continue
            buildEntry(pm, info, Intent.CATEGORY_LEANBACK_LAUNCHER)?.let { result[it.key] = it }
        }

        for (info in queryActivities(pm, Intent.CATEGORY_LAUNCHER)) {
            if (info.packageName in excluded || info.packageName in leanbackPackages || info.packageName in result) continue
            if (info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) continue
            buildEntry(pm, info, Intent.CATEGORY_LAUNCHER)?.let { result[it.key] = it }
        }

        val hdmiIcon = context.getDrawable(R.drawable.ic_input_hdmi)
        for (input in hdmiInputs.load()) {
            val entry = AppEntry(
                key = AppEntry.inputKey(input.id),
                kind = AppEntry.Kind.HDMI_INPUT,
                packageName = input.servicePackage,
                defaultLabel = input.defaultLabel,
                banner = null,
                icon = hdmiIcon?.constantState?.newDrawable()?.mutate() ?: hdmiIcon,
                launchIntent = input.launchIntent,
            )
            result.putIfAbsent(entry.key, entry)
        }
        return result.values.toList()
    }

    private fun queryActivities(pm: PackageManager, category: String): List<ActivityInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        return try {
            pm.queryIntentActivities(intent, 0).mapNotNull { it.activityInfo }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Querying $category activities failed", e)
            emptyList()
        }
    }

    /** Some devices ship their settings under a different package than [AppRanker.SETTINGS_PACKAGE]. */
    private fun settingsPackage(pm: PackageManager): String? = try {
        pm.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName
    } catch (e: RuntimeException) {
        Log.w(TAG, "Resolving the system settings failed", e)
        null
    }

    private fun buildEntry(pm: PackageManager, info: ActivityInfo, category: String): AppEntry? {
        val component = ComponentName(info.packageName, info.name)
        val launchIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(category)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            val label = info.loadLabel(pm).toString().trim().ifEmpty { info.packageName }
            // loadBanner falls back to the application banner by itself.
            val banner: Drawable? = info.loadBanner(pm)
            val icon: Drawable? = if (banner == null) info.loadIcon(pm) else null
            AppEntry(
                key = info.packageName,
                kind = AppEntry.Kind.APP,
                packageName = info.packageName,
                defaultLabel = label,
                banner = banner,
                icon = icon,
                launchIntent = launchIntent,
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "Skipping app ${component.flattenToShortString()}", e)
            null
        }
    }
}
