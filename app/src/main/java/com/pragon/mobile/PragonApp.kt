package com.pragon.mobile

import android.app.Application

/** Runs before anything else in every Pragon process: gives the network shield access to the settings. */
class PragonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Guard.app = applicationContext
        // If the firewall was on before a restart or an app update, put it back.
        try { Firewall.restore(this) } catch (e: Exception) { }
    }
}
