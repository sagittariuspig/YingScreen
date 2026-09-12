package io.carmo.airplay.receiver

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Monitors network connectivity and triggers DNS-SD refresh when the network
 * changes. Registered at the service level so it works whether or not the
 * activity is visible.
 */
class NetworkMonitor(
    context: Context,
    private val onNetworkAvailable: () -> Unit,
    private val onNetworkLost: () -> Unit
) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isRegistered = false
    private val delayedRefresh = Runnable { onNetworkAvailable() }

    private fun scheduleRefresh() {
        mainHandler.removeCallbacks(delayedRefresh)
        mainHandler.post { onNetworkAvailable() }
        mainHandler.postDelayed(delayedRefresh, SECOND_REFRESH_DELAY_MS)
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "network available")
            scheduleRefresh()
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "network lost")
            mainHandler.post { onNetworkLost() }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            scheduleRefresh()
        }
    }

    fun start() {
        if (isRegistered) return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            isRegistered = true
            Log.d(TAG, "network monitor started")
        } catch (e: Exception) {
            Log.e(TAG, "failed to register network callback", e)
        }
    }

    fun stop() {
        mainHandler.removeCallbacks(delayedRefresh)
        if (!isRegistered) return
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            isRegistered = false
        } catch (e: Exception) {
            Log.e(TAG, "failed to unregister network callback", e)
        }
    }

    companion object {
        private const val TAG = "Receiver-Network"
        private const val SECOND_REFRESH_DELAY_MS = 2_500L
    }
}
