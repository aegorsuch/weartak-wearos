package com.aitech.weartak_civ.presentation

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.wear.compose.material.Text
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w227dp-h227dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayManagerCategoryPreferencesTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences(
        OverlayManagerCategoryPreferences.FILE_NAME, Context.MODE_PRIVATE)
    private val categories = listOf(
        "overlay-manager-layers", "overlay-manager-maps",
        "overlay-manager-roles", "overlay-manager-teams", "map-credits-disclosure"
    )

    @Before fun resetPreferences() {
        assertTrue(preferences.edit().clear().commit())
    }

    @Test fun unsavedCategoriesUseCallerDefaultsWithoutWritingPreferences() {
        val store = OverlayManagerCategoryPreferences(preferences)
        categories.forEach { id ->
            assertTrue(store.expanded(id, true))
            assertFalse(store.expanded(id, false))
        }
        assertTrue(preferences.all.isEmpty())
    }

    @Test fun everyCategoryAndNestedDynamicGroupHaveIndependentChoices() {
        val store = OverlayManagerCategoryPreferences(preferences)
        val ids = categories + listOf("features:routes", "features:points")
        ids.forEachIndexed { index, id -> store.saveExpanded(id, index % 2 == 0) }
        ids.reversed().forEach { id ->
            val expected = ids.indexOf(id) % 2 == 0
            assertEquals(expected, store.expanded(id, !expected))
        }
        store.saveExpanded("features:routes", false)
        assertFalse(store.expanded("features:routes", true))
        assertTrue(store.expanded("features:points", false))
        assertTrue(store.expanded("overlay-manager-layers", false))
    }

    @Test fun choicesSurviveFreshDiskBackedPreferencesWithoutChangingMapSettings() {
        val file = File(temporaryFolder.root, "category-preferences.xml")
        val original = diskPreferences(file)
        assertTrue(original.edit()
            .putStringSet("hidden_layers", setOf("contours"))
            .putStringSet("visible_user_roles_standard", setOf("Medic"))
            .putStringSet("visible_user_teams", setOf("White"))
            .putBoolean("show_markers", false)
            .putString("map_source", "bing_hybrid")
            .commit())
        val before = original.all
        val store = OverlayManagerCategoryPreferences(original)
        categories.forEachIndexed { index, id -> store.saveExpanded(id, index % 2 == 0) }
        // Drain apply() writes before discarding the backing instance, as Android does on lifecycle exit.
        assertTrue(original.edit().commit())
        assertTrue(file.isFile)

        val reopened = diskPreferences(file)
        assertNotSame(original, reopened)
        val restored = OverlayManagerCategoryPreferences(reopened)
        categories.forEachIndexed { index, id ->
            assertEquals(index % 2 == 0, restored.expanded(id, index % 2 != 0))
        }
        assertEquals(before, reopened.all.filterKeys { !it.startsWith("category_expanded_") })
    }

    @Test fun uiRestoresAllCategoriesAfterClosingAndSavedStateRecreation() {
        val open = mutableStateOf(true)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            if (open.value) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    categories.forEach { id ->
                        OverlayManagerCategory(
                            label = id, tag = id, initiallyExpanded = id != "map-credits-disclosure"
                        ) { Text("$id child") }
                    }
                }
            }
        }
        categories.forEach { id ->
            assertExpanded(id, id != "map-credits-disclosure")
            compose.onNodeWithTag(id).performClick()
            assertExpanded(id, id == "map-credits-disclosure")
        }
        compose.runOnIdle { open.value = false }
        categories.forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        compose.runOnIdle { open.value = true }
        categories.forEach { assertExpanded(it, it == "map-credits-disclosure") }
        restoration.emulateSavedInstanceStateRestore()
        categories.forEach { assertExpanded(it, it == "map-credits-disclosure") }
    }

    @Test fun labelsCountsReorderAndTemporaryAbsenceDoNotResetOrTransferChoices() {
        val groups = mutableStateOf(listOf("dynamic:one", "dynamic:two"))
        val label = mutableStateOf("Maps (1)")
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                groups.value.forEach { id ->
                    OverlayManagerCategory("${label.value} $id", id) { Text("$id child") }
                }
            }
        }
        compose.onNodeWithTag("dynamic:one").performClick()
        compose.runOnIdle {
            label.value = "Karten (27)"
            groups.value = groups.value.reversed()
        }
        assertExpanded("dynamic:one", false)
        assertExpanded("dynamic:two", true)
        compose.onNodeWithText("dynamic:one child").assertDoesNotExist()
        compose.onNodeWithText("dynamic:two child").assertExists()
        compose.runOnIdle { groups.value = listOf("dynamic:two") }
        compose.onNodeWithTag("dynamic:one").assertDoesNotExist()
        compose.runOnIdle { groups.value = listOf("dynamic:one", "dynamic:two") }
        assertExpanded("dynamic:one", false)
        assertExpanded("dynamic:two", true)
    }

    private fun assertExpanded(id: String, expanded: Boolean) {
        compose.onNodeWithTag(id).performScrollTo().assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription, if (expanded) "Expanded" else "Collapsed"))
        compose.onNodeWithTag("$id-arrow-${if (expanded) "down" else "right"}",
            useUnmergedTree = true).assertExists()
    }

    private fun diskPreferences(file: File): SharedPreferences {
        // Bypass Context's process cache to model a new process reading the same preference XML.
        val constructor = Class.forName("android.app.SharedPreferencesImpl")
            .getDeclaredConstructor(File::class.java, Int::class.javaPrimitiveType)
        constructor.isAccessible = true
        return constructor.newInstance(file, Context.MODE_PRIVATE) as SharedPreferences
    }
}
