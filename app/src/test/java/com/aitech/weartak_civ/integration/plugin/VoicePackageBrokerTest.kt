package com.aitech.weartak_civ.integration.plugin

import android.content.pm.*
import android.os.*
import com.aitech.weartak_civ.WearTakApplication
import com.weartak.voice.api.*
import com.weartak.voice.api.VoicePackageContract as C
import io.mockk.every
import io.mockk.mockk
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.UUID
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VoicePackageBrokerTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var app: WearTakApplication
    private var cert = byteArrayOf(1, 2, 3)
    private var live = true
    private lateinit var broker: VoicePackageBroker
    private lateinit var binding: VoicePackageBroker.Binding
    private var offer: Bundle? = null
    private var offers = 0
    private val receiver = object : IVoicePackageReceiverV1.Stub() {
        override fun onOffer(value: Bundle?) { offer = value; offers++ }
        override fun onRevoked(handle: String?, reason: Int) { }
    }
    @Before fun setup() {
        cert = byteArrayOf(1, 2, 3); live = true
        val pm = mockk<PackageManager>()
        val signing = mockk<SigningInfo>()
        every { signing.apkContentsSigners } answers { arrayOf(Signature(cert)) }
        every { pm.getPackageInfo(any<String>(), any<Int>()) } returns PackageInfo().apply { signingInfo = signing }
        every { pm.getPackageUid(any<String>(), any<Int>()) } returns Binder.getCallingUid()
        app = mockk<WearTakApplication>()
        every { app.filesDir } returns folder.root
        every { app.packageManager } returns pm
        broker = VoicePackageBroker(app)
        binding = broker.newBinding("voice.test", Binder.getCallingUid()) { live }
        binding.registerReceiver(receiver)
    }
    private fun stage(bytes: ByteArray = "never-write-payload".toByteArray()): String {
        val id = UUID.randomUUID().toString(); broker.stage(C.JSON, bytes, id, "tak:source"); return id
    }
    @Test fun oneUseMemoryHandleAndIdempotentStates() {
        val bytes = "never-write-payload".toByteArray(); val id = stage(bytes)
        assertArrayEquals(bytes, binding.takePackage(offer!!.getString("handle")).getByteArray("payload"))
        assertEquals(C.UNAVAILABLE, binding.takePackage(offer!!.getString("handle")).getInt("code"))
        val hash = VoicePackageBroker.digest(bytes)
        assertEquals(C.STAGED, binding.reportState(id, hash, hash, C.STAGED, C.OK).getInt("state"))
        assertEquals(C.APPLIED, binding.reportState(id, hash, hash, C.APPLIED, C.OK).getInt("state"))
        assertEquals(C.APPLIED, broker.stage(C.JSON, bytes, UUID.randomUUID().toString(), "tak:source").getInt("state"))
        assertEquals(1, offers)
        val metadata = java.io.File(folder.root, "voice-package-receipts.json").readText()
        assertFalse(metadata.contains("never-write-payload"))
        assertEquals(C.APPLIED, VoicePackageBroker(app).status(id).getInt("state"))
    }
    @Test fun everyCallRechecksLiveBindingAndSigner() {
        stage(); cert = byteArrayOf(9, 8, 7)
        Assert.assertThrows(SecurityException::class.java) { binding.takePackage(offer!!.getString("handle")) }
        cert = byteArrayOf(1, 2, 3); live = false
        Assert.assertThrows(SecurityException::class.java) { binding.getCapabilities() }
        val wrongUid = broker.newBinding("voice.other", Binder.getCallingUid() + 1) { true }
        Assert.assertThrows(SecurityException::class.java) { wrongUid.getCapabilities() }
    }
    @Test fun foreignReceiverAndOriginCannotTakeOrReadTheOffer() {
        val id = stage()
        val other = broker.newBinding("voice.other", Binder.getCallingUid()) { true }; other.registerReceiver(receiver)
        assertFalse(broker.canDeliver())
        Assert.assertThrows(SecurityException::class.java) { other.takePackage(offer!!.getString("handle")) }
        Assert.assertThrows(IllegalStateException::class.java) { broker.statusForOrigin(id, "tak:foreign") }
        assertNotNull(binding.takePackage(offer!!.getString("handle")))
    }
    @Test fun expiryAndUnbindRevokeAndPermitFreshRetry() {
        val id = stage(); val handle = offer!!.getString("handle")
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(121))
        assertEquals(C.UNAVAILABLE, binding.takePackage(handle).getInt("code"))
        assertEquals(C.EXPIRED, broker.status(id).getInt("state"))
        stage(); val second = offer!!.getString("handle")
        binding.close()
        Assert.assertThrows(SecurityException::class.java) { binding.takePackage(second) }
        assertEquals(0, broker.readyCount())
    }
    @Test fun invalidStateCannotClaimAppliedWithoutStaging() {
        val id = stage(); val hash = offer!!.getString("payloadSha256")
        Assert.assertThrows(IllegalStateException::class.java) { binding.reportState(id, hash, hash, C.APPLIED, C.OK) }
        assertEquals(C.DOWNLOADED, broker.status(id).getInt("state"))
    }
    @Test fun validTakeCanRecoverItsDurableReviewAfterBindingLoss() {
        val id = stage(); val hash = offer!!.getString("payloadSha256")!!
        binding.takePackage(offer!!.getString("handle")); binding.close()
        assertEquals(C.FAILED, broker.status(id).getInt("state"))
        val replacement = broker.newBinding("voice.test", Binder.getCallingUid()) { true }
        replacement.registerReceiver(receiver)
        assertEquals(C.STAGED, replacement.reportState(id, hash, hash, C.STAGED, C.OK).getInt("state"))
        assertEquals(C.APPLIED, replacement.reportState(id, hash, hash, C.APPLIED, C.OK).getInt("state"))
    }

}
