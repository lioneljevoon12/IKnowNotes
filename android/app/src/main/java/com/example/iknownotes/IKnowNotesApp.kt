package com.example.iknownotes

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import com.example.iknownotes.data.remote.RetrofitClient
import com.example.iknownotes.data.repo.NotesRepository

class IKnowNotesApp : Application() {

    override fun onCreate() {
        super.onCreate()
        watchConnectivity()
    }

    /** Pushes offline changes as soon as the device is back online. */
    private fun watchConnectivity() {
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return
        try {
            connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (RetrofitClient.getSessionManager(this@IKnowNotesApp).isLoggedIn) {
                        NotesRepository.get(this@IKnowNotesApp).onNetworkAvailable()
                    }
                }
            })
        } catch (e: SecurityException) {
            // No network-state access: sync still runs on every screen load.
        }
    }
}
