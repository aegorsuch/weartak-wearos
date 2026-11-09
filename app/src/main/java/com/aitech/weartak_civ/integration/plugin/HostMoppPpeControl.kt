package com.aitech.weartak_civ.integration.plugin

import android.os.Binder
import android.util.Log
import com.aitech.weartak_civ.data.MoppPpeStore
import com.weartak.plugin.sdk.mopp.aidl.IMoppPpeControlV1
import com.weartak.plugin.sdk.mopp.model.MoppPpeResultCode
import com.weartak.plugin.sdk.mopp.model.MoppPpeResultParcel
import kotlinx.coroutines.runBlocking
import java.io.IOException

internal class HostMoppPpeControl(
    private val store: MoppPpeStore,
    private val expectedUid: Int,
    private val authorize: () -> Boolean,
    private val callingUid: () -> Int = Binder::getCallingUid,
) : IMoppPpeControlV1.Stub() {
    private val lock = Any()
    private var active = true
    private var ready = false
    private var readVerified = false

    init {
        MoppPluginAvailability.acquire(this)
    }

    fun ready() = synchronized(lock) {
        ready = true
        if (active && readVerified) MoppPluginAvailability.activate(this)
    }

    fun close() {
        MoppPluginAvailability.release(this)
        synchronized(lock) { active = false }
    }

    override fun read(): MoppPpeResultParcel = call {
        val state = store.read()
        readVerified = true
        if (ready) MoppPluginAvailability.activate(this)
        MoppPpeResultParcel(true, MoppPpeResultCode.OK, state.copy(
            contributing = state.enabled && state.selection != null && MoppPluginAvailability.available.value,
        ))
    }

    override fun save(revision: Long, mutationId: String?, selection: String?): MoppPpeResultParcel =
        call(requireReady = true) { store.save(revision, mutationId, selection) }

    override fun setEnabled(revision: Long, mutationId: String?, enabled: Boolean): MoppPpeResultParcel =
        call(requireReady = true) { store.setEnabled(revision, mutationId, enabled) }

    private fun call(
        requireReady: Boolean = false,
        action: suspend () -> MoppPpeResultParcel,
    ): MoppPpeResultParcel = synchronized(lock) {
        if (callingUid() != expectedUid) return@synchronized rejected(MoppPpeResultCode.UNAUTHORIZED)
        if (!active || !MoppPluginAvailability.owns(this) ||
            (requireReady && (!ready || !readVerified))) {
            return@synchronized rejected(MoppPpeResultCode.INACTIVE_SESSION)
        }
        try {
            if (!authorize()) {
                active = false
                MoppPluginAvailability.release(this)
                return@synchronized rejected(MoppPpeResultCode.UNAUTHORIZED)
            }
            val result = runBlocking { action() }
            if (MoppPluginAvailability.owns(this)) result else rejected(MoppPpeResultCode.INACTIVE_SESSION)
        } catch (error: IOException) {
            Log.e("WearTAK-MOPP", "Could not persist or read MOPP state", error)
            rejected(MoppPpeResultCode.PERSISTENCE_FAILED)
        }
    }

    private fun rejected(code: Int): MoppPpeResultParcel {
        Log.w("WearTAK-MOPP", "MOPP control rejected code=$code")
        return MoppPpeResultParcel(false, code, null)
    }
}
