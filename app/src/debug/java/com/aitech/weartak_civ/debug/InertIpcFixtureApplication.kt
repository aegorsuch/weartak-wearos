package com.aitech.weartak_civ.debug

import android.app.Application
import android.os.Build
import com.aitech.weartak_civ.BuildConfig
import com.aitech.weartak_civ.WearTakApplication

class InertIpcFixtureApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        check(BuildConfig.DITTO_UI_DEMO && Build.HARDWARE in setOf("ranchu", "goldfish"))
        WearTakApplication.AppCtx.init(this)
    }
}
