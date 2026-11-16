package com.aitech.weartak_civ

import android.app.Application
import android.content.Context
import com.aitech.weartak_civ.data.secure.openvpn.OpenVpnProfileRepository

class OpenVpnPluginApplication : Application() {
    lateinit var openVpnProfiles: OpenVpnProfileRepository
        private set

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        openVpnProfiles = OpenVpnProfileRepository(applicationContext)
    }

    companion object {
        internal lateinit var appContext: Context
            private set
    }
}
