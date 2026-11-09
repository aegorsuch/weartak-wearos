package com.aitech.weartak_civ.integration.plugin

import android.app.Application
import android.os.Binder
import android.os.Parcel
import com.aitech.weartak_civ.data.takserver.TestClientP12
import com.weartak.plugin.sdk.nearbyimport.Pkcs12ImportErrorCode as Error
import com.weartak.plugin.sdk.nearbyimport.Pkcs12ImportState as State
import com.weartak.plugin.sdk.nearbyimport.Pkcs12StageCode as Code
import com.weartak.plugin.sdk.nearbyimport.aidl.IWearTakPkcs12ImportControlV1
import com.weartak.plugin.sdk.nearbyimport.model.Pkcs12StageRequestParcel
import java.util.Base64
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HostPkcs12ImportControlTest {
    private var uid = 42
    private var trusted = true
    private var now = 100L
    private val launches = mutableListOf<String>()
    private val host = HostPkcs12ImportControl(42, { trusted }, { launches += it }, { uid }, { now })
    private val bytes get() = Base64.getDecoder().decode(TestClientP12.p12("fixture-only"))
    private fun request(payload: ByteArray = bytes, id: String = UUID.randomUUID().toString()) =
        Pkcs12StageRequestParcel(id, payload)

    @Test fun stageIsReceiptOnlyAndDoesNotLaunchOrApply() {
        val request = request()
        val result = host.stagePkcs12(request)
        assertEquals(Code.STAGED, result.code)
        assertTrue(request.pkcs12Bytes.all { it == 0.toByte() })
        assertEquals(22, result.handle!!.length)
        assertEquals(now + 300_000, result.expiresAtElapsedRealtimeMs)
        assertEquals(State.STAGED, host.getStatus(result.handle).state)
        assertTrue(launches.isEmpty())
        assertEquals(Error.UNAUTHORIZED, host.requestReview(result.handle).errorCode)
        assertTrue(launches.isEmpty())
        host.close()
    }

    @Test fun actualSdkProxyTraversesBinderAndReviewClaimIsOneUse() {
        val remote = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int) =
                host.asBinder().transact(code, data, reply, flags)
        }
        val client = IWearTakPkcs12ImportControlV1.Stub.asInterface(remote)
        host.allowReviewFromWearerLaunch()
        val stage = client.stagePkcs12(request())
        val handle = requireNotNull(stage.handle)
        assertEquals(State.REVIEW_PENDING, client.requestReview(handle).state)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(handle), launches)
        assertTrue(host.claimReview(handle))
        assertFalse(host.claimReview(handle))
        assertEquals(Error.TERMINAL, client.requestReview(handle).errorCode)
        val opened = host.open(handle, "fixture-only")
        assertTrue(opened is com.aitech.weartak_civ.data.takserver.Pkcs12CertificateImport.Opened.Client)
        assertTrue(host.completeApplied(handle))
        assertEquals(State.APPLIED, client.getStatus(handle).state)
        assertEquals(State.APPLIED, client.cancel(handle).state)
        assertFalse(host.completeApplied(handle))
        assertTrue(runCatching { host.open(handle, "fixture-only") }.isFailure)
    }

    @Test fun wrongUidSignerSessionForeignAndReplayedRequestsFailClosed() {
        val id = UUID.randomUUID().toString()
        val accepted = host.stagePkcs12(request(id = id))
        assertEquals(Code.REPLAYED_REQUEST, host.stagePkcs12(request(id = id)).code)
        uid = 99
        val rejected = request()
        assertEquals(Code.UNAUTHORIZED, host.stagePkcs12(rejected).code)
        assertTrue(rejected.pkcs12Bytes.all { it == 0.toByte() })
        assertEquals(Error.UNAUTHORIZED, host.getStatus(accepted.handle).errorCode)
        assertEquals(Error.UNAUTHORIZED, host.cancel(accepted.handle).errorCode)
        assertEquals(Error.UNAUTHORIZED, host.requestReview(accepted.handle).errorCode)
        uid = 42
        assertEquals(Error.INVALID_HANDLE, host.getStatus("foreign").errorCode)
        val other = HostPkcs12ImportControl(42, { true }, {}, { 42 }, { now })
        assertEquals(Error.INVALID_HANDLE, other.getStatus(accepted.handle).errorCode)
        trusted = false
        assertEquals(Code.UNAUTHORIZED, host.stagePkcs12(request()).code)
        trusted = true
        assertFalse(host.isLive(requireNotNull(accepted.handle)))
        other.close()
    }

    @Test fun expiryBoundaryCancelAndDeathErasePayloadAndCannotApply() {
        host.allowReviewFromWearerLaunch()
        val expired = host.stagePkcs12(request())
        host.requestReview(expired.handle)
        val handle = requireNotNull(expired.handle)
        assertTrue(host.claimReview(handle))
        now = expired.expiresAtElapsedRealtimeMs
        assertEquals(State.EXPIRED, host.getStatus(handle).state)
        assertFalse(host.completeApplied(handle))
        assertTrue(runCatching { host.open(handle, "fixture-only") }.isFailure)
        val cancelled = host.stagePkcs12(request())
        assertEquals(State.CANCELLED, host.cancel(cancelled.handle).state)
        assertEquals(State.CANCELLED, host.cancel(cancelled.handle).state)
        val died = host.stagePkcs12(request())
        host.close()
        assertFalse(host.isLive(requireNotNull(died.handle)))
        assertEquals(Error.UNAUTHORIZED, host.getStatus(died.handle).errorCode)
    }

    @Test fun byteBoundsAndDerEnvelopeAreExactAndBusyDoesNotReplaceStage() {
        assertEquals(Code.INVALID_REQUEST, host.stagePkcs12(request(byteArrayOf())).code)
        assertEquals(Code.INVALID_REQUEST, host.stagePkcs12(request(ByteArray(20))).code)
        assertEquals(Code.TOO_LARGE, host.stagePkcs12(request(ByteArray(32701))).code)
        val max = ByteArray(32700)
        byteArrayOf(0x30, 0x82.toByte(), 0x7f, 0xb8.toByte(), 2, 1, 3, 0x30).copyInto(max)
        val first = host.stagePkcs12(request(max))
        assertEquals(Code.STAGED, first.code)
        assertEquals(Code.HOST_BUSY, host.stagePkcs12(request()).code)
        assertEquals(State.STAGED, host.getStatus(first.handle).state)
        host.cancel(first.handle)
        assertEquals(Code.STAGED, host.stagePkcs12(request()).code)
        host.close()
    }
}
