package com.aitech.weartak_civ.presentation

import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.aitech.weartak_civ.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AlertSmsLabelTest {
    @Test fun wordingIsConsistentAcrossExistingLocaleResources() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        listOf("en", "ar", "de", "es", "fr", "ja", "ko", "tr", "vi", "zh").forEach { language ->
            val config = Configuration(app.resources.configuration).apply { setLocale(Locale(language)) }
            assertEquals("Send Alerts over SMS",
                app.createConfigurationContext(config).getString(R.string.send_sms_over_alerts))
        }
    }
}
