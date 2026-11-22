package com.aitech.weartak_civ.integration.plugin

import android.util.Log
import com.weartak.plugin.sdk.model.PluginStatusParcel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal enum class DittoPdlMode { COMMUNITY, PRIVATE, NOT_CONFIGURED }

internal data class DittoMeshStatus(val runtime: Int, val permissions: Int) {
    val canPrompt: Boolean get() = runtime in 2..4 && permissions == 2
    val needsRecovery: Boolean get() = runtime in 2..5 &&
        (permissions == 3 || (runtime == 5 && permissions == 2))

    companion object {
        @Suppress("DEPRECATION")
        fun parse(status: PluginStatusParcel?): DittoMeshStatus? {
            return try {
                val extras = status?.extras ?: return null
                val runtime = extras.get("com.weartak.plugin.ditto.runtimeState.v1") as? Int ?: return null
                val permissions = extras.get("com.weartak.plugin.ditto.meshPermissions.v1") as? Int ?: return null
                if (runtime !in 1..5 || permissions !in 1..3) null else DittoMeshStatus(runtime, permissions)
            } catch (error: RuntimeException) {
                Log.w("WearTAK-DittoPdl", "Invalid mesh status extras", error)
                null
            }
        }
    }
}

internal class DittoPdlModeState {
    private val lock = Any()
    private val state = MutableStateFlow<DittoPdlMode?>(null)
    val mode = state.asStateFlow()
    private val meshState = MutableStateFlow<DittoMeshStatus?>(null)
    val mesh = meshState.asStateFlow()
    private var revision = 0L
    private var closed = false

    fun readRevision(): Long = synchronized(lock) { revision }

    fun update(status: PluginStatusParcel?) {
        synchronized(lock) {
            if (!closed) {
                revision++
                state.value = parse(status)
                meshState.value = DittoMeshStatus.parse(status)
            }
        }
    }

    fun completeRead(readRevision: Long, status: PluginStatusParcel?) {
        synchronized(lock) {
            if (!closed && revision == readRevision) {
                state.value = parse(status)
                meshState.value = DittoMeshStatus.parse(status)
            }
        }
    }

    fun isReadCurrent(readRevision: Long): Boolean = synchronized(lock) {
        !closed && revision == readRevision
    }

    fun close() = synchronized(lock) {
        closed = true
        revision++
        state.value = null
        meshState.value = null
    }

    @Suppress("DEPRECATION")
    private fun parse(status: PluginStatusParcel?): DittoPdlMode? {
        val value = try {
            status?.extras?.get(KEY) as? Int
        } catch (error: RuntimeException) {
            Log.w("WearTAK-DittoPdl", "Invalid status extras", error)
            null
        } ?: return null
        return when (value) {
            1 -> DittoPdlMode.COMMUNITY
            2 -> DittoPdlMode.PRIVATE
            3 -> DittoPdlMode.NOT_CONFIGURED
            else -> null
        }
    }

    private companion object {
        const val KEY = "com.weartak.plugin.ditto.pdlMode.v1"
    }
}

internal object DittoPdlStatusWork {
    private val worker = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue(), { task ->
        Thread(task, "WearTAK-DittoPdlStatus").apply { isDaemon = true }
    })

    fun execute(work: () -> Unit): Boolean {
        try {
            worker.execute { work() }
            return true
        } catch (_: RejectedExecutionException) {
            Log.w("WearTAK-DittoPdl", "Status worker busy")
            return false
        }
    }
}
