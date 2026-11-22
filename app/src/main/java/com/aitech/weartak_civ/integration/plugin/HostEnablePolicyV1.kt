package com.aitech.weartak_civ.integration.plugin

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.weartak.plugin.sdk.enablepolicy.v1.EnablePolicyContractV1 as Contract
import com.weartak.plugin.sdk.enablepolicy.v1.IEnablePolicyHostV1
import com.weartak.plugin.sdk.enablepolicy.v1.IEnablePolicyListenerV1
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

internal class HostEnablePolicyV1 @VisibleForTesting constructor(
    private val sessionId: String,
    private val expectedUid: Int,
    private val authorized: () -> Boolean,
    private val bindingActive: () -> Boolean,
    private val repository: DataRepository,
    private val callingUid: () -> Int = Binder::getCallingUid,
) : IEnablePolicyHostV1.Stub() {
    private data class Registration(
        val listener: IEnablePolicyListenerV1,
        val binder: IBinder,
        val death: IBinder.DeathRecipient,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private var registration: Registration? = null
    @Volatile private var observedRevision = 0L

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        val old = synchronized(lock) { registration.also { registration = null } }
        old?.binder?.unlinkToDeath(old.death, 0)
        scope.cancel()
    }

    suspend fun awaitClosed(timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            scope.coroutineContext[Job]?.join()
            true
        } == true

    override fun register(sessionId: String?, listener: IEnablePolicyListenerV1?): Int {
        val uid = callingUid()
        reject(uid, sessionId)?.let { return it }
        if (listener == null) return Contract.INVALID_ARGUMENT
        if (!HostCutoverBindings.tryEnter()) return Contract.BUSY
        try {
            reject(uid, sessionId)?.let { return it }
            if (synchronized(lock) { registration != null }) return Contract.BUSY
            val binder = listener.asBinder()
            if (!binder.isBinderAlive) return Contract.UNAVAILABLE
            val death = IBinder.DeathRecipient { close() }
            try {
                binder.linkToDeath(death, 0)
            } catch (_: RemoteException) {
                return Contract.UNAVAILABLE
            }
            val next = Registration(listener, binder, death)
            synchronized(lock) {
                if (!closed.get()) registration = next
            }
            if (!isRegistered(next)) {
                binder.unlinkToDeath(death, 0)
                return Contract.INACTIVE_SESSION
            }
            val initial = runBlocking { repository.readDittoEnablePolicy(newBinding = true) }
            if (reject(uid, sessionId) != null || !isRegistered(next) ||
                !repository.isDittoPolicyPhoneGenerationCurrent(initial.phoneGeneration)) {
                close()
                return Contract.UNCERTAIN
            }
            scope.launch {
                repository.dittoEnablePolicySignals.collect {
                    if (!isRegistered(next) || reject(expectedUid, this@HostEnablePolicyV1.sessionId) != null) {
                        close()
                        return@collect
                    }
                    try {
                        val state = repository.readDittoEnablePolicy()
                        if (isRegistered(next) && reject(expectedUid, this@HostEnablePolicyV1.sessionId) == null &&
                            repository.isDittoPolicyPhoneGenerationCurrent(state.phoneGeneration)) {
                            next.listener.onPolicyChanged(this@HostEnablePolicyV1.sessionId, state.revision)
                        }
                    } catch (_: IOException) {
                        Log.e(TAG, "Ditto enable policy notification unavailable")
                        close()
                    } catch (_: IllegalStateException) {
                        Log.e(TAG, "Ditto enable policy state invalid")
                        close()
                    } catch (_: RemoteException) {
                        Log.w(TAG, "Ditto enable policy listener unavailable")
                        close()
                    }
                }
            }
            return Contract.OK
        } catch (_: IOException) {
            Log.e(TAG, "Ditto enable policy registration failed")
            close()
            return Contract.UNCERTAIN
        } catch (_: IllegalStateException) {
            Log.e(TAG, "Ditto enable policy state invalid")
            close()
            return Contract.UNCERTAIN
        } finally {
            HostCutoverBindings.leave()
        }
    }

    override fun read(sessionId: String?): Bundle {
        val uid = callingUid()
        rejectRegistered(uid, sessionId)?.let { return failure(it) }
        if (!HostCutoverBindings.tryEnter()) return failure(Contract.BUSY)
        try {
            rejectRegistered(uid, sessionId)?.let { return failure(it) }
            val state = runBlocking { repository.readDittoEnablePolicy() }
            rejectRegistered(uid, sessionId)?.let { return failure(it) }
            if (!repository.isDittoPolicyPhoneGenerationCurrent(state.phoneGeneration)) return failure(Contract.BUSY)
            observedRevision = state.revision
            return Bundle().apply {
                putInt(Contract.KEY_CODE, Contract.OK)
                putLong(Contract.KEY_REVISION, state.revision)
                putBoolean(Contract.KEY_DESIRED_ENABLED, state.desiredEnabled)
                putBoolean(Contract.KEY_LOCAL_ENABLE_BLOCKED, state.localEnableBlocked)
                putBoolean(Contract.KEY_MENU_NAVIGATION_BLOCKED, state.menuNavigationBlocked)
                putInt(Contract.KEY_APPLICATION, state.application)
            }
        } catch (_: IOException) {
            Log.e(TAG, "Ditto enable policy read failed")
            return failure(Contract.UNAVAILABLE)
        } catch (_: IllegalStateException) {
            Log.e(TAG, "Ditto enable policy state invalid")
            return failure(Contract.UNAVAILABLE)
        } finally {
            HostCutoverBindings.leave()
        }
    }

    override fun requestEnabled(sessionId: String?, expectedRevision: Long, enabled: Boolean): Int {
        val uid = callingUid()
        requestRejection(uid, sessionId)?.let { return it }
        if (expectedRevision < 1) return Contract.REQUEST_INVALID
        if (!HostCutoverBindings.tryEnter()) return Contract.REQUEST_BUSY
        var started = false
        try {
            requestRejection(uid, sessionId)?.let { return it }
            started = true
            val (result, generation) = runBlocking { repository.requestDittoEnabled(expectedRevision, enabled) }
            if (requestRejection(uid, sessionId) != null ||
                !repository.isDittoPolicyPhoneGenerationCurrent(generation)) {
                close()
                return Contract.REQUEST_UNCERTAIN
            }
            return result
        } catch (_: IOException) {
            Log.e(TAG, "Ditto enable policy request failed")
            close()
            return if (started) Contract.REQUEST_UNCERTAIN else Contract.REQUEST_UNAVAILABLE
        } catch (_: IllegalStateException) {
            Log.e(TAG, "Ditto enable policy state invalid")
            close()
            return if (started) Contract.REQUEST_UNCERTAIN else Contract.REQUEST_UNAVAILABLE
        } finally {
            HostCutoverBindings.leave()
        }
    }

    override fun reportApplied(sessionId: String?, revision: Long, application: Int): Int {
        val uid = callingUid()
        rejectRegistered(uid, sessionId)?.let { return it }
        if (revision < 1 || (application != Contract.APPLIED && application != Contract.FAILED)) {
            return Contract.INVALID_ARGUMENT
        }
        if (revision != observedRevision) return Contract.STALE
        if (!HostCutoverBindings.tryEnter()) return Contract.BUSY
        try {
            rejectRegistered(uid, sessionId)?.let { return it }
            val (result, generation) = runBlocking { repository.reportDittoPolicyApplied(revision, application) }
            if (rejectRegistered(uid, sessionId) != null ||
                !repository.isDittoPolicyPhoneGenerationCurrent(generation)) {
                close()
                return Contract.UNCERTAIN
            }
            return result
        } catch (_: IOException) {
            Log.e(TAG, "Ditto enable policy acknowledgement failed")
            close()
            return Contract.UNCERTAIN
        } catch (_: IllegalStateException) {
            Log.e(TAG, "Ditto enable policy state invalid")
            close()
            return Contract.UNCERTAIN
        } finally {
            HostCutoverBindings.leave()
        }
    }

    private fun isRegistered(value: Registration): Boolean =
        synchronized(lock) { !closed.get() && registration === value && value.binder.isBinderAlive }

    private fun reject(uid: Int, session: String?): Int? = when {
        uid != expectedUid || !authorized() -> Contract.UNAUTHORIZED
        session.isNullOrBlank() -> Contract.INVALID_ARGUMENT
        closed.get() || !bindingActive() || session != sessionId -> Contract.INACTIVE_SESSION
        else -> null
    }

    private fun rejectRegistered(uid: Int, session: String?): Int? =
        reject(uid, session) ?: if (synchronized(lock) { registration?.let(::isRegistered) != true })
            Contract.INACTIVE_SESSION else null

    private fun requestRejection(uid: Int, session: String?): Int? = when (rejectRegistered(uid, session)) {
        Contract.UNAUTHORIZED -> Contract.REQUEST_UNAUTHORIZED
        Contract.INVALID_ARGUMENT -> Contract.REQUEST_INVALID
        Contract.INACTIVE_SESSION -> Contract.REQUEST_INACTIVE
        null -> null
        else -> Contract.REQUEST_UNAVAILABLE
    }

    private fun failure(code: Int) = Bundle().apply { putInt(Contract.KEY_CODE, code) }

    companion object {
        private const val TAG = "WearTAK-EnablePolicy"

        fun forPreactivation(
            app: WearTakApplication,
            transport: PluginTransportHostV1Impl,
            sessionId: String,
            expectedUid: Int,
        ): HostEnablePolicyV1? {
            if (!TrustedCommunityPlugin.verify(app, expectedUid) || !transport.isPreactivationSession(sessionId)) {
                return null
            }
            return HostEnablePolicyV1(
                sessionId, expectedUid, { TrustedCommunityPlugin.verify(app, expectedUid) },
                { transport.isPreactivationSession(sessionId) }, app.repo,
            )
        }
    }
}
