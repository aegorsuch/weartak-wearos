package com.aitech.weartak_civ.service

import com.aitech.weartak_civ.cot.CotMessageKind
import com.aitech.weartak_civ.cot.CotCompanionPayload
import com.aitech.weartak_civ.cot.CotOutboundMessage
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.integration.plugin.MoppPluginAvailability
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CotDispatchTransportResumeTest {
    @Test
    fun pliCapturedBeforeMoppLeaseLossIsRejectedBeforeTransportDispatch() = kotlinx.coroutines.runBlocking {
        val lease = Any()
        MoppPluginAvailability.acquire(lease)
        MoppPluginAvailability.activate(lease)
        try {
            val generation = MoppPluginAvailability.generation()
            val snapshot = mockk<DataRepository.PliSnapshot>()
            every { snapshot.moppGeneration } returns generation
            val message = CotOutboundMessage("<event/>", CotMessageKind.PLI, CotCompanionPayload.Pli(snapshot))
            MoppPluginAvailability.release(lease)
            val method = CotDispatchService::class.java.getDeclaredMethod(
                "dispatch", CotOutboundMessage::class.java, kotlin.coroutines.Continuation::class.java,
            ).apply { isAccessible = true }
            val accepted = kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Boolean> {
                method.invoke(CotDispatchService(), message, it)
            }
            assertFalse(accepted)
        } finally {
            MoppPluginAvailability.release(lease)
        }
    }

    @Before
    fun setUp() {
        MainActivity.mainActivity = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        io.mockk.unmockkAll()
    }

    @Test
    fun routinePliKeepsGraceButChatAlertAndMarkerResumeDirectTransports() {
        val repository = mockk<DataRepository>(relaxed = true)
        var graceActive = true
        every { repository.isCompanionConnected } returns kotlinx.coroutines.flow.MutableStateFlow(false)
        every { repository.resumeWatchTransportsImmediately() } answers {
            if (!graceActive) return@answers false
            graceActive = false
            true
        }
        val service = CotDispatchService()
        CotDispatchService::class.java.getDeclaredField("dataRepository").apply {
            isAccessible = true
            set(service, repository)
        }

        service.resumeWatchTransportsForDispatch(CotMessageKind.PLI)
        assertTrue(graceActive)
        verify(exactly = 0) { repository.resumeWatchTransportsImmediately() }
        every { repository.shouldSuppressWatchTransportNow() } returns true
        assertTrue(service.shouldDeferPliForTransportGrace(CotMessageKind.PLI))

        service.resumeWatchTransportsForDispatch(CotMessageKind.CHAT)
        assertFalse(graceActive)
        every { repository.shouldSuppressWatchTransportNow() } returns false
        assertFalse(service.shouldDeferPliForTransportGrace(CotMessageKind.CHAT))
        graceActive = true
        service.resumeWatchTransportsForDispatch(CotMessageKind.EMERGENCY)
        assertFalse(graceActive)
        graceActive = true
        service.resumeWatchTransportsForDispatch(CotMessageKind.MARKER)
        assertFalse(graceActive)
        verify(exactly = 3) { repository.resumeWatchTransportsImmediately() }
    }

    @Test fun emergencyWithoutFixResumesDittoEvenWhenSentAsChatNotice() {
        io.mockk.mockkObject(DittoService.Companion)
        every { DittoService.resumeSyncForAlert() } returns Unit
        val repository = mockk<DataRepository>(relaxed = true)
        every { repository.isCompanionConnected } returns kotlinx.coroutines.flow.MutableStateFlow(false)
        val service = CotDispatchService()
        CotDispatchService::class.java.getDeclaredField("dataRepository").apply {
            isAccessible = true
            set(service, repository)
        }
        service.resumeWatchTransportsForDispatch(CotMessageKind.PLI)
        service.resumeWatchTransportsForDispatch(CotMessageKind.CHAT)
        verify(exactly = 0) { DittoService.resumeSyncForAlert() }
        service.resumeWatchTransportsForDispatch(CotMessageKind.CHAT, emergencyNotice = true)
        service.resumeWatchTransportsForDispatch(CotMessageKind.EMERGENCY)
        verify(exactly = 2) { DittoService.resumeSyncForAlert() }
    }
}
