package com.aitech.weartak_civ.presentation

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

internal fun registerComposeTestActivity(): TestRule = TestRule { base, _ ->
    object : Statement() {
        override fun evaluate() {
            val app = RuntimeEnvironment.getApplication()
            val component = ComponentName(app, ComponentActivity::class.java)
            val packages = shadowOf(app.packageManager)
            val activity = packages.addActivityIfNotPresent(component)
            activity.theme = android.R.style.Theme_Material_Light_NoActionBar
            packages.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            })
            base.evaluate()
        }
    }
}
