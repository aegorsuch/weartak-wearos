package com.aitech.weartak_civ.integration.plugin

import android.app.Application
import android.os.Binder
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.DeflaterOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DittoStartupIdentityLeaseTest {
    private val session = Binder()
    private val wrongSession = Binder()
    private val identity = DittoStartupIdentityLease.Identity("WEAROS_0123456789abcdef", syntheticIdentity())

    private fun syntheticIdentity(): String {
        val json = JSONObject().put("v", 2).put("it", "SK").put("a", "synthetic-app")
            .put("s", "synthetic-key").put("t", "synthetic-token")
            .put("e", System.currentTimeMillis() + 60_000)
        val output = ByteArrayOutputStream()
        DeflaterOutputStream(output).use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
        return Base64.encodeToString(output.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
    }
    private fun lease(
        authenticate: () -> Boolean = { true },
        resolve: () -> DittoStartupIdentityLease.Identity? = { identity },
    ) = DittoStartupIdentityLease(42, session, 7, authenticate, resolve)

    @Test fun drainRequiresMatchingReceivedNonceAndRetainsLeaseIsolation() {
        val lease = lease()
        val first = lease.readStartup(42, session, 7, 1)
        assertEquals(DittoStartupIdentityLease.Result.OK, first.result)
        assertEquals(16, first.nonce!!.size)
        assertEquals(DittoStartupIdentityLease.Result.BUSY, lease.readStartup(42, session, 7, 2).result)
        assertEquals(DittoStartupIdentityLease.Result.UNAUTHORIZED, lease.beginDrain(43, session, 7))
        assertEquals(DittoStartupIdentityLease.Result.INACTIVE_LEASE, lease.beginDrain(42, wrongSession, 7))
        assertEquals(DittoStartupIdentityLease.Result.INACTIVE_LEASE, lease.beginDrain(42, session, 8))
        assertEquals(DittoStartupIdentityLease.Result.NOT_READY, lease.finalizeRevoke(42, session, 7))
        assertEquals(DittoStartupIdentityLease.Result.OK, lease.beginDrain(42, session, 7))
        assertEquals(DittoStartupIdentityLease.Result.NOT_READY, lease.readStartup(42, session, 7, 2).result)
        assertEquals(DittoStartupIdentityLease.Result.PENDING, lease.finalizeRevoke(42, session, 7))
        val incorrect = first.nonce!!.clone().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertEquals(DittoStartupIdentityLease.Result.INVALID_ARGUMENT,
            lease.finishRead(42, session, 7, 1, incorrect, false))
        assertEquals(DittoStartupIdentityLease.Result.INVALID_ARGUMENT,
            lease.finishRead(42, session, 7, 1, first.nonce, null))
        assertEquals(DittoStartupIdentityLease.Result.PENDING, lease.finalizeRevoke(42, session, 7))
        assertEquals(DittoStartupIdentityLease.Result.OK, lease.finishRead(42, session, 7, 1, first.nonce, false))
        assertEquals(DittoStartupIdentityLease.Result.INVALID_ARGUMENT,
            lease.finishRead(42, session, 7, 1, first.nonce, false))
        assertEquals(DittoStartupIdentityLease.Result.OK, lease.finalizeRevoke(42, session, 7))
        assertEquals(DittoStartupIdentityLease.Result.INACTIVE_LEASE, lease.readStartup(42, session, 7, 3).result)
    }

    @Test fun drainDuringResolutionDiscardsDataAndWaitsForResolution() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val lease = lease(resolve = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            identity
        })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val read = executor.submit<DittoStartupIdentityLease.ReadResult> {
                lease.readStartup(42, session, 7, 1)
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(DittoStartupIdentityLease.Result.INVALID_ARGUMENT,
                lease.finishRead(42, session, 7, 1, ByteArray(16), false))
            assertEquals(DittoStartupIdentityLease.Result.OK, lease.beginDrain(42, session, 7))
            assertEquals(DittoStartupIdentityLease.Result.PENDING, lease.finalizeRevoke(42, session, 7))
            release.countDown()
            val result = read.get(5, TimeUnit.SECONDS)
            assertEquals(DittoStartupIdentityLease.Result.NOT_READY, result.result)
            assertNull(result.identity)
            assertNull(result.nonce)
            assertEquals(DittoStartupIdentityLease.Result.OK, lease.finalizeRevoke(42, session, 7))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun deathOrTrustLossStaysUncertainAndCannotAuthorizeResponse() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val lease = lease(resolve = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            identity
        })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val read = executor.submit<DittoStartupIdentityLease.ReadResult> {
                lease.readStartup(42, session, 7, 1)
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            lease.invalidate()
            release.countDown()
            val result = read.get(5, TimeUnit.SECONDS)
            assertEquals(DittoStartupIdentityLease.Result.UNCERTAIN, result.result)
            assertNull(result.identity)
            assertNull(result.nonce)
            assertEquals(DittoStartupIdentityLease.Result.UNCERTAIN, lease.beginDrain(42, session, 7))
            assertEquals(DittoStartupIdentityLease.Result.UNCERTAIN, lease.finalizeRevoke(42, session, 7))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
        var trusted = true
        val other = lease(authenticate = { trusted })
        trusted = false
        assertEquals(DittoStartupIdentityLease.Result.UNAUTHORIZED, other.readStartup(42, session, 7, 1).result)
        trusted = true
        assertEquals(DittoStartupIdentityLease.Result.UNCERTAIN, other.beginDrain(42, session, 7))
    }

    @Test fun deathAfterIssueCannotTurnPendingReadIntoCleanRevocation() {
        val lease = lease()
        val result = lease.readStartup(42, session, 7, 1)
        assertEquals(DittoStartupIdentityLease.Result.OK, result.result)
        lease.invalidate()
        assertEquals(DittoStartupIdentityLease.Result.UNCERTAIN,
            lease.finishRead(42, session, 7, 1, result.nonce, false))
        assertEquals(DittoStartupIdentityLease.Result.UNCERTAIN, lease.finalizeRevoke(42, session, 7))
    }

    @Test fun rejectsReplayAndInvalidIdentityWithoutPayload() {
        val lease = lease()
        val first = lease.readStartup(42, session, 7, 3)
        assertEquals(DittoStartupIdentityLease.Result.OK, first.result)
        assertEquals(DittoStartupIdentityLease.Result.OK, lease.finishRead(42, session, 7, 3, first.nonce, true))
        assertEquals(DittoStartupIdentityLease.Result.INVALID_ARGUMENT, lease.readStartup(42, session, 7, 3).result)
        assertEquals(DittoStartupIdentityLease.Result.INVALID_ARGUMENT, lease.readStartup(42, session, 7, 0).result)
        val max = lease.readStartup(42, session, 7, Long.MAX_VALUE)
        assertEquals(DittoStartupIdentityLease.Result.OK, max.result)
        assertEquals(DittoStartupIdentityLease.Result.OK,
            lease.finishRead(42, session, 7, Long.MAX_VALUE, max.nonce, false))
        assertEquals(DittoStartupIdentityLease.Result.INVALID_ARGUMENT, lease.readStartup(42, session, 7, 1).result)
        for (uid in listOf("", "WEAROS_null", "WEAROS_", "a".repeat(513), "x\n", "\uD800")) {
            val invalid = lease(resolve = { DittoStartupIdentityLease.Identity(uid, identity.encodedIdentity) })
                .readStartup(42, session, 7, 1)
            assertEquals(DittoStartupIdentityLease.Result.INVALID_ARGUMENT, invalid.result)
            assertNull(invalid.identity)
            assertNull(invalid.nonce)
        }
        val invalidPdl = lease(resolve = { DittoStartupIdentityLease.Identity(identity.uid, "invalid") })
            .readStartup(42, session, 7, 1)
        assertEquals(DittoStartupIdentityLease.Result.INVALID_ARGUMENT, invalidPdl.result)
        assertNull(invalidPdl.identity)
        assertNull(invalidPdl.nonce)
        val unicode = lease(resolve = { DittoStartupIdentityLease.Identity("界".repeat(512), identity.encodedIdentity) })
            .readStartup(42, session, 7, 1)
        assertEquals(DittoStartupIdentityLease.Result.OK, unicode.result)
    }
}
