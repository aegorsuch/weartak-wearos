package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.data.CrmInferenceState
import com.aitech.weartak_civ.data.CrmInferenceStatus
import com.aitech.weartak_civ.data.CrmThresholdRanges
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CrmWarningLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun warningExpiresWithoutAnotherInferenceAndRejectedGoodOrDisabledCannotStayLive() {
        var now = 1_000L
        val inference = mutableStateOf(CrmInferenceState(CrmInferenceStatus.ACCEPTED, .5f,
            updatedAtMillis = now))
        val enabled = mutableStateOf(true)
        compose.setContent {
            val warning = rememberCrmHomeWarning(inference.value, CrmThresholdRanges(), enabled.value, true) { now }
            Text(warning?.status?.name ?: "none")
        }
        compose.onNodeWithText("MODERATE").assertExists()
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithText("none").assertExists()
        compose.runOnIdle { enabled.value = true; inference.value = inference.value.copy(status = CrmInferenceStatus.REJECTED) }
        compose.onNodeWithText("none").assertExists()
        compose.runOnIdle { inference.value = inference.value.copy(status = CrmInferenceStatus.ACCEPTED, value = .9f) }
        compose.onNodeWithText("none").assertExists()
        compose.runOnIdle { now++; inference.value = inference.value.copy(value = .5f, updatedAtMillis = now) }
        compose.onNodeWithText("MODERATE").assertExists()
        compose.runOnIdle { now += 30_001L }
        compose.mainClock.advanceTimeBy(30_001)
        compose.onNodeWithText("none").assertExists()
    }

    @Test fun returningFromSleepReevaluatesWallClockBeforeShowingOldAcceptedScore() {
        var now = 1_000L
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val inference = CrmInferenceState(CrmInferenceStatus.ACCEPTED, .25f,
            updatedAtMillis = now)
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val warning = rememberCrmHomeWarning(inference, CrmThresholdRanges(), true, true) { now }
                Text(warning?.status?.name ?: "none")
            }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithText("POOR").assertExists()
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            now += 60_000L
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        assertTrue(now - inference.updatedAtMillis > 30_000)
        compose.onNodeWithText("none").assertExists()
    }
}
