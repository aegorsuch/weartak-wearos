package com.aitech.weartak_civ.integration.managedconfig

import android.os.Bundle
import android.util.Log
import com.aitech.weartak_civ.data.DataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class ManagedHailPolicyCache(
    private val scope: CoroutineScope,
    private val readRestrictions: suspend () -> Bundle,
) {
    private val lock = Any()
    private var generation = 0L
    private val mutablePolicy = MutableStateFlow(DataRepository.ManagedHailServicePolicy(false, null))
    val policy = mutablePolicy.asStateFlow()

    fun refresh() {
        val request = synchronized(lock) {
            generation += 1
            mutablePolicy.value = DataRepository.ManagedHailServicePolicy(false, null)
            generation
        }
        scope.launch {
            try {
                val restrictions = readRestrictions()
                val enabled = if (restrictions.containsKey(KEY)) {
                    restrictions.get(KEY) as? Boolean
                        ?: throw IllegalArgumentException("Invalid managed HAIL policy")
                } else null
                synchronized(lock) {
                    if (generation == request) {
                        mutablePolicy.value = DataRepository.ManagedHailServicePolicy(true, enabled)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Failed to refresh managed HAIL policy; plugin PLI remains disabled", error)
            }
        }
    }

    private companion object {
        const val KEY = "hail_service_enabled"
        const val TAG = "ManagedHailPolicy"
    }
}
