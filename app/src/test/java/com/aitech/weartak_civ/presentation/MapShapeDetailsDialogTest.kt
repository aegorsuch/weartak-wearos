package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w192dp-h192dp-round", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapShapeDetailsDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun selectedImportedShapeShowsGeometryAndRemovalAction() {
        var removals = 0
        compose.setContent {
            MapShapeDetailsDialog(
                title = "West boundary",
                geometryType = "Polygon",
                imported = true,
                onRemove = { removals++ },
                onBack = {}
            )
        }
        compose.onNodeWithText("West boundary").assertIsDisplayed()
        compose.onNodeWithText("Geometry: Polygon").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, removals) }
    }

    @Test
    fun receivedCotShapeDetailsDoNotOfferLocalDelete() {
        compose.setContent {
            MapShapeDetailsDialog("Tactical area", "Polygon", imported = false, onRemove = {}, onBack = {})
        }
        compose.onNodeWithText("Geometry: Polygon").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertDoesNotExist()
    }
}
