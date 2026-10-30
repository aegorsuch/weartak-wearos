package com.aitech.weartak_civ.integration.plugin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object MoppPluginAvailability {
    const val PACKAGE = "com.weartak.plugin.mopp"
    private var owner: Any? = null
    private var epoch = 0L
    private val mutableAvailable = MutableStateFlow(false)
    val available = mutableAvailable.asStateFlow()

    @Synchronized fun owns(token: Any): Boolean = owner === token
    @Synchronized fun generation(): Long = epoch
    @Synchronized fun isCurrent(generation: Long?): Boolean = generation == null || generation == epoch

    @Synchronized fun acquire(token: Any) {
        owner = token
        epoch++
        mutableAvailable.value = false
    }

    @Synchronized fun activate(token: Any) {
        if (owner === token && !mutableAvailable.value) {
            epoch++
            mutableAvailable.value = true
        }
    }

    @Synchronized fun release(token: Any) {
        if (owner === token) {
            epoch++
            mutableAvailable.value = false
            owner = null
        }
    }
}
