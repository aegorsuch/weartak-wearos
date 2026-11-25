package com.aitech.weartak_civ.service

import android.app.Application
import com.aitech.weartak_civ.events.DittoDocumentEvent
import com.aitech.weartak_civ.presentation.MainActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.greenrobot.eventbus.EventBus
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DittoPublicationGateTest {
    @Test fun physicalCallMustReturnBeforeDrainAndClosedGateRejectsNewWork() {
        val gate = DittoPublicationGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val worker = Thread {
            val admission = checkNotNull(gate.admit())
            try {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            } finally {
                admission.close()
                finished.countDown()
            }
        }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(gate.closeAndDrain(50))
            assertNull(gate.admit())
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertTrue(gate.closeAndDrain(1000))
        } finally {
            release.countDown()
            worker.join(5000)
        }
    }

    @Test fun uncertainNativeOutcomeNeverBecomesDrainProof() {
        val gate = DittoPublicationGate()
        val admission = checkNotNull(gate.admit())
        gate.markUncertain()
        admission.close()
        assertFalse(gate.closeAndDrain(1000))
        assertNull(gate.admit())
    }

}
