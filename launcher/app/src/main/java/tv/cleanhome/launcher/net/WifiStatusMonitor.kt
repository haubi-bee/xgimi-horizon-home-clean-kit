package tv.cleanhome.launcher.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import tv.cleanhome.launcher.CleanHomeApp.Companion.TAG

/**
 * Reports whether a Wi-Fi network with internet capability is connected. Callbacks arrive on the
 * main thread. Call [start] in onStart and [stop] in onStop.
 */
class WifiStatusMonitor(context: Context, private val onChange: (connected: Boolean) -> Unit) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val wifiNetworks = HashSet<Network>()
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifiNetworks += network
            publish()
        }

        override fun onLost(network: Network) {
            wifiNetworks -= network
            publish()
        }
    }

    fun start() {
        val manager = connectivity ?: return
        if (registered) return
        wifiNetworks.clear()
        // Initial state right away; the callback confirms it shortly after registering.
        onChange(isWifiActive(manager))
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            manager.registerNetworkCallback(request, callback, handler)
            registered = true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Wi-Fi state callback not available", e)
        }
    }

    fun stop() {
        if (!registered) return
        try {
            connectivity?.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Wi-Fi state callback was not registered", e)
        }
        registered = false
        wifiNetworks.clear()
    }

    private fun publish() = onChange(wifiNetworks.isNotEmpty())

    private fun isWifiActive(manager: ConnectivityManager): Boolean = try {
        manager.activeNetwork
            ?.let { manager.getNetworkCapabilities(it) }
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (e: SecurityException) {
        Log.w(TAG, "Network state not readable", e)
        false
    }
}
