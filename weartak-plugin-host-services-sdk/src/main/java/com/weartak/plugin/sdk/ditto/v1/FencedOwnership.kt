package com.weartak.plugin.sdk.ditto.v1

/**
 * In-memory fencing only. Callers must authenticate sessions and independently establish
 * quiescence before passing positive evidence; this class does not stop either engine.
 */
class FencedOwnership {
    enum class State { IDLE, ACTIVE, STOPPING, UNCERTAIN }

    data class Snapshot(val state: State, val generation: Long)

    private var state = State.IDLE
    private var generation = 0L
    private var owner: Any? = null

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(state, generation)

    /** Returns null unless both engines are positively quiescent and no lease exists. */
    @Synchronized
    fun acquire(session: Any, hostQuiescent: Boolean, pluginQuiescent: Boolean): Long? {
        if (state != State.IDLE || !hostQuiescent || !pluginQuiescent || generation == Long.MAX_VALUE) {
            return null
        }
        generation += 1
        owner = session
        state = State.ACTIVE
        return generation
    }

    @Synchronized
    fun isCurrent(session: Any, fence: Long): Boolean =
        state == State.ACTIVE && owner === session && generation == fence

    /** Invalidates the lease before requesting plugin shutdown. */
    @Synchronized
    fun beginStop(session: Any, fence: Long): Boolean {
        if (!isCurrent(session, fence)) return false
        state = State.STOPPING
        return true
    }

    /** Death, disconnect or timeout invalidates the lease, but cannot prove shutdown. */
    @Synchronized
    fun markUncertain(session: Any, fence: Long): Boolean {
        if (owner !== session || generation != fence || state == State.IDLE) return false
        state = State.UNCERTAIN
        return true
    }

    /** Only a trusted local adapter with positive plugin shutdown evidence may call this. */
    @Synchronized
    fun confirmPluginStopped(fence: Long): Boolean {
        if (generation != fence || (state != State.STOPPING && state != State.UNCERTAIN)) {
            return false
        }
        owner = null
        state = State.IDLE
        return true
    }
}
