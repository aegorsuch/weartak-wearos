package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.weartak.plugin.sdk.HostSettingsContract
import com.weartak.plugin.sdk.model.MainUiCardParcel
import com.weartak.plugin.sdk.model.PluginMainUiSchemaParcel
import com.weartak.plugin.sdk.model.PluginCapabilitiesParcel
import com.weartak.plugin.sdk.model.SettingsFieldParcel
import com.weartak.plugin.sdk.model.SettingsFieldTypes
import com.weartak.plugin.sdk.model.SettingsSchemaParcel
import com.weartak.plugin.sdk.validation.SettingsSchemaContractValidator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PluginSettingsCoordinatorTest {

    private lateinit var app: WearTakApplication
    private lateinit var coordinator: PluginSettingsCoordinator
    private lateinit var fakePlugin: FakeWearTakPlugin
    private val networkLocked = MutableStateFlow(false)

    @Before
    fun setUp() {
        PluginDiagnostics.resetForTest()
        app = mockk(relaxed = true)
        val repo = mockk<DataRepository>(relaxed = true)
        every { app.repo } returns repo
        every { repo.getCallsign } returns flowOf("TEST")
        every { repo.networkSettingsLocked } returns networkLocked
        every { repo.getTeam } returns flowOf("Cyan")
        every { repo.getRole } returns flowOf("Member")
        every { repo.reportingUpdateIntervalSecs } returns MutableStateFlow(60)
        every { repo.sitxStatus } returns MutableStateFlow(DataRepository.SitxServerStatus.DISCONNECTED)
        coordinator = PluginSettingsCoordinator(
            app,
            HostSettingsSnapshotBuilder(app),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        fakePlugin = FakeWearTakPlugin("com.plugin.settings")
        fakePlugin.pluginCapabilities = PluginCapabilitiesParcel(hostedSettingsUi = true)
        val schema = when (
            val v = SettingsSchemaContractValidator.validate(
                SettingsSchemaParcel(
                    fields = listOf(
                        SettingsFieldParcel(
                            fieldId = "callsign_mirror",
                            label = "Callsign",
                            fieldType = SettingsFieldTypes.STRING,
                            hostSettingKey = HostSettingsContract.KEY_CALLSIGN,
                            readOnly = true,
                        ),
                        SettingsFieldParcel(
                            fieldId = "enable_stream",
                            label = "Stream",
                            fieldType = SettingsFieldTypes.TOGGLE,
                        ),
                    ),
                ),
            )
        ) {
            is com.weartak.plugin.sdk.validation.ContractValidationResult.Ok -> v.value
            else -> SettingsSchemaParcel()
        }
        fakePlugin.setSettingsSchemaForTest(schema)
    }

    @Test
    fun hostedMainUiDispatchesAnyPluginDefinedEnabledActionOnExplicitRequest() {
        val pluginId = "org.example.telemetry"
        fakePlugin.pluginCapabilities = PluginCapabilitiesParcel(hostedMainUi = true)
        fakePlugin.setMainUiSchemaForTest(
            PluginMainUiSchemaParcel(
                cards = listOf(
                    MainUiCardParcel(
                        cardType = "ACTION",
                        cardId = "sync",
                        title = "Synchronize",
                        content = hostedActionContent(
                            actionId = "telemetry.sync",
                            actionLabel = "Synchronize",
                            args = mapOf("batchId" to "batch-17"),
                        ),
                    ),
                ),
            ),
        )
        coordinator.registerPlugin(pluginId, fakePlugin)

        assertTrue(coordinator.supportsHostedMainUi(pluginId))
        assertTrue(coordinator.getHostedMainUi(pluginId) is HostedPluginMainUiResult.Ready)
        assertTrue(fakePlugin.mainUiActions.isEmpty())
        assertTrue(
            coordinator.invokeHostedMainUiAction(pluginId, "sync") is HostedPluginMainUiResult.Ready
        )
        assertEquals("telemetry.sync", fakePlugin.mainUiActions.single().first)
        assertEquals("batch-17", fakePlugin.mainUiActions.single().second!!.getString("batchId"))

        fakePlugin.setMainUiSchemaForTest(
            PluginMainUiSchemaParcel(
                cards = listOf(
                    MainUiCardParcel(
                        cardType = "ACTION",
                        cardId = "sync",
                        title = "Synchronize",
                        content = hostedActionContent(
                            actionId = "telemetry.sync",
                            actionLabel = "Synchronize",
                            enabled = false,
                        ),
                    ),
                ),
            ),
        )
        assertTrue(
            coordinator.invokeHostedMainUiAction(pluginId, "sync") is HostedPluginMainUiResult.Failed
        )
        assertEquals(1, fakePlugin.mainUiActions.size)
        fakePlugin.pluginCapabilities = PluginCapabilitiesParcel()
        assertFalse(coordinator.supportsHostedMainUi(pluginId))
    }

    @Test
    fun inboundSubscriptionIsIndependentOfOutboundAndDisabledPluginsReceiveNothing() {
        val inbound = PluginCotIngress.SETTING_RECEIVE_INBOUND_COT
        fakePlugin.setSettingsSchemaForTest(SettingsSchemaParcel(fields = listOf(
            SettingsFieldParcel(fieldId = inbound, label = "Receive CoT", fieldType = SettingsFieldTypes.TOGGLE)
        )))
        coordinator.onPluginConnected("com.plugin.settings", fakePlugin)
        val args = Bundle().apply { putString("xml", "<event/>") }
        coordinator.invokeActionOnPluginsWithSetting(inbound, "cot.inbound", args)
        assertTrue(fakePlugin.mainUiActions.isEmpty())
        assertTrue(coordinator.applyPluginSettings(
            "com.plugin.settings", Bundle().apply { putBoolean(inbound, true) }
        ).success)
        coordinator.invokeActionOnPluginsWithSetting(inbound, "cot.inbound", args)
        assertEquals("cot.inbound", fakePlugin.mainUiActions.single().first)
        assertEquals("<event/>", fakePlugin.mainUiActions.single().second?.getString("xml"))
        assertFalse(coordinator.outboundCotAvailable.value)
        coordinator.invokeActionOnPluginsWithSetting(
            PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT, "cot.outbound", args
        )
        assertEquals(1, fakePlugin.mainUiActions.size)
    }

    @Test
    fun localMarkerRemovalTargetsOnlyItsOriginatingPlugin() {
        coordinator.onPluginConnected("com.plugin.settings", fakePlugin)
        val otherPlugin = FakeWearTakPlugin("com.plugin.other")
        coordinator.onPluginConnected("com.plugin.other", otherPlugin)
        val marker = DataRepository.ReceivedMarker(
            uid = "edge-scout.aabb",
            markerType = "a-u-A-M-F-Q",
            title = "DRONE",
            timeReceived = 2_000L,
            staleTime = 62_000L,
            location = null,
            source = "plugin:com.plugin.settings",
            sourceServerId = "plugin:com.plugin.settings",
            receivedAt = 2_100L,
        )

        coordinator.notifyLocalMarkerRemoved(marker, removedAt = 2_200L)

        val (action, args) = fakePlugin.mainUiActions.single()
        assertEquals(PluginSettingsCoordinator.ACTION_LOCAL_MARKER_REMOVED, action)
        assertEquals(marker.uid, args?.getString(PluginSettingsCoordinator.KEY_MARKER_UID))
        assertEquals(marker.timeReceived, args?.getLong(PluginSettingsCoordinator.KEY_EVENT_TIME))
        assertEquals(marker.receivedAt, args?.getLong(PluginSettingsCoordinator.KEY_RECEIVED_AT))
        assertEquals(2_200L, args?.getLong(PluginSettingsCoordinator.KEY_REMOVED_AT))
        assertTrue(otherPlugin.mainUiActions.isEmpty())
    }

    @Test
    fun localMarkerRemovalIgnoresNonPluginSources() {
        coordinator.onPluginConnected("com.plugin.settings", fakePlugin)
        coordinator.notifyLocalMarkerRemoved(
            DataRepository.ReceivedMarker(
                uid = "remote-marker",
                markerType = "a-u-G",
                title = "REMOTE",
                timeReceived = 2_000L,
                staleTime = 62_000L,
                location = null,
                source = "tak:server",
                sourceServerId = "tak:server",
                receivedAt = 2_100L,
            ),
            removedAt = 2_200L,
        )

        assertTrue(fakePlugin.mainUiActions.isEmpty())
    }

    @Test
    fun connectivityIncludesDisabledFunctionalPluginsAndRechecksLocksAndBinding() {
        val id = "com.plugin.settings"
        val field = PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT
        coordinator.onPluginConnected(id, fakePlugin)
        assertTrue(coordinator.connectivityToggles.value.isEmpty())
        fakePlugin.setSettingsSchemaForTest(SettingsSchemaParcel(fields = listOf(
            SettingsFieldParcel(fieldId = field, label = "Forward CoT", fieldType = SettingsFieldTypes.TOGGLE)
        )))
        coordinator.onPluginSettingsSchemaChanged(id)
        val toggle = coordinator.connectivityToggles.value.getValue(id)
        assertFalse(toggle.enabled)
        networkLocked.value = true
        assertFalse(coordinator.setConnectivityEnabled(id, toggle, true).success)
        assertFalse(coordinator.outboundCotAvailable.value)
        networkLocked.value = false
        assertTrue(coordinator.setConnectivityEnabled(id, toggle, true).success)
        assertTrue(coordinator.connectivityToggles.value.getValue(id).enabled)
        coordinator.unregisterPlugin(id)
        assertTrue(coordinator.connectivityToggles.value.isEmpty())
        coordinator.onPluginConnected(id, FakeWearTakPlugin(id))
        assertFalse(coordinator.setConnectivityEnabled(id, toggle, true).success)
    }

    @Test
    fun outboundCotReadinessTracksOptInAndDisconnect() {
        val field = PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT
        fakePlugin.setSettingsSchemaForTest(SettingsSchemaParcel(fields = listOf(
            SettingsFieldParcel(fieldId = field, label = "Forward CoT", fieldType = SettingsFieldTypes.TOGGLE)
        )))
        coordinator.onPluginConnected("com.plugin.settings", fakePlugin)
        assertFalse(coordinator.outboundCotAvailable.value)
        assertTrue(coordinator.applyPluginSettings(
            "com.plugin.settings", Bundle().apply { putBoolean(field, true) }
        ).success)
        assertTrue(coordinator.anyPluginHasSettingEnabled(field))
        assertTrue(coordinator.outboundCotAvailable.value)
        coordinator.invokeActionOnPluginsWithSetting(
            field, "cot.outbound", Bundle().apply { putString("xml", "<event/>") }
        )
        assertEquals("cot.outbound", fakePlugin.mainUiActions.single().first)
        coordinator.unregisterPlugin("com.plugin.settings")
        assertFalse(coordinator.anyPluginHasSettingEnabled(field))
        assertFalse(coordinator.outboundCotAvailable.value)
    }

    @Test
    fun outboundReadinessVersionsReconnectsAndSecondPluginsWithoutUnchangedRepeats() {
        val field = PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT
        val schema = SettingsSchemaParcel(fields = listOf(
            SettingsFieldParcel(fieldId = field, label = "Forward CoT", fieldType = SettingsFieldTypes.TOGGLE)
        ))
        fakePlugin.setSettingsSchemaForTest(schema)
        coordinator.onPluginConnected("com.plugin.settings", fakePlugin)
        assertTrue(coordinator.outboundCotReadyRoutes.value.isEmpty())
        val on = Bundle().apply { putBoolean(field, true) }
        assertTrue(coordinator.applyPluginSettings("com.plugin.settings", on).success)
        val first = coordinator.outboundCotReadyRoutes.value
        assertEquals(setOf("com.plugin.settings"), first.keys)
        coordinator.applyPluginSettings("com.plugin.settings", on)
        assertEquals(first, coordinator.outboundCotReadyRoutes.value)
        val other = FakeWearTakPlugin("com.plugin.other")
        other.pluginCapabilities = PluginCapabilitiesParcel(hostedSettingsUi = true)
        other.setSettingsSchemaForTest(schema)
        coordinator.onPluginConnected("com.plugin.other", other)
        assertTrue(coordinator.applyPluginSettings("com.plugin.other", on).success)
        assertEquals(first["com.plugin.settings"], coordinator.outboundCotReadyRoutes.value["com.plugin.settings"])
        assertEquals(2, coordinator.outboundCotReadyRoutes.value.size)
        coordinator.unregisterPlugin("com.plugin.settings")
        assertFalse(coordinator.outboundCotReadyRoutes.value.containsKey("com.plugin.settings"))
        coordinator.onPluginConnected("com.plugin.settings", fakePlugin)
        coordinator.applyPluginSettings("com.plugin.settings", on)
        assertTrue(coordinator.outboundCotReadyRoutes.value.getValue("com.plugin.settings") >
            first.getValue("com.plugin.settings"))
    }

    @Test
    fun onPluginConnected_cachesSchemaAndPushesHostSettings() = runBlocking {
        coordinator.onPluginConnected("com.plugin.settings", fakePlugin)

        val state = coordinator.getState("com.plugin.settings")
        assertNotNull(state)
        assertTrue(state!!.settingsSupported)
        assertTrue(state.hostedSettingsUi)
        assertEquals(2, state.schema.fields.size)
        assertTrue(fakePlugin.hostSettingsReceived.isNotEmpty())
        assertTrue(state.hostSettingsRevision > 0L)
    }

    @Test
    fun onPluginSettingsValuesChanged_refreshesCache() = runBlocking {
        coordinator.onPluginConnected("com.plugin.settings", fakePlugin)
        fakePlugin.applySettings(Bundle().apply { putString("callsign_mirror", "ignored") })

        coordinator.onPluginSettingsValuesChanged("com.plugin.settings")

        val state = coordinator.getState("com.plugin.settings")
        assertNotNull(state)
    }

    @Test
    fun applyPluginSettings_validatesLocally() = runBlocking {
        coordinator.onPluginConnected("com.plugin.settings", fakePlugin)
        val result = coordinator.applyPluginSettings(
            "com.plugin.settings",
            Bundle().apply { putBoolean("enable_stream", true) },
        )
        assertTrue(result.success)
    }
}
