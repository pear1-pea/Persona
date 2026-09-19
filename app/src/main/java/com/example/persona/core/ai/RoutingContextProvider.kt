package com.example.persona.core.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoutingContextProvider @Inject constructor(
    @ApplicationContext context: Context
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun networkState(): NetworkState {
        val network = connectivityManager.activeNetwork ?: return NetworkState.OFFLINE
        val capabilities = connectivityManager.getNetworkCapabilities(network)
            ?: return NetworkState.UNKNOWN
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return NetworkState.OFFLINE
        }
        return if (connectivityManager.isActiveNetworkMetered) {
            NetworkState.METERED
        } else {
            NetworkState.UNMETERED
        }
    }
}
