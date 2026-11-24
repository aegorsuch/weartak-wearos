package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import android.os.Process
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cot.TakServerCotIngestResult
import com.aitech.weartak_civ.data.CrmThresholdRanges
import com.aitech.weartak_civ.data.DataRepository
import com.weartak.plugin.sdk.HostSettingsContract
import com.weartak.plugin.sdk.model.MainUiCardParcel
import com.weartak.plugin.sdk.model.PluginMainUiSchemaParcel
import com.weartak.plugin.sdk.model.PluginCapabilitiesParcel
import com.weartak.plugin.sdk.model.SettingsFieldParcel
import com.weartak.plugin.sdk.model.SettingsFieldTypes
import com.weartak.plugin.sdk.model.SettingsSchemaParcel
import com.weartak.plugin.sdk.model.SettingsApplyResultParcel
import com.weartak.plugin.sdk.transport.TransportContract
import com.weartak.plugin.sdk.transport.TransportPayloadKind
import com.weartak.plugin.sdk.transport.TransportResultCode
import com.weartak.plugin.sdk.transport.aidl.IPluginTransportCallbackV1
import com.weartak.plugin.sdk.transport.model.TransportCapabilitiesParcel
import com.weartak.plugin.sdk.transport.model.TransportResultParcel
import com.weartak.plugin.sdk.transport.model.TransportSendRequestParcel
import com.weartak.plugin.sdk.validation.SettingsSchemaContractValidator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
    private val gunshotEnabled = MutableStateFlow(false)
    private val gunshotThreshold = MutableStateFlow(90)
    private val uasEnabled = MutableStateFlow(false)
    private val uasThreshold = MutableStateFlow(90)

    @Before
    fun setUp() {
        PluginDiagnostics.resetForTest()
        app = mockk(relaxed = true)
        val repo = mockk<DataRepository>(relaxed = true)
        every { app.repo } returns repo
        every { repo.getCallsign } returns flowOf("TEST")
        every { repo.getEnableOnnxGunshotService } returns gunshotEnabled
        every { repo.getOnnxGunshotTriggerThresholdPercent } returns gunshotThreshold
        every { repo.getEnableOnnxUasService } returns uasEnabled
        every { repo.getOnnxUasTriggerThresholdPercent } returns uasThreshold
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

    private fun crmPlugin(marker: Boolean): FakeWearTakPlugin =
        FakeWearTakPlugin("com.weartak.plugin.crm").apply {
            setSettingsSchemaForTest(
                SettingsSchemaParcel(fields = listOf(
                    SettingsFieldParcel(fieldId = "enable_crm", label = "Enabled", fieldType = SettingsFieldTypes.TOGGLE,
                        defaultValueLiteral = "false"),
                    SettingsFieldParcel(fieldId = "crm_poor_max", label = "Poor", fieldType = SettingsFieldTypes.NUMBER,
                        defaultValueLiteral = "33"),
                    SettingsFieldParcel(fieldId = "crm_moderate_max", label = "Moderate", fieldType = SettingsFieldTypes.NUMBER,
                        defaultValueLiteral = "66"),
                    SettingsFieldParcel(fieldId = "crm_legacy_migration_complete", label = "Migration", fieldType = SettingsFieldTypes.TOGGLE,
                        defaultValueLiteral = "false"),
                ))
            )
            applySettings(Bundle().apply { putBoolean("crm_legacy_migration_complete", marker) })
        }

    @Test
    fun crmMigrationTransfersLegacySettingsOnlyOnce() {
        every { app.repo.crmLegacyMigrationSettings } returns flowOf(true to CrmThresholdRanges(20, 70))
        val plugin = crmPlugin(false)

        coordinator.onPluginConnected("com.weartak.plugin.crm", plugin)
        val migrated = plugin.getSettingsValues()
        assertTrue(migrated.getBoolean("crm_legacy_migration_complete"))
        assertTrue(migrated.getBoolean("enable_crm"))
        assertEquals(20.0, migrated.getDouble("crm_poor_max"), 0.0)
        assertEquals(70.0, migrated.getDouble("crm_moderate_max"), 0.0)

        plugin.applySettings(Bundle().apply { putDouble("crm_poor_max", 25.0) })
        coordinator.onPluginConnected("com.weartak.plugin.crm", plugin)
        assertEquals(25.0, plugin.getSettingsValues().getDouble("crm_poor_max"), 0.0)
    }

    @Test
    fun crmMigrationPreservesConcurrentPluginEdit() {
        every { app.repo.crmLegacyMigrationSettings } returns flowOf(true to CrmThresholdRanges(20, 70))
        val configured = crmPlugin(false)
        val plugin = object : FakeWearTakPlugin("com.weartak.plugin.crm") {
            init {
                setSettingsSchemaForTest(configured.getSettingsSchema())
                applySettings(configured.getSettingsValues())
            }

            override fun applySettingsWithResult(values: Bundle?, applyMode: Int): SettingsApplyResultParcel {
                applySettings(Bundle().apply {
                    putDouble("crm_poor_max", 25.0)
                    putBoolean("crm_legacy_migration_complete", true)
                })
                return SettingsApplyResultParcel(success = false)
            }
        }

        coordinator.onPluginConnected("com.weartak.plugin.crm", plugin)
        assertEquals(25.0, plugin.getSettingsValues().getDouble("crm_poor_max"), 0.0)
        assertTrue(plugin.getSettingsValues().getBoolean("crm_legacy_migration_complete"))
    }

    @Test
    fun crmMigrationFailsClosedWhenMarkerMissing() {
        val plugin = FakeWearTakPlugin("com.weartak.plugin.crm")
        val failure = runCatching { coordinator.onPluginConnected("com.weartak.plugin.crm", plugin) }
        assertTrue(failure.isFailure)
    }

    @Test
    fun crmMigrationRejectsIncompleteSchemaEvenAfterMigration() {
        val plugin = crmPlugin(true).apply {
            setSettingsSchemaForTest(SettingsSchemaParcel(fields = listOf(
                SettingsFieldParcel(
                    fieldId = "crm_legacy_migration_complete",
                    label = "Migration",
                    fieldType = SettingsFieldTypes.TOGGLE,
                    defaultValueLiteral = "false",
                ),
            )))
        }
        val failure = runCatching { coordinator.onPluginConnected("com.weartak.plugin.crm", plugin) }
        assertTrue(failure.isFailure)
    }

    @Test
    fun crmToggleChangesPluginSettingWithoutUnloadingOrChangingThresholds() {
        val plugin = crmPlugin(true).apply {
            applySettings(Bundle().apply {
                putBoolean("enable_crm", false)
                putDouble("crm_poor_max", 33.0)
                putDouble("crm_moderate_max", 66.0)
            })
        }
        coordinator.onPluginConnected(CRM_PLUGIN_PACKAGE, plugin)
        assertTrue(coordinator.setCrmEnabled(true).success)
        assertTrue(plugin.getSettingsValues().getBoolean("enable_crm"))
        assertTrue(coordinator.crmSettings.value!!.enabled)
        assertTrue(coordinator.setCrmEnabled(false).success)
        assertFalse(plugin.getSettingsValues().getBoolean("enable_crm"))
        assertEquals(33.0, plugin.getSettingsValues().getDouble("crm_poor_max"), 0.0)
        assertEquals(66.0, plugin.getSettingsValues().getDouble("crm_moderate_max"), 0.0)
        assertTrue(plugin.getSettingsValues().getBoolean("crm_legacy_migration_complete"))
        assertEquals(0, plugin.stopCalls)
        coordinator.unregisterPlugin(CRM_PLUGIN_PACKAGE)
        assertFalse(coordinator.setCrmEnabled(true).success)
    }

    @Test
    fun crmThresholdActionOpensWhileCrmIsDisabled() {
        val plugin = crmPlugin(true).apply {
            pluginCapabilities = PluginCapabilitiesParcel(hostedSettingsUi = true, hostedMainUi = true)
            applySettings(Bundle().apply {
                putBoolean("enable_crm", false)
                putDouble("crm_poor_max", 33.0)
                putDouble("crm_moderate_max", 66.0)
            })
            setMainUiSchemaForTest(PluginMainUiSchemaParcel(cards = listOf(
                MainUiCardParcel(
                    cardType = "ACTION",
                    cardId = "crm-thresholds",
                    title = "CRM Thresholds",
                    content = hostedActionContent("crm.thresholds", "Edit thresholds"),
                ),
            )))
        }
        coordinator.onPluginConnected(CRM_PLUGIN_PACKAGE, plugin)
        assertFalse(plugin.getSettingsValues().getBoolean("enable_crm"))
        assertTrue(coordinator.invokeHostedMainUiAction(CRM_PLUGIN_PACKAGE, "crm-thresholds")
            is HostedPluginMainUiResult.Ready)
        assertEquals("crm.thresholds", plugin.mainUiActions.single().first)
    }

    @Test
    fun crmToggleAcceptsSettingsChangedCallbackDuringApply() {
        val configured = crmPlugin(true).apply {
            applySettings(Bundle().apply {
                putBoolean("enable_crm", false)
                putDouble("crm_poor_max", 33.0)
                putDouble("crm_moderate_max", 66.0)
            })
        }
        val plugin = object : FakeWearTakPlugin(CRM_PLUGIN_PACKAGE) {
            init {
                setSettingsSchemaForTest(configured.getSettingsSchema())
                applySettings(configured.getSettingsValues())
            }

            override fun applySettingsWithResult(values: Bundle?, applyMode: Int): SettingsApplyResultParcel {
                val result = super.applySettingsWithResult(values, applyMode)
                coordinator.onPluginSettingsValuesChanged(CRM_PLUGIN_PACKAGE)
                return result
            }
        }
        coordinator.onPluginConnected(CRM_PLUGIN_PACKAGE, plugin)
        assertTrue(coordinator.setCrmEnabled(true).success)
        assertTrue(coordinator.crmSettings.value!!.enabled)
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
    fun takCadIncidentActionsAndIncidentIdAreAcceptedByHostedDispatcher() {
        val pluginId = "com.weartak.plugin.takcad"
        val actions = listOf("takcad.incidents.refresh", "takcad.server.next", "takcad.bloodhound", "takcad.target")
        fakePlugin.pluginCapabilities = PluginCapabilitiesParcel(hostedMainUi = true)
        fakePlugin.setMainUiSchemaForTest(PluginMainUiSchemaParcel(cards = actions.map { action ->
            MainUiCardParcel(cardType = "ACTION", cardId = action, title = action,
                content = hostedActionContent(action, action, args = mapOf("incidentId" to "incident-17")))
        }))
        coordinator.registerPlugin(pluginId, fakePlugin)
        actions.forEach { action ->
            assertTrue(coordinator.invokeHostedMainUiAction(pluginId, action) is HostedPluginMainUiResult.Ready)
        }
        assertEquals(actions, fakePlugin.mainUiActions.map { it.first })
        assertTrue(fakePlugin.mainUiActions.all { it.second!!.getString("incidentId") == "incident-17" })
    }

    @Test
    fun lateHostedUiReadCannotPublishOrDispatchForReplacedConnection() {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            for (invokeAction in listOf(false, true)) {
                val entered = java.util.concurrent.CountDownLatch(1)
                val release = java.util.concurrent.CountDownLatch(1)
                val old = object : FakeWearTakPlugin("com.plugin.settings") {
                    override fun getMainUiSchema(): PluginMainUiSchemaParcel {
                        entered.countDown()
                        check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        return super.getMainUiSchema()
                    }
                }
                old.pluginCapabilities = PluginCapabilitiesParcel(hostedMainUi = true)
                old.setMainUiSchemaForTest(PluginMainUiSchemaParcel(cards = listOf(
                    MainUiCardParcel(cardType = "ACTION", cardId = "target", title = "Target",
                        content = hostedActionContent("takcad.target", "Target")),
                )))
                coordinator.registerPlugin("com.plugin.settings", old)
                try {
                    val pending = executor.submit<HostedPluginMainUiResult> {
                        if (invokeAction) coordinator.invokeHostedMainUiAction("com.plugin.settings", "target")
                        else coordinator.getHostedMainUi("com.plugin.settings")
                    }
                    assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    coordinator.unregisterPlugin("com.plugin.settings")
                    coordinator.registerPlugin("com.plugin.settings", fakePlugin)
                    release.countDown()
                    assertEquals(HostedPluginMainUiResult.NotConnected, pending.get(5, java.util.concurrent.TimeUnit.SECONDS))
                    assertTrue(old.mainUiActions.isEmpty())
                } finally {
                    release.countDown()
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun lateInitializationCannotRepopulateCacheAfterUnloadOrReplaceNewConnection() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val old = object : FakeWearTakPlugin("com.plugin.settings") {
            override fun getCapabilities(): PluginCapabilitiesParcel {
                entered.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                return super.getCapabilities()
            }
        }
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit { coordinator.onPluginConnected("com.plugin.settings", old) }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            coordinator.unregisterPlugin("com.plugin.settings")
            val replacement = FakeWearTakPlugin("com.plugin.settings")
            replacement.setSettingsSchemaForTest(SettingsSchemaParcel(fields = listOf(
                SettingsFieldParcel(fieldId = "new_only", label = "New", fieldType = SettingsFieldTypes.TOGGLE)
            )))
            coordinator.onPluginConnected("com.plugin.settings", replacement)
            release.countDown()
            pending.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(listOf("new_only"),
                coordinator.getState("com.plugin.settings")!!.schema.fields.map { it.fieldId })
            coordinator.unregisterPlugin("com.plugin.settings")
            coordinator.onPluginConnected("com.plugin.settings", old) { false }
            org.junit.Assert.assertNull(coordinator.getState("com.plugin.settings"))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
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
    fun legacyDittoNetworkPolicyUsesSavedForwardingChoiceAndFailsClosedOnUnreadableValues() {
        val id = TrustedCommunityPlugin.PACKAGE
        val field = PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT
        fun plugin(enabled: Boolean) = FakeWearTakPlugin(id).apply {
            pluginCapabilities = PluginCapabilitiesParcel(hostedSettingsUi = true)
            setSettingsSchemaForTest(SettingsSchemaParcel(fields = listOf(
                SettingsFieldParcel(fieldId = field, label = "Forward CoT", fieldType = SettingsFieldTypes.TOGGLE)
            )))
            applySettings(Bundle().apply { putBoolean(field, enabled) })
        }

        coordinator.onPluginConnected(id, plugin(false))
        assertEquals(false, coordinator.legacyDittoNetworkEnabled())

        coordinator.unregisterPlugin(id)
        coordinator.onPluginConnected(id, plugin(true))
        assertEquals(true, coordinator.legacyDittoNetworkEnabled())

        coordinator.unregisterPlugin(id)
        coordinator.onPluginConnected(id, FakeWearTakPlugin(id))
        assertEquals(null, coordinator.legacyDittoNetworkEnabled())

        coordinator.unregisterPlugin(id)
        val unreadable = object : FakeWearTakPlugin(id) {
            override fun getSettingsValues(): Bundle = error("Settings unavailable")
        }.apply {
            pluginCapabilities = PluginCapabilitiesParcel(hostedSettingsUi = true)
            setSettingsSchemaForTest(SettingsSchemaParcel(fields = listOf(
                SettingsFieldParcel(fieldId = field, label = "Forward CoT", fieldType = SettingsFieldTypes.TOGGLE)
            )))
        }
        coordinator.onPluginConnected(id, unreadable)
        assertEquals(null, coordinator.legacyDittoNetworkEnabled())
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
    fun disabledDittoNetworkFencesOutboundWithoutChangingSavedForwardingChoice() {
        val id = TrustedCommunityPlugin.PACKAGE
        val field = PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT
        val plugin = FakeWearTakPlugin(id).apply {
            pluginCapabilities = PluginCapabilitiesParcel(hostedSettingsUi = true)
            setSettingsSchemaForTest(SettingsSchemaParcel(fields = listOf(
                SettingsFieldParcel(fieldId = field, label = "Forward CoT", fieldType = SettingsFieldTypes.TOGGLE)
            )))
            applySettings(Bundle().apply { putBoolean(field, true) })
        }
        coordinator.onPluginConnected(id, plugin)
        coordinator.networkEnabledForPlugin = { false }
        coordinator.refreshOutboundCotAvailability()

        assertTrue(coordinator.pluginHasSettingEnabled(id, field))
        assertFalse(coordinator.anyPluginHasSettingEnabled(field))
        assertFalse(coordinator.outboundCotAvailable.value)

        coordinator.networkEnabledForPlugin = { true }
        coordinator.refreshOutboundCotAvailability()
        assertTrue(coordinator.pluginHasSettingEnabled(id, field))
        assertTrue(coordinator.outboundCotAvailable.value)
    }

    @Test
    fun failedDittoDisableFencesInFlightAndSurvivesRefreshAndReconnect() {
        val id = TrustedCommunityPlugin.PACKAGE
        val field = PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val result = AtomicReference<SettingsApplyResultParcel>()
        val plugin = object : FakeWearTakPlugin(id) {
            override fun applySettingsWithResult(values: Bundle?, applyMode: Int): SettingsApplyResultParcel {
                if (values?.containsKey(field) == true && !values.getBoolean(field)) {
                    started.countDown()
                    check(finish.await(3, TimeUnit.SECONDS))
                    return SettingsApplyResultParcel(success = false)
                }
                return super.applySettingsWithResult(values, applyMode)
            }
        }.apply {
            pluginCapabilities = PluginCapabilitiesParcel(hostedSettingsUi = true)
            setSettingsSchemaForTest(SettingsSchemaParcel(fields = listOf(
                SettingsFieldParcel(fieldId = field, label = "Forward CoT", fieldType = SettingsFieldTypes.TOGGLE)
            )))
            applySettings(Bundle().apply { putBoolean(field, true) })
        }
        coordinator.onPluginConnected(id, plugin)
        val otherId = "com.plugin.other"
        val other = FakeWearTakPlugin(otherId).apply {
            pluginCapabilities = PluginCapabilitiesParcel(hostedSettingsUi = true)
            setSettingsSchemaForTest(SettingsSchemaParcel(fields = listOf(
                SettingsFieldParcel(fieldId = field, label = "Forward CoT", fieldType = SettingsFieldTypes.TOGGLE)
            )))
            applySettings(Bundle().apply { putBoolean(field, true) })
        }
        coordinator.onPluginConnected(otherId, other)
        val oldToken = checkNotNull(coordinator.dittoForwardingToken())
        val thread = Thread {
            result.set(coordinator.applyPluginSettings(id, Bundle().apply { putBoolean(field, false) }))
        }
        thread.start()
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS))
            assertFalse(coordinator.isDittoForwardingTokenCurrent(oldToken))
            assertFalse(coordinator.connectivityToggles.value.getValue(id).enabled)
            coordinator.onPluginSettingsValuesChanged(id)
            assertFalse(coordinator.pluginHasSettingEnabled(id, field))
            assertTrue(coordinator.pluginHasSettingEnabled(otherId, field))
        } finally {
            finish.countDown()
            thread.join(3_000)
        }
        assertFalse(thread.isAlive)
        assertFalse(result.get().success)
        coordinator.unregisterPlugin(id)
        coordinator.onPluginConnected(id, plugin)
        assertFalse(coordinator.pluginHasSettingEnabled(id, field))
        assertTrue(coordinator.outboundCotAvailable.value)
        coordinator.unregisterPlugin(otherId)
        assertFalse(coordinator.outboundCotAvailable.value)
        assertTrue(coordinator.applyPluginSettings(id, Bundle().apply { putBoolean(field, true) }).success)
        assertTrue(coordinator.pluginHasSettingEnabled(id, field))
        assertFalse(coordinator.isDittoForwardingTokenCurrent(oldToken))
    }

    @Test
    fun staleEnableResultCannotUndoNewerDisable() {
        val id = TrustedCommunityPlugin.PACKAGE
        val field = PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val result = AtomicReference<SettingsApplyResultParcel>()
        val plugin = object : FakeWearTakPlugin(id) {
            override fun applySettingsWithResult(values: Bundle?, applyMode: Int): SettingsApplyResultParcel {
                if (values?.containsKey(field) == true && values.getBoolean(field)) {
                    started.countDown()
                    check(finish.await(3, TimeUnit.SECONDS))
                }
                return super.applySettingsWithResult(values, applyMode)
            }
        }.apply {
            pluginCapabilities = PluginCapabilitiesParcel(hostedSettingsUi = true)
            setSettingsSchemaForTest(SettingsSchemaParcel(fields = listOf(
                SettingsFieldParcel(fieldId = field, label = "Forward CoT", fieldType = SettingsFieldTypes.TOGGLE)
            )))
            applySettings(Bundle().apply { putBoolean(field, true) })
        }
        coordinator.onPluginConnected(id, plugin)
        val thread = Thread {
            result.set(coordinator.applyPluginSettings(id, Bundle().apply { putBoolean(field, true) }))
        }
        thread.start()
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS))
            assertTrue(coordinator.applyPluginSettings(id, Bundle().apply { putBoolean(field, false) }).success)
        } finally {
            finish.countDown()
            thread.join(3_000)
        }
        assertFalse(thread.isAlive)
        assertFalse(result.get().success)
        coordinator.onPluginSettingsValuesChanged(id)
        assertFalse(coordinator.pluginHasSettingEnabled(id, field))
    }

    @Test
    fun failedSettingsReadDisablesCachedForwarding() {
        val id = TrustedCommunityPlugin.PACKAGE
        val field = PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT
        var failRead = false
        val plugin = object : FakeWearTakPlugin(id) {
            override fun getSettingsValues(): Bundle {
                check(!failRead)
                return super.getSettingsValues()
            }
        }.apply {
            pluginCapabilities = PluginCapabilitiesParcel(hostedSettingsUi = true)
            setSettingsSchemaForTest(SettingsSchemaParcel(fields = listOf(
                SettingsFieldParcel(fieldId = field, label = "Forward CoT", fieldType = SettingsFieldTypes.TOGGLE)
            )))
            applySettings(Bundle().apply { putBoolean(field, true) })
        }
        coordinator.onPluginConnected(id, plugin)
        assertTrue(coordinator.pluginHasSettingEnabled(id, field))
        val token = checkNotNull(coordinator.dittoForwardingToken())
        failRead = true
        coordinator.onPluginSettingsValuesChanged(id)
        assertFalse(coordinator.pluginHasSettingEnabled(id, field))
        assertFalse(coordinator.outboundCotAvailable.value)
        failRead = false
        coordinator.onPluginSettingsValuesChanged(id)
        assertTrue(coordinator.pluginHasSettingEnabled(id, field))
        assertFalse(coordinator.isDittoForwardingTokenCurrent(token))
    }

    @Test
    fun throwingDittoApplyNeverFallsBackToUnconfirmedLegacyApply() {
        val id = TrustedCommunityPlugin.PACKAGE
        val field = PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT
        val plugin = object : FakeWearTakPlugin(id) {
            override fun applySettingsWithResult(values: Bundle?, applyMode: Int): SettingsApplyResultParcel {
                throw IllegalStateException("unavailable")
            }
        }.apply {
            pluginCapabilities = PluginCapabilitiesParcel(hostedSettingsUi = true)
            setSettingsSchemaForTest(SettingsSchemaParcel(fields = listOf(
                SettingsFieldParcel(fieldId = field, label = "Forward CoT", fieldType = SettingsFieldTypes.TOGGLE)
            )))
            applySettings(Bundle().apply { putBoolean(field, true) })
        }
        coordinator.onPluginConnected(id, plugin)
        assertFalse(coordinator.applyPluginSettings(id, Bundle().apply { putBoolean(field, false) }).success)
        assertTrue(plugin.settingsValues.getBoolean(field))
        assertFalse(coordinator.pluginHasSettingEnabled(id, field))
    }

    @Test
    fun staleReadCannotOverwriteNewerDittoDisable() {
        val id = TrustedCommunityPlugin.PACKAGE
        val field = PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val blockRead = AtomicBoolean(false)
        val plugin = object : FakeWearTakPlugin(id) {
            override fun getSettingsValues(): Bundle {
                val captured = super.getSettingsValues()
                if (blockRead.get()) {
                    started.countDown()
                    check(finish.await(3, TimeUnit.SECONDS))
                }
                return captured
            }
        }.apply {
            pluginCapabilities = PluginCapabilitiesParcel(hostedSettingsUi = true)
            setSettingsSchemaForTest(SettingsSchemaParcel(fields = listOf(
                SettingsFieldParcel(fieldId = field, label = "Forward CoT", fieldType = SettingsFieldTypes.TOGGLE)
            )))
            applySettings(Bundle().apply { putBoolean(field, true) })
        }
        coordinator.onPluginConnected(id, plugin)
        blockRead.set(true)
        val thread = Thread { coordinator.onPluginSettingsValuesChanged(id) }
        thread.start()
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS))
            assertTrue(coordinator.applyPluginSettings(id, Bundle().apply { putBoolean(field, false) }).success)
        } finally {
            finish.countDown()
            thread.join(3_000)
        }
        assertFalse(thread.isAlive)
        assertFalse(coordinator.pluginHasSettingEnabled(id, field))
        assertFalse(coordinator.getState(id)!!.values.getBoolean(field))
    }

    @Test
    fun outboundCotExcludesOnlyTrackedPlugin() {
        val field = PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT
        val schema = SettingsSchemaParcel(fields = listOf(
            SettingsFieldParcel(fieldId = field, label = "Forward CoT", fieldType = SettingsFieldTypes.TOGGLE)
        ))
        val other = FakeWearTakPlugin("com.plugin.other").apply {
            pluginCapabilities = PluginCapabilitiesParcel(hostedSettingsUi = true)
            setSettingsSchemaForTest(schema)
        }
        fakePlugin.setSettingsSchemaForTest(schema)
        coordinator.onPluginConnected("com.plugin.settings", fakePlugin)
        coordinator.onPluginConnected("com.plugin.other", other)
        val on = Bundle().apply { putBoolean(field, true) }
        assertTrue(coordinator.applyPluginSettings("com.plugin.settings", on).success)
        assertTrue(coordinator.applyPluginSettings("com.plugin.other", on).success)
        coordinator.invokeCotOnPluginsWithSetting(
            field, PluginCotEgress.ACTION_COT_OUTBOUND, Bundle().apply { putString("xml", "<event/>") },
            senderUid = "watch", excludedPluginId = "com.plugin.settings",
        )
        assertTrue(fakePlugin.mainUiActions.isEmpty())
        assertEquals(1, other.mainUiActions.size)
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
    fun transportUsesExistingOptInAndNeverFallsBackToLegacyAction() {
        val pluginId = "com.plugin.settings"
        val field = PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT
        fakePlugin.setSettingsSchemaForTest(SettingsSchemaParcel(fields = listOf(
            SettingsFieldParcel(fieldId = field, label = "Forward CoT", fieldType = SettingsFieldTypes.TOGGLE)
        )))
        coordinator.onPluginConnected(pluginId, fakePlugin)

        val transportIngress = mockk<PluginIngressPort>()
        every { transportIngress.packagesForUid(app, any()) } returns setOf(pluginId)
        val host = PluginTransportHostV1Impl(
            app = app,
            ingress = transportIngress,
            pluginId = pluginId,
            expectedPluginUid = Process.myUid(),
            cotIngest = { TakServerCotIngestResult.UNSUPPORTED },
        )
        val callback = RecordingTransportCallback()
        coordinator.registerTransportHost(pluginId, host)
        val registration = host.registerTransport(
            TransportCapabilitiesParcel(
                version = TransportContract.VERSION,
                outboundPayloadKinds = listOf(
                    TransportPayloadKind.CHAT_UTF8,
                    TransportPayloadKind.COT_XML_UTF8,
                ),
                inboundPayloadKinds = listOf(TransportPayloadKind.CHAT_UTF8),
                channelIds = listOf("satellite"),
                maxPayloadBytes = TransportContract.MAX_PAYLOAD_BYTES,
                maxPendingMessages = TransportContract.MAX_PENDING_MESSAGES,
                runtimeAvailable = true,
                deviceReceipts = true,
                recipientReceipts = true,
            ),
            callback,
        )
        assertEquals(TransportResultCode.ACCEPTED, registration.code)
        host.activateBinding()

        val args = Bundle().apply { putString("xml", outboundChatXml()) }
        coordinator.invokeCotOnPluginsWithSetting(
            field, PluginCotEgress.ACTION_COT_OUTBOUND, args,
            senderUid = "watch-1", senderCallsign = "WATCH",
        )
        assertTrue(fakePlugin.mainUiActions.isEmpty())
        assertEquals(1L, callback.sendLatch.count)

        assertTrue(coordinator.applyPluginSettings(
            pluginId, Bundle().apply { putBoolean(field, true) }
        ).success)
        coordinator.invokeCotOnPluginsWithSetting(
            field, PluginCotEgress.ACTION_COT_OUTBOUND, args,
            senderUid = "watch-1", senderCallsign = "WATCH",
        )

        assertTrue(callback.sendLatch.await(3, TimeUnit.SECONDS))
        assertEquals("satellite", callback.request?.channelId)
        assertEquals(TransportPayloadKind.CHAT_UTF8, callback.request?.message?.payloadKind)
        assertTrue(fakePlugin.mainUiActions.isEmpty())
        val firstRequest = callback.request
        assertEquals(TransportResultCode.ACCEPTED, host.unregisterTransport(registration.sessionId).code)
        coordinator.invokeCotOnPluginsWithSetting(
            field, PluginCotEgress.ACTION_COT_OUTBOUND, args,
            senderUid = "watch-1", senderCallsign = "WATCH",
        )
        assertEquals(firstRequest, callback.request)
        assertTrue(fakePlugin.mainUiActions.isEmpty())
        host.closeBinding()
        coordinator.unregisterTransportHost(pluginId, host)
    }

    private class RecordingTransportCallback : IPluginTransportCallbackV1.Stub() {
        @Volatile
        var request: TransportSendRequestParcel? = null
        val sendLatch = CountDownLatch(1)

        override fun sendMessage(request: TransportSendRequestParcel?): TransportResultParcel {
            this.request = request
            sendLatch.countDown()
            return TransportResultParcel(TransportResultCode.ACCEPTED, request?.sessionId)
        }

        override fun cancelMessage(sessionId: String?, messageId: String?) =
            TransportResultParcel(TransportResultCode.ACCEPTED, sessionId)

        override fun onSessionClosed(sessionId: String?, reason: Int) = Unit
    }

    private fun outboundChatXml() = """
        <event version="2.0" uid="host-event-1" type="b-t-f"
            time="2100-03-01T00:00:00.000Z" start="2100-03-01T00:00:00.000Z"
            stale="2100-03-02T00:00:00.000Z" how="h-g-i-g-o">
            <point lat="0" lon="0" hae="0" ce="1" le="1"/>
            <detail>
                <__chat id="remote-1" senderCallsign="WATCH" messageId="outbound-1">
                    <chatgrp uid0="watch-1" uid1="remote-1" id="remote-1"/>
                </__chat>
                <remarks to="remote-1">Hello plugin</remarks>
            </detail>
        </event>
    """.trimIndent()

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

    @Test
    fun legacyAudioPreferencesAreAppliedOnBindAndLiveChanges() {
        gunshotEnabled.value = true
        gunshotThreshold.value = 67
        uasEnabled.value = true
        uasThreshold.value = 82

        val dart = legacyAudioPlugin(
            PluginDetectionEventBridge.DART_PACKAGE,
            "enable_onnx_gunshot_service",
            "onnx_gunshot_trigger_threshold_percent",
        )
        val falcon = legacyAudioPlugin(
            PluginDetectionEventBridge.FALCON_PACKAGE,
            "enable_onnx_uas_service",
            "onnx_uas_trigger_threshold_percent",
        )

        coordinator.onPluginConnected(PluginDetectionEventBridge.DART_PACKAGE, dart)
        coordinator.onPluginConnected(PluginDetectionEventBridge.FALCON_PACKAGE, falcon)

        assertEquals(true, dart.settingsValues.getBoolean("enable_onnx_gunshot_service"))
        assertEquals(67.0, dart.settingsValues.getDouble("onnx_gunshot_trigger_threshold_percent"), 0.0)
        assertEquals(true, falcon.settingsValues.getBoolean("enable_onnx_uas_service"))
        assertEquals(82.0, falcon.settingsValues.getDouble("onnx_uas_trigger_threshold_percent"), 0.0)

        gunshotEnabled.value = false
        gunshotThreshold.value = 74
        uasEnabled.value = false
        uasThreshold.value = 95

        assertEquals(false, dart.settingsValues.getBoolean("enable_onnx_gunshot_service"))
        assertEquals(74.0, dart.settingsValues.getDouble("onnx_gunshot_trigger_threshold_percent"), 0.0)
        assertEquals(false, falcon.settingsValues.getBoolean("enable_onnx_uas_service"))
        assertEquals(95.0, falcon.settingsValues.getDouble("onnx_uas_trigger_threshold_percent"), 0.0)
    }

    @Test
    fun liveAudioSettingsRejectionReportsTheAffectedBinding() {
        val packageName = PluginDetectionEventBridge.DART_PACKAGE
        var reject = false
        val plugin = legacyAudioPlugin(
            packageName,
            "enable_onnx_gunshot_service",
            "onnx_gunshot_trigger_threshold_percent",
            object : FakeWearTakPlugin(packageName) {
                override fun applySettingsWithResult(values: Bundle?, applyMode: Int): SettingsApplyResultParcel =
                    if (reject) SettingsApplyResultParcel(success = false)
                    else super.applySettingsWithResult(values, applyMode)
            },
        )
        var rejectedBinding: FakeWearTakPlugin? = null
        coordinator.onLegacyAudioSettingsRejected = { id, binding ->
            assertEquals(packageName, id)
            rejectedBinding = binding as FakeWearTakPlugin
        }
        coordinator.onPluginConnected(packageName, plugin)
        reject = true
        gunshotThreshold.value = 75
        assertTrue(rejectedBinding === plugin)
    }

    @Test
    fun audioBindRetriesWhenHostSnapshotChangesDuringApply() {
        val packageName = PluginDetectionEventBridge.FALCON_PACKAGE
        var applies = 0
        val plugin = legacyAudioPlugin(
            packageName,
            "enable_onnx_uas_service",
            "onnx_uas_trigger_threshold_percent",
            object : FakeWearTakPlugin(packageName) {
                override fun applySettingsWithResult(values: Bundle?, applyMode: Int): SettingsApplyResultParcel {
                    if (++applies == 1) coordinator.pushHostSettingsToAllPlugins()
                    return super.applySettingsWithResult(values, applyMode)
                }
            },
        )
        uasEnabled.value = true
        uasThreshold.value = 83
        coordinator.onPluginConnected(packageName, plugin)
        assertTrue(applies >= 2)
        assertTrue(plugin.settingsValues.getBoolean("enable_onnx_uas_service"))
        assertEquals(83.0, plugin.settingsValues.getDouble("onnx_uas_trigger_threshold_percent"), 0.0)
    }

    private fun legacyAudioPlugin(
        packageName: String,
        enabledFieldId: String,
        thresholdFieldId: String,
        plugin: FakeWearTakPlugin = FakeWearTakPlugin(packageName),
    ): FakeWearTakPlugin =
        plugin.apply {
            pluginCapabilities = PluginCapabilitiesParcel()
            setSettingsSchemaForTest(
                SettingsSchemaParcel(
                    fields = listOf(
                        SettingsFieldParcel(
                            fieldId = enabledFieldId,
                            label = "Detection enabled",
                            fieldType = SettingsFieldTypes.TOGGLE,
                            required = true,
                            defaultValueLiteral = "false",
                        ),
                        SettingsFieldParcel(
                            fieldId = thresholdFieldId,
                            label = "Detection threshold",
                            fieldType = SettingsFieldTypes.NUMBER,
                            required = true,
                            defaultValueLiteral = "90",
                        ),
                    ),
                ),
            )
        }
}
