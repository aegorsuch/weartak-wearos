package com.aitech.weartak_civ.integration.plugin

import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginStateStoreTest {

    private lateinit var store: PluginStateStore

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("weartak_plugin_state", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences("plugin_loading", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = PluginStateStore(context)
    }

    @Test
    fun stateDefaultsToEnabledAndVisible() {
        assertTrue(store.isEnabled(PACKAGE_NAME))
        assertFalse(store.isHidden(PACKAGE_NAME))
    }

    @Test
    fun stateSurvivesASecondStoreInstance() {
        store.setEnabled(PACKAGE_NAME, false)
        store.setHidden(PACKAGE_NAME, true)

        val context = RuntimeEnvironment.getApplication()
        val restored = PluginStateStore(context)

        assertFalse(restored.isEnabled(PACKAGE_NAME))
        assertTrue(restored.isHidden(PACKAGE_NAME))
    }

    @Test
    fun removeClearsStateToDefaults() {
        store.setEnabled(PACKAGE_NAME, false)
        store.setHidden(PACKAGE_NAME, true)
        store.remove(PACKAGE_NAME)

        assertTrue(store.isEnabled(PACKAGE_NAME))
        assertFalse(store.isHidden(PACKAGE_NAME))
    }

    @Test
    fun mainDisabledChoiceIsPreservedUntilExplicitlyEnabled() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("plugin_loading", android.content.Context.MODE_PRIVATE)
            .edit().putStringSet("disabled_packages", setOf(PACKAGE_NAME)).commit()

        assertFalse(store.isEnabled(PACKAGE_NAME))
        store.setEnabled(PACKAGE_NAME, true)
        assertTrue(PluginStateStore(context).isEnabled(PACKAGE_NAME))
    }

    private companion object {
        const val PACKAGE_NAME = "com.example.plugin"
    }
}
