package com.aitech.weartak_civ.integration.plugin

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException

/**
 * Synchronous Binder calls cannot be interrupted safely. Retain their slots until they
 * actually return: no timeout-driven replacement threads, queues, or overlapping generations.
 */
internal object PluginLifecycleWork {
    private val packages = ConcurrentHashMap.newKeySet<String>()
    private val workers = ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS, SynchronousQueue(), { task ->
        Thread(task, "WearTAK-PluginLifecycle").apply { isDaemon = true }
    })

    fun isBusy(packageName: String): Boolean = packageName in packages

    fun execute(packageName: String, work: () -> Unit): Boolean {
        if (!packages.add(packageName)) return false
        try {
            workers.execute {
                try {
                    work()
                } finally {
                    packages.remove(packageName)
                }
            }
            return true
        } catch (_: RejectedExecutionException) {
            packages.remove(packageName)
            Log.w("PluginLifecycle", "Lifecycle workers busy; rejected pkg=$packageName")
            return false
        }
    }
}
