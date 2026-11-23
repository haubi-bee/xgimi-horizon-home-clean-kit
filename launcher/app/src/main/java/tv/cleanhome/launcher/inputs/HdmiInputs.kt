package tv.cleanhome.launcher.inputs

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.util.Log
import tv.cleanhome.launcher.CleanHomeApp.Companion.TAG
import tv.cleanhome.launcher.R

/** A hardware HDMI input of the device. */
data class HdmiInput(
    val id: String,
    val defaultLabel: String,
    /** Package of the TV input service that provides this input. */
    val servicePackage: String,
    val launchIntent: Intent,
)

/**
 * Reads the hardware HDMI inputs from [TvInputManager] and builds the intent that switches to each.
 * Blocking; call on an IO thread.
 *
 * XGIMI firmware switches inputs with its own player activity (the same call its system UI makes);
 * other devices get the standard passthrough-channel intent, handled by the system TV app.
 */
class HdmiInputs(private val context: Context) {

    fun load(): List<HdmiInput> {
        val manager = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return emptyList()
        val inputs = try {
            manager.tvInputList
        } catch (e: RuntimeException) {
            Log.w(TAG, "Reading the TV input list failed", e)
            return emptyList()
        }
        return inputs
            .filter { it.isPassthroughInput && it.type == TvInputInfo.TYPE_HDMI && it.parentId == null }
            .sortedBy { it.id }
            .mapIndexed { index, info ->
                HdmiInput(
                    id = info.id,
                    defaultLabel = labelOf(info, index),
                    servicePackage = info.serviceInfo.packageName,
                    launchIntent = launchIntent(info.id),
                )
            }
    }

    /** The user's label from the system TV settings, else the input's own label, else "HDMI n". */
    private fun labelOf(info: TvInputInfo, index: Int): String {
        val label = try {
            info.loadCustomLabel(context)?.toString()?.trim().orEmpty()
                .ifEmpty { info.loadLabel(context)?.toString()?.trim().orEmpty() }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Label of input ${info.id} not readable", e)
            ""
        }
        return label.ifEmpty { context.getString(R.string.hdmi_default_label, index + 1) }
    }

    private fun launchIntent(inputId: String): Intent {
        val xgimi = xgimiPlayerIntent(inputId)
        if (resolves(context.packageManager, xgimi)) return xgimi
        return Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun resolves(pm: PackageManager, intent: Intent): Boolean = try {
        pm.resolveActivity(intent, 0) != null
    } catch (e: RuntimeException) {
        Log.w(TAG, "Resolving $intent failed", e)
        false
    }

    private fun xgimiPlayerIntent(inputId: String): Intent =
        Intent(XGIMI_ACTION)
            .setData(TvContract.buildChannelsUriForInput(inputId))
            .setComponent(ComponentName(XGIMI_PLAYER_PACKAGE, XGIMI_PLAYER_ACTIVITY))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private companion object {
        const val XGIMI_PLAYER_PACKAGE = "com.xgimi.xhplayer"
        const val XGIMI_PLAYER_ACTIVITY = "com.xgimi.xhplayer.InputActivity"
        const val XGIMI_ACTION = "com.xgimi.action.hdmiPlayer"
    }
}
