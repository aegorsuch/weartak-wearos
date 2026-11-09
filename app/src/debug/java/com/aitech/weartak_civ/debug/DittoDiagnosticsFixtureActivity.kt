package com.aitech.weartak_civ.debug

import android.app.Application
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.BuildConfig
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DittoPdlSource
import com.aitech.weartak_civ.presentation.DittoIdentitySettingsPage
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.service.DittoDirectPeers
import com.aitech.weartak_civ.service.DittoDiagnosticTransports
import com.aitech.weartak_civ.service.DittoLiveStatus
import com.aitech.weartak_civ.service.DittoRecoveryStatus

/** Separate emulator-only package: no repository, SDK instance, authentication or service bootstrap. */
class DittoDiagnosticsFixtureApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        check(BuildConfig.DITTO_UI_DEMO && Build.HARDWARE in setOf("ranchu", "goldfish"))
        WearTakApplication.AppCtx.init(this)
    }
}

class DittoDiagnosticsFixtureActivity : ComponentActivity() {
    internal var fixtureStatus by mutableStateOf(sampleStatus())
    internal var settingsMutations = 0
    internal var fixtureFontScale by mutableStateOf(1f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.DITTO_UI_DEMO && Build.HARDWARE in setOf("ranchu", "goldfish"))
        setContent {
            val density = LocalDensity.current
                WearTAKCIVTheme {
                    Column(Modifier.fillMaxSize()) {
                        // Fixture label sits outside production content and remains visible while scrolling.
                        Text("SYNTHETIC / OFFLINE", color = Color.Yellow, style = MaterialTheme.typography.caption2,
                            modifier = Modifier.padding(top = 18.dp).align(androidx.compose.ui.Alignment.CenterHorizontally))
                        CompositionLocalProvider(LocalDensity provides Density(density.density, fixtureFontScale)) {
                            val nav = rememberNavController()
                            NavHost(nav, startDestination = "fixture") {
                                composable("fixture") {
                                    Chip(onClick = { nav.navigate("ditto_pdl") },
                                        label = { Text("Review Ditto settings") })
                                }
                                composable("ditto_pdl") {
                                    DittoIdentitySettingsPage(nav, DittoPdlSource.COMMUNITY, "", fixtureStatus,
                                        onCommunitySelected = { settingsMutations++ })
                                }
                                composable("ditto_custom_pdl") {
                                    Text("Fixture: no identity writes")
                                }
                            }
                        }
                    }
            }
        }
    }

    internal companion object {
        fun sampleStatus() = DittoLiveStatus(DittoDirectPeers(3, 2, 3, 0, 0), DittoRecoveryStatus.ACTIVE,
            DittoDiagnosticTransports(bluetooth = true, lan = true))
    }
}
