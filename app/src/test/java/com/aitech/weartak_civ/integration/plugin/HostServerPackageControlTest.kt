package com.aitech.weartak_civ.integration.plugin

import android.app.Application
import android.os.Binder
import android.os.Looper
import android.os.Parcel
import com.weartak.plugin.sdk.nearbyimport.Pkcs12ImportErrorCode
import com.weartak.plugin.sdk.nearbyimport.Pkcs12ImportState
import com.weartak.plugin.sdk.nearbyimport.Pkcs12StageCode
import com.weartak.plugin.sdk.serverpackage.*
import com.weartak.plugin.sdk.serverpackage.aidl.IWearTakServerPackageControlV1
import com.weartak.plugin.sdk.serverpackage.model.ServerPackageStageRequestParcel
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HostServerPackageControlTest {
    private var uid = 42
    private var trusted = true
    private var now = 100L
    private val host = HostServerPackageControl(42, { trusted }, {}, {}, { uid }, { now })
    private val remote = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int) =
            host.asBinder().transact(code, data, reply, flags)
    }
    private val client = IWearTakServerPackageControlV1.Stub.asInterface(remote)
    private fun bytes() = ServerPackageCodec.encode(ServerPackage("Synthetic", "event.example", 8089,
        ServerPackageTrustMode.PUBLIC_CA, ServerPackageIdentityMode.RECEIVER_ENROLLMENT))
    private fun id() = UUID.randomUUID().toString()
    private fun pending(): String {
        host.allowFromWearerLaunch()
        val result = client.requestExport(id())
        assertEquals(ServerPackageExportCode.PENDING, result.code)
        shadowOf(Looper.getMainLooper()).idle()
        val handle = requireNotNull(result.handle)
        assertTrue(host.claimExport(handle))
        assertFalse(host.claimExport(handle))
        return handle
    }

    @Test fun sdkBinderStageIsReceiptOnlyAndReviewApplyOneUse() {
        val raw = bytes()
        val staged = host.stageServerPackage(ServerPackageStageRequestParcel(id(), raw))
        assertTrue(raw.all { it == 0.toByte() })
        assertEquals(Pkcs12StageCode.STAGED, staged.code)
        assertEquals(Pkcs12ImportState.STAGED, client.getStatus(staged.handle).state)
        assertEquals(Pkcs12ImportErrorCode.UNAUTHORIZED, client.requestReview(staged.handle).errorCode)
        host.allowFromWearerLaunch()
        assertEquals(Pkcs12ImportState.REVIEW_PENDING, client.requestReview(staged.handle).state)
        val handle = requireNotNull(staged.handle)
        assertTrue(host.imports.claimReview(handle))
        val packageData = host.imports.openPackage(handle)
        assertEquals("event.example", packageData.host)
        packageData.wipe()
        assertTrue(host.imports.completeApplied(handle))
        assertEquals(Pkcs12ImportState.APPLIED, client.getStatus(handle).state)
        assertFalse(host.imports.completeApplied(handle))
        host.close()
    }

    @Test fun readySurvivesUiReturnAndTakeIsOneUseThroughRealSdkBinder() {
        val handle = pending()
        val payload = bytes()
        assertTrue(host.approveExport(handle, payload) { true })
        assertTrue(payload.all { it == 0.toByte() })
        host.dismissPendingExport(handle)
        assertEquals(ServerPackageExportState.READY, client.getExportStatus(handle).state)
        val taken = client.takeExport(handle)
        assertEquals(ServerPackageTakeCode.OK, taken.code)
        assertEquals(null, ServerPackageCodec.validate(taken.encodedPackage))
        requireNotNull(taken.encodedPackage).fill(0)
        assertEquals(ServerPackageExportState.TAKEN, client.getExportStatus(handle).state)
        assertEquals(ServerPackageTakeCode.TERMINAL, client.takeExport(handle).code)
        assertEquals(ServerPackageExportState.TAKEN, client.cancelExport(handle).state)
        host.close()
    }

    @Test fun callerSessionForeignReplayAndOversizeFailClosed() {
        host.allowFromWearerLaunch()
        val requestId = id()
        val first = client.requestExport(requestId)
        host.allowFromWearerLaunch()
        assertEquals(ServerPackageExportCode.REPLAYED_REQUEST, client.requestExport(requestId).code)
        assertEquals(ServerPackageExportCode.HOST_BUSY, client.requestExport(id()).code)
        assertEquals(ServerPackageTakeCode.INVALID_HANDLE, client.takeExport("foreign").code)
        uid = 99
        assertEquals(ServerPackageExportCode.UNAUTHORIZED, client.requestExport(id()).code)
        assertEquals(ServerPackageExportReason.UNAUTHORIZED, client.getExportStatus(first.handle).errorCode)
        assertEquals(ServerPackageTakeCode.UNAUTHORIZED, client.takeExport(first.handle).code)
        assertEquals(ServerPackageExportReason.UNAUTHORIZED, client.cancelExport(first.handle).errorCode)
        assertEquals(Pkcs12StageCode.UNAUTHORIZED,
            client.stageServerPackage(ServerPackageStageRequestParcel(id(), bytes())).code)
        uid = 42
        assertEquals(Pkcs12StageCode.TOO_LARGE,
            client.stageServerPackage(ServerPackageStageRequestParcel(id(), ByteArray(32701))).code)
        trusted = false
        assertEquals(ServerPackageTakeCode.UNAUTHORIZED, client.takeExport(first.handle).code)
        trusted = true
        assertFalse(host.exportLive(requireNotNull(first.handle)))
        host.close()
    }

    @Test fun cancelDeclineExpiryDeathAndPolicyChangeDestroyExport() {
        val cancelled = pending()
        host.dismissPendingExport(cancelled)
        assertEquals(ServerPackageExportState.CANCELLED, client.getExportStatus(cancelled).state)
        val declined = pending()
        host.rejectExport(declined, ServerPackageExportReason.USER_DECLINED)
        assertEquals(ServerPackageExportReason.USER_DECLINED, client.getExportStatus(declined).errorCode)
        val expired = pending()
        host.approveExport(expired, bytes()) { true }
        now = client.getExportStatus(expired).expiresAtElapsedRealtimeMs
        assertEquals(ServerPackageTakeCode.EXPIRED, client.takeExport(expired).code)
        val changed = pending()
        var allowed = true
        host.approveExport(changed, bytes()) { allowed }
        allowed = false
        assertEquals(ServerPackageTakeCode.UNAUTHORIZED, client.takeExport(changed).code)
        assertEquals(ServerPackageExportState.REJECTED, client.getExportStatus(changed).state)
        val died = pending()
        host.approveExport(died, bytes()) { true }
        host.close()
        assertFalse(host.exportLive(died))
        assertEquals(ServerPackageTakeCode.UNAUTHORIZED, client.takeExport(died).code)
    }
}
