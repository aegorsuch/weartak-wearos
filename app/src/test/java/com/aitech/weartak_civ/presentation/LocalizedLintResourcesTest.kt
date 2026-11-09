package com.aitech.weartak_civ.presentation

import android.app.Application
import android.content.res.Configuration
import com.aitech.weartak_civ.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LocalizedLintResourcesTest {
    @Test fun allSupportedLocalesResolveTheirNewTranslations() {
        val labels = mapOf(
            "ar" to "أثناء الشحن",
            "de" to "Beim Laden",
            "es" to "Durante la carga",
            "fr" to "Pendant la charge",
            "ja" to "充電中",
            "ko" to "충전 중",
            "tr" to "Şarj olurken",
            "vi" to "Khi đang sạc",
            "zh" to "充电时"
        )
        val app = RuntimeEnvironment.getApplication()
        labels.forEach { (language, expected) ->
            val configuration = Configuration(app.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            }
            val resources = app.createConfigurationContext(configuration).resources
            assertEquals(language, expected, resources.getString(R.string.debug_while_charging))
            listOf(0, 1, 2, 3, 11, 101, 1_000_000).forEach { quantity ->
                val target = resources.getQuantityString(R.plurals.map_target_count, quantity, quantity)
                val services = resources.getQuantityString(R.plurals.notification_services_running, quantity, quantity)
                val servers = resources.getQuantityString(R.plurals.network_server_count, quantity, quantity, quantity)
                listOf(target, services, servers).forEach { text ->
                    assertFalse("$language/$quantity: $text", text.isBlank())
                    assertFalse("$language/$quantity: $text", Regex("%(?:\\d+\\$)?[ds]").containsMatchIn(text))
                }
            }
        }
    }
}
