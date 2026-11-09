package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import com.aitech.weartak_civ.data.takserver.TakServerCaTrust
import com.aitech.weartak_civ.data.takserver.TakServerCaTrustTest
import com.aitech.weartak_civ.data.takserver.TakConnectionFailure
import com.aitech.weartak_civ.data.takserver.TakEnrollmentStage
import com.aitech.weartak_civ.data.takserver.TakFailureCategory
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w192dp-h192dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ServerCaTrustEditorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var selected by mutableStateOf<String?>(null)
    private var address by mutableStateOf("127.0.0.1")
    private val ca = TakServerCaTrustTest.certificate()
    private val staged get() = File(compose.activity.getExternalFilesDir(null), "trust/ca.crt")

    @After fun cleanup() { staged.delete() }

    private fun show(
        valid: Boolean = true,
        settings: Boolean = false,
        failure: TakConnectionFailure? = null,
        importOnOpen: Boolean = false,
        enabled: Boolean = true,
    ) {
        staged.parentFile.mkdirs()
        staged.writeBytes(if (valid) TakServerCaTrustTest.pem(ca) else "invalid".toByteArray())
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (settings) {
                        ServerCaTrustSettings(address, "8089", selected, enabled,
                            failure, importOnOpen, onChange = { selected = it })
                    } else {
                        ServerCaTrustEditor(address, "8089", selected, onChange = { selected = it })
                    }
                }
            }
        }
    }

    private fun review() {
        compose.onNodeWithText("Review staged CA").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Approve this CA"))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun reviewShowsFingerprintAndRequiresExplicitApprovalBeforeSelection() {
        show()
        review()
        assertNull(selected)
        compose.onNodeWithText(TakServerCaTrust.fingerprint(ca)).performScrollTo()
        compose.onNodeWithText("Trust only 127.0.0.1:8089 (enrollment 8446 and APIs 8443)").performScrollTo()
        compose.onNodeWithText("Approve this CA").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(TakServerCaTrust.encode(ca), selected) }
        compose.onNodeWithText("Remove approved CA on save").performScrollTo().performClick()
        compose.runOnIdle { assertNull(selected) }
    }

    @Test fun cancelAndEndpointChangeDiscardReviewWithoutApproving() {
        show()
        review()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        assertNull(selected)
        compose.onNodeWithText("Approve this CA").assertDoesNotExist()
        review()
        compose.runOnIdle { address = "other.example" }
        compose.onNodeWithText("Approve this CA").assertDoesNotExist()
        assertNull(selected)
    }

    @Test fun invalidFileShowsActionableErrorAndCannotBeApproved() {
        show(valid = false)
        compose.onNodeWithText("Review staged CA").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Cannot import CA.", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Approve this CA").assertDoesNotExist()
        assertNull(selected)
    }

    @Test fun defaultFormHidesAllCaDetailsUntilAdvancedIsExpanded() {
        selected = TakServerCaTrust.encode(ca)
        show(settings = true)
        compose.onNodeWithText("Review staged CA").assertDoesNotExist()
        compose.onNodeWithText("CA trust: approved for this server").assertDoesNotExist()
        compose.onNodeWithText("Import CA...").assertDoesNotExist()
        compose.onNodeWithText("Advanced").performClick()
        val advancedBounds = compose.onNodeWithText("Hide advanced").fetchSemanticsNode().boundsInRoot
        val trustBounds = compose.onNodeWithText("CA trust: approved for this server").fetchSemanticsNode().boundsInRoot
        assertTrue("CA details must not overlap Advanced", trustBounds.top >= advancedBounds.bottom)
        compose.onNodeWithText("CA trust: approved for this server").performScrollTo()
        compose.onNodeWithText("Hide advanced").performScrollTo().performClick()
        compose.onNodeWithText("Review staged CA").assertDoesNotExist()
        assertEquals(TakServerCaTrust.encode(ca), selected)
    }

    @Test fun untrustedFailureImportOpensReviewWithoutApprovingAndCanBeRetried() {
        show(settings = true, failure = untrustedFailure)
        compose.onNodeWithText("Review staged CA").assertDoesNotExist()
        compose.onNodeWithText("Import CA...").performScrollTo().performClick()
        awaitApproval()
        compose.onNodeWithText(TakServerCaTrust.fingerprint(ca)).performScrollTo()
        assertNull(selected)
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.onNodeWithText("Import CA...").performScrollTo().performClick()
        awaitApproval()
        compose.onNodeWithText("Approve this CA").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(TakServerCaTrust.encode(ca), selected) }
    }

    @Test fun genericTlsFailureDoesNotOfferImport() {
        show(settings = true, failure = TakConnectionFailure(
            TakEnrollmentStage.CONNECTION, TakFailureCategory.TLS))
        compose.onNodeWithText("Import CA...").assertDoesNotExist()
        compose.onNodeWithText("Review staged CA").assertDoesNotExist()
    }

    @Test fun failureNavigationOpensExistingReviewAfterTrustHasLoaded() {
        show(settings = true, importOnOpen = true)
        awaitApproval()
        assertNull(selected)
        compose.onNodeWithText("Approve this CA").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(TakServerCaTrust.encode(ca), selected) }
    }

    @Test fun recoveryReviewComposesBeforeLongFormAndDoesNotReopenAfterScrolling() {
        staged.parentFile.mkdirs()
        staged.writeBytes(TakServerCaTrustTest.pem(ca))
        compose.setContent {
            MaterialTheme {
                LazyColumn(Modifier.testTag("recoveryForm")) {
                    item(key = "trust") {
                        ServerCaTrustSettings(address, "8089", selected, importOnOpen = true,
                            onChange = { selected = it })
                    }
                    items(20) { Spacer(Modifier.height(100.dp)) }
                }
            }
        }
        awaitApproval()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.onNodeWithTag("recoveryForm").performScrollToIndex(20)
        compose.onNodeWithText("Review staged CA").assertDoesNotExist()
        compose.onNodeWithTag("recoveryForm").performScrollToIndex(0)
        compose.onNodeWithText("Review staged CA").assertExists()
        compose.onNodeWithText("Approve this CA").assertDoesNotExist()
        assertNull(selected)
    }

    @Test fun managedFormCannotStartFailureImport() {
        show(settings = true, failure = untrustedFailure, enabled = false)
        compose.onNodeWithText("Import CA...").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Approve this CA").assertDoesNotExist()
        assertNull(selected)
    }

    @Test fun registrationFailureDialogImportActionReturnsToServerWithoutApproving() {
        var imports = 0
        val visible = mutableStateOf(true)
        val loading = mutableStateOf(false)
        val networkConnected = mutableStateOf(true)
        val serverConnected = mutableStateOf(false)
        val detailsChanged = mutableStateOf(true)
        val duplicate = mutableStateOf(false)
        val navigation = io.mockk.mockk<androidx.navigation.NavController>(relaxed = true)
        compose.setContent {
            MaterialTheme {
                WearTAKServerDialog(
                    shouldShowDialog = visible,
                    shouldShowLoadingDialog = loading,
                    isNetworkConnected = networkConnected,
                    navController = navigation,
                    isServerConnected = serverConnected,
                    serverDetailsChanged = detailsChanged,
                    isDuplicate = duplicate,
                    failure = untrustedFailure,
                    onImportCa = { imports++; visible.value = false },
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithText("Import CA...").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, imports)
            assertFalse(visible.value)
            assertNull(selected)
        }
    }

    private fun awaitApproval() {
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Approve this CA"))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private val untrustedFailure = TakConnectionFailure(
        TakEnrollmentStage.CONFIG, TakFailureCategory.TLS, untrustedServerCa = true)
}
