package com.aitech.weartak_civ.presentation

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.test.runner.AndroidJUnitRunner
import com.aitech.weartak_civ.WearTakApplication

/**
 * UI-only fixture: no WearTakApplication bootstrap, sensor services, CEP, or transports.
 * The normal instrumentation runner is unchanged for the rest of the test suite.
 */
class WarningUiTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Warning UI fixture requires an emulator" }
        return super.newApplication(cl, Application::class.java.name, context).also {
            WearTakApplication.AppCtx.init(it)
        }
    }
}
