package com.financeautomation.core.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observes whether the device currently has a validated internet connection.
 * Backed by [ConnectivityManager.registerDefaultNetworkCallback]; requires ACCESS_NETWORK_STATE.
 * Holds only a boolean and never inspects traffic.
 */
class ConnectivityObserver(context: Context) {

    private val connectivityManager: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val _isOnline = MutableStateFlow(readCurrentState())

    /** True when the default network has the internet capability and has been validated. */
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            _isOnline.value = hasValidatedInternet(networkCapabilities)
        }

        override fun onLost(network: Network) {
            _isOnline.value = false
        }

        override fun onUnavailable() {
            _isOnline.value = false
        }
    }

    init {
        try {
            connectivityManager?.registerDefaultNetworkCallback(callback)
        } catch (e: SecurityException) {
            Diagnostics.record("ConnectivityObserver", "${e.javaClass.simpleName}: network callback not registered")
        } catch (e: RuntimeException) {
            Diagnostics.record("ConnectivityObserver", "${e.javaClass.simpleName}: network callback not registered")
        }
    }

    private fun readCurrentState(): Boolean {
        return try {
            val cm = connectivityManager ?: return true
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            hasValidatedInternet(caps)
        } catch (e: SecurityException) {
            true
        }
    }

    companion object {
        fun hasValidatedInternet(capabilities: NetworkCapabilities?): Boolean =
            capabilities != null &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
