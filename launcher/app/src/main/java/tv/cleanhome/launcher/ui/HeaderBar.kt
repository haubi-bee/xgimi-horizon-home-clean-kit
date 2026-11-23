package tv.cleanhome.launcher.ui

import android.view.View
import android.widget.ImageButton
import tv.cleanhome.launcher.R

/**
 * The round shortcut buttons left of the clock: HDMI, Wi-Fi and Settings (left to right).
 * Reached with UP from the top row; LEFT/RIGHT move between them and stop at the outer buttons;
 * DOWN goes back to the rows (default focus search).
 */
class HeaderBar(
    private val hdmiButton: ImageButton,
    private val wifiButton: ImageButton,
    private val settingsButton: ImageButton,
    onHdmi: () -> Unit,
    onWifi: () -> Unit,
    onSettings: () -> Unit,
    onSettingsMenu: () -> Unit,
) {
    private val buttons = listOf(hdmiButton, wifiButton, settingsButton)

    init {
        hdmiButton.setOnClickListener { onHdmi() }
        wifiButton.setOnClickListener { onWifi() }
        settingsButton.setOnClickListener { onSettings() }
        settingsButton.setOnLongClickListener {
            onSettingsMenu()
            true
        }
        for (button in buttons) {
            button.setOnFocusChangeListener { view, focused -> FocusEffect.animate(view, focused) }
        }
        setWifiConnected(false)
        linkHorizontalFocus()
    }

    /** Shows the HDMI button only if the device has at least one hardware HDMI input. */
    fun setHdmiAvailable(available: Boolean) {
        val visibility = if (available) View.VISIBLE else View.GONE
        if (hdmiButton.visibility == visibility) return
        if (!available && hdmiButton.isFocused) wifiButton.requestFocus()
        hdmiButton.visibility = visibility
        linkHorizontalFocus()
    }

    fun setWifiConnected(connected: Boolean) {
        wifiButton.setImageResource(if (connected) R.drawable.ic_wifi else R.drawable.ic_wifi_off)
        val description = if (connected) R.string.wifi_button_connected else R.string.wifi_button_disconnected
        wifiButton.contentDescription = wifiButton.context.getString(description)
    }

    private fun linkHorizontalFocus() {
        val visible = buttons.filter { it.visibility == View.VISIBLE }
        visible.forEachIndexed { index, button ->
            button.nextFocusLeftId = (visible.getOrNull(index - 1) ?: button).id
            button.nextFocusRightId = (visible.getOrNull(index + 1) ?: button).id
        }
    }
}
