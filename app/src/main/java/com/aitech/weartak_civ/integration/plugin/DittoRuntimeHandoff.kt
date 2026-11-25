package com.aitech.weartak_civ.integration.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.weartak.plugin.sdk.ditto.v1.DittoContractV1
import com.weartak.plugin.sdk.ditto.v1.IDittoHostControlV1
import com.weartak.plugin.sdk.dittoruntime.aidl.IDittoRuntimeV1
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One connection and one lease. A failed or interrupted attempt never clears the host fence. */
internal class DittoRuntimeHandoff(
    private val context: Context,
    private val component: ComponentName,
    private val action: String,
    private val expectedUid: Int,
    private val lifecycleActive: () -> Boolean,
    private val endpointTrusted: () -> Boolean,
    private val firstStartProven: () -> Boolean,
    private val fenceAndQuiesce: () -> Boolean,
) {
    private val closed = AtomicBoolean(false)
    private val connected = CountDownLatch(1)
    private val callbackConfirmed = AtomicBoolean(false)
    private val confirmed = AtomicBoolean(false)
    private val callbackReceived = AtomicBoolean(false)
    private val lock = Any()
    @Volatile private var binder: IBinder? = null
    @Volatile private var leaseBinder: IBinder? = null
    @Volatile private var session: IBinder? = null
    @Volatile private var generation = 0L
    private var bound = false
    private val death = IBinder.DeathRecipient { invalidate() }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (name != component || closed.get() || !callbackReceived.compareAndSet(false, true)) {
                connected.countDown()
                return
            }
            try {
                service.linkToDeath(death, 0)
                binder = service
                if (closed.get()) invalidate()
            } catch (error: RemoteException) {
                Log.w(TAG, "Runtime binder already dead", error)
            }
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) = invalidate()
        override fun onBindingDied(name: ComponentName) = invalidate()
        override fun onNullBinding(name: ComponentName) { connected.countDown() }
    }

    private fun invalidate() {
        confirmed.set(false)
        binder = null
    }

    private fun live(expected: IBinder? = binder): Boolean =
        !closed.get() && expected != null && binder === expected && expected.isBinderAlive &&
            lifecycleActive() && endpointTrusted() && !closed.get() && binder === expected &&
            expected.isBinderAlive && lifecycleActive()

    /** Local handshake only; V1 cannot report every plugin-initiated lease revocation. */
    fun ownershipConfirmed(): Boolean {
        if (!confirmed.get()) return false
        if (live(leaseBinder)) return confirmed.get() && !closed.get()
        confirmed.set(false)
        return false
    }

    /** Must run off the main thread. The handshake callback must verify the exact returned profile. */
    fun acquire(negotiate: (IBinder, Long) -> Boolean): Boolean {
        if (closed.get() || !firstStartProven() || !lifecycleActive() || !endpointTrusted()) return false
        synchronized(lock) {
            if (closed.get() || bound) return false
            try {
                bound = context.bindService(
                    Intent(action).setComponent(component), connection, Context.BIND_AUTO_CREATE,
                )
            } catch (error: SecurityException) {
                Log.w(TAG, "Runtime bind denied", error)
                return false
            }
            if (!bound) return false
        }
        val connectedInTime = try {
            connected.await(10, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "Runtime bind interrupted", error)
            false
        }
        if (!connectedInTime || !live()) {
            close()
            return false
        }
        val quiescent = try {
            fenceAndQuiesce()
        } catch (error: RuntimeException) {
            Log.e(TAG, "Host quiescence could not be established", error)
            false
        }
        if (!quiescent || !live()) {
            close()
            return false
        }
        val remoteBinder = binder ?: return false
        val runtime = IDittoRuntimeV1.Stub.asInterface(remoteBinder)
        val leaseSession = Binder()
        val hostControl = object : IDittoHostControlV1.Stub() {
            override fun quiesceBundledEngine(): Boolean {
                val valid = Binder.getCallingUid() == expectedUid && live(remoteBinder)
                if (valid) callbackConfirmed.set(true)
                return valid
            }
        }
        try {
            val result = runtime.acquire(leaseSession, hostControl)
            if (result.status == DittoContractV1.LEASE_ACQUIRED && result.generation > 0) {
                session = leaseSession
                generation = result.generation
                leaseBinder = remoteBinder
            }
            if (result.status != DittoContractV1.LEASE_ACQUIRED || result.generation <= 0 ||
                !callbackConfirmed.get() || !live(remoteBinder)) {
                release()
                return false
            }
            if (!negotiate(leaseSession, result.generation) || !live(remoteBinder)) {
                release()
                return false
            }
            confirmed.set(true)
            if (ownershipConfirmed()) return true
            release()
            return false
        } catch (error: RemoteException) {
            Log.w(TAG, "Runtime lease handshake failed", error)
            release()
            return false
        } catch (error: RuntimeException) {
            Log.e(TAG, "Runtime lease handshake rejected", error)
            release()
            return false
        }
    }

    fun revoke() {
        closed.set(true)
        confirmed.set(false)
    }

    /** Revokes local access before remote cleanup; release never clears the durable host fence. */
    fun release() {
        revoke()
        val lease = synchronized(lock) {
            val current = Triple(leaseBinder, session, generation)
            session = null
            generation = 0
            leaseBinder = null
            current
        }
        try {
            val (remote, leaseSession, leaseGeneration) = lease
            if (remote != null && leaseSession != null) {
                val result = IDittoRuntimeV1.Stub.asInterface(remote).release(leaseSession, leaseGeneration)
                if (result.status != DittoContractV1.LEASE_STOPPED) {
                    Log.w(TAG, "Runtime release uncertain: ${result.status}")
                }
            }
        } catch (error: RemoteException) {
            Log.w(TAG, "Runtime release failed; ownership remains uncertain", error)
        } finally {
            close()
        }
    }

    fun close() {
        revoke()
        synchronized(lock) {
            try {
                binder?.unlinkToDeath(death, 0)
            } catch (error: NoSuchElementException) {
                Log.w(TAG, "Runtime death recipient already removed", error)
            }
            binder = null
            if (bound) {
                bound = false
                context.unbindService(connection)
            }
        }
    }

    private companion object {
        const val TAG = "DittoRuntimeHandoff"
    }
}
