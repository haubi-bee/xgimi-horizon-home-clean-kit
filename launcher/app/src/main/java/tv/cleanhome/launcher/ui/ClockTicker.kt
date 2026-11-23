package tv.cleanhome.launcher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * Calls [onTick] on start, then every minute (ACTION_TIME_TICK) and whenever the time, the time
 * zone or the 12/24-hour setting changes. Start/stop it in onStart/onStop or while dreaming.
 */
class ClockTicker(private val context: Context, private val onTick: () -> Unit) {
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onTick()
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        context.registerReceiver(receiver, filter)
        registered = true
        onTick()
    }

    fun stop() {
        if (!registered) return
        context.unregisterReceiver(receiver)
        registered = false
    }
}
