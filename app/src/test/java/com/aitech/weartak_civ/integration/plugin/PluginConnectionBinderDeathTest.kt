package com.aitech.weartak_civ.integration.plugin

import android.app.Application
import android.content.ComponentName
import android.os.Process
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cep.plugin.PluginTrust
import com.weartak.plugin.sdk.activation.v1.ITransportReadinessV1
import com.weartak.plugin.sdk.activation.v1.TransportReadinessContractV1 as Readiness
import com.weartak.plugin.sdk.brokerretirement.v1.BrokerRetirementContractV1 as Retirement
import com.weartak.plugin.sdk.brokerretirement.v1.IBrokerRetirementV1
import com.weartak.plugin.sdk.cutover.v1.CutoverCompletionContractV1
import com.weartak.plugin.sdk.enablepolicy.v1.EnablePolicyContractV1
import com.weartak.plugin.sdk.pdlsnapshot.v1.PdlSnapshotContractV1
import com.weartak.plugin.sdk.pointcutover.v1.PointCutoverContractV1
import com.weartak.plugin.sdk.point.v1.PointContractV1
import com.weartak.plugin.sdk.transport.TransportContract
import com.weartak.plugin.sdk.transport.TransportPayloadKind
import com.weartak.plugin.sdk.transport.TransportResultCode
import com.weartak.plugin.sdk.transport.aidl.IPluginTransportCallbackV1
import com.weartak.plugin.sdk.transport.aidl.IPluginTransportHostV1
import com.weartak.plugin.sdk.transport.model.TransportCapabilitiesParcel
import com.weartak.plugin.sdk.transport.model.TransportResultParcel
import com.weartak.plugin.sdk.transport.model.TransportSendRequestParcel
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.weartak.plugin.sdk.nearbyimport.Pkcs12ImportState
import com.weartak.plugin.sdk.community.v1.CommunityIdentityContract
import com.weartak.plugin.sdk.serverpackage.ServerPackage
import com.weartak.plugin.sdk.serverpackage.ServerPackageCodec
import com.weartak.plugin.sdk.serverpackage.ServerPackageExportState
import com.weartak.plugin.sdk.serverpackage.ServerPackageIdentityMode
import com.weartak.plugin.sdk.serverpackage.ServerPackageTrustMode
import com.weartak.plugin.sdk.serverpackage.model.ServerPackageStageRequestParcel
import java.util.UUID
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class PluginConnectionBinderDeathTest {
    @Test
    fun everyConnectionLossRevokesPendingReviewAndReadyExportImmediately() {
        for (termination in listOf("death", "disconnect", "binding", "teardown")) {
            val app = mockk<WearTakApplication>(relaxed = true)
            val ingress = mockk<PluginIngressPort>(relaxed = true)
            val manager = mockk<PluginManager>(relaxed = true)
            val trust = mockk<PluginTrust>(relaxed = true)
            val connection = PluginConnection(app, ingress, ComponentName("com.synthetic.plugin", ".Svc"),
                42, manager, trust)
            val packages = HostServerPackageControl(42, { true }, {}, {}, { 42 })
            PluginConnection::class.java.getDeclaredField("serverPackages").apply {
                isAccessible = true
                set(connection, packages)
            }
            val bytes = ServerPackageCodec.encode(ServerPackage("Synthetic", "event.example", 8089,
                ServerPackageTrustMode.PUBLIC_CA, ServerPackageIdentityMode.RECEIVER_ENROLLMENT))
            val staged = requireNotNull(packages.stageServerPackage(
                ServerPackageStageRequestParcel(UUID.randomUUID().toString(), bytes.copyOf())).handle)
            packages.allowFromWearerLaunch()
            packages.requestReview(staged)
            assertTrue(packages.imports.claimReview(staged))
            val reviewState = requireNotNull(packages.imports.observe(staged))
            val outgoing = requireNotNull(packages.requestExport(UUID.randomUUID().toString()).handle)
            assertTrue(packages.claimExport(outgoing))
            assertTrue(packages.approveExport(outgoing, bytes) { true })
            val exportState = requireNotNull(packages.observeExport(outgoing))
            when (termination) {
                "death" -> connection.triggerBinderDeathSyncForTest()
                "disconnect" -> connection.onServiceDisconnected(null)
                "binding" -> connection.onBindingDied(null)
                else -> connection.tearDownBinder("synthetic")
            }
            assertEquals(termination, Pkcs12ImportState.CANCELLED, reviewState.value.state)
            assertEquals(termination, ServerPackageExportState.CANCELLED, exportState.value.state)
            assertFalse(packages.imports.completeApplied(staged))
            assertFalse(packages.exportLive(outgoing))
            assertTrue(runCatching { packages.imports.openPackage(staged) }.isFailure)
            packages.close()
        }
    }


    @Before
    fun resetDiag() {
        PluginDiagnostics.resetForTest()
    }

    @Test
    fun triggerBinderDeathSyncForTest_notifiesManagerAndDiagnostics() {
        val app = mockk<WearTakApplication>(relaxed = true)
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        val mgr = mockk<PluginManager>(relaxed = true)
        val cn = ComponentName("com.death.plugin", ".Svc")
        val trust = mockk<PluginTrust>(relaxed = true)
        val conn = PluginConnection(app, ingress, cn, 100, mgr, trust)
        conn.triggerBinderDeathSyncForTest()
        verify(exactly = 1) { mgr.onConnectionDied(conn) }
        val snap = PluginDiagnostics.snapshot()
        assertEquals(1, snap.binderDeaths)
        assertEquals(1, snap.categoryCounts[PluginDiagnostics.AdmissionCategory.BINDER_DEATH] ?: 0)
    }

    @Test
    fun binderDeathRecipient_postsToMainAndEvicts() {
        val app = mockk<WearTakApplication>(relaxed = true)
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        val mgr = mockk<PluginManager>(relaxed = true)
        val trust = mockk<PluginTrust>(relaxed = true)
        val conn = PluginConnection(app, ingress, ComponentName("com.death2", ".Svc"), 101, mgr, trust)
        conn.binderDeathRecipientForTest.binderDied()
        ShadowLooper.shadowMainLooper().idle()
        verify(exactly = 1) { mgr.onConnectionDied(conn) }
        val snap = PluginDiagnostics.snapshot()
        assertEquals(1, snap.binderDeaths)
        assertEquals(1, snap.categoryCounts[PluginDiagnostics.AdmissionCategory.BINDER_DEATH] ?: 0)
    }
}
