package com.aitech.weartak_civ.cot

import android.content.SharedPreferences
import android.util.Log
import com.aitech.weartak_civ.service.DittoPublicationGate
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class DittoPointAdmissionClosedException : IllegalStateException("Ditto local point admission closed")

/** Persistent, one-way local-point writer fence; cutover must not start on a failed commit. */
internal class DittoPointMutationFence(private val preferences: SharedPreferences) {
    private val admission = synchronized(admissions) {
        admissions.getOrPut(preferences) { Admission() }
    }

    fun closeAndDrain(timeoutMillis: Long): Boolean = admission.gate.closeAndDrain(timeoutMillis)

    fun fence(timeoutMillis: Long = 5_000): Boolean {
        require(timeoutMillis > 0)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        try {
            if (!admission.fencing.tryLock(timeoutMillis, TimeUnit.MILLISECONDS)) return false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        try {
            admission.confirmed.set(false)
            return fenceUntil(deadline).also { admission.confirmed.set(it) }
        } finally {
            admission.fencing.unlock()
        }
    }

    private fun fenceUntil(deadline: Long): Boolean {
        if (!acquireUntil(deadline)) return false
        try {
            if (admission.uncertain.get() || failed.containsKey(preferences) ||
                uncertainWrites.containsKey(preferences)) return false
        } finally {
            lock.unlock()
        }
        val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        if (remaining <= 0 || !closeAndDrain(remaining)) return false
        val commit = FutureTask {
            if (!acquireUntil(deadline)) return@FutureTask false
            try {
                if (admission.uncertain.get() || failed.containsKey(preferences) ||
                    uncertainWrites.containsKey(preferences)) return@FutureTask false
                when (preferences.all[KEY]) {
                    true -> return@FutureTask true
                    null, false -> Unit
                    else -> return@FutureTask false
                }
                failed[preferences] = true
                preferences.edit().putBoolean(KEY, true).commit().also {
                    if (it) failed.remove(preferences)
                    else Log.e(TAG, "Ditto point fence commit failed; outcome uncertain")
                }
            } finally {
                lock.unlock()
            }
        }
        Thread(commit, "ditto-point-fence-commit").apply { isDaemon = true; start() }
        val waitNanos = deadline - System.nanoTime()
        if (waitNanos <= 0) {
            admission.uncertain.set(true)
            Log.e(TAG, "Ditto point fence commit timed out; outcome uncertain")
            return false
        }
        return try {
            commit.get(waitNanos, TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            admission.uncertain.set(true)
            Log.e(TAG, "Ditto point fence commit timed out; outcome uncertain")
            false
        } catch (_: InterruptedException) {
            admission.uncertain.set(true)
            Thread.currentThread().interrupt()
            Log.e(TAG, "Ditto point fence commit interrupted; outcome uncertain")
            false
        } catch (error: ExecutionException) {
            admission.uncertain.set(true)
            Log.e(TAG, "Ditto point fence commit failed; outcome uncertain", error.cause)
            false
        }
    }

    private fun acquireUntil(deadline: Long): Boolean {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) return false
        return try {
            lock.tryLock(remaining, TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    fun <T> mutate(block: () -> T): T {
        if (admission.depth.get() > 0) return lock.withLock {
            if (failed.containsKey(preferences) || isFenced()) throw DittoPointAdmissionClosedException()
            block()
        }
        val ticket = admission.gate.admit() ?: throw DittoPointAdmissionClosedException()
        try {
            admission.depth.set(1)
            return lock.withLock {
                if (failed.containsKey(preferences) || isFenced()) throw DittoPointAdmissionClosedException()
                block()
            }
        } finally {
            admission.depth.remove()
            ticket.close()
        }
    }

    fun commit(editor: SharedPreferences.Editor): Boolean = lock.withLock {
        try {
            editor.commit().also { if (!it) uncertainWrites[preferences] = true }
        } catch (error: Exception) {
            uncertainWrites[preferences] = true
            throw error
        }
    }

    fun <T : Any> withConfirmedFence(action: () -> T?): T? = lock.withLock {
        if (!admission.confirmed.get() || admission.uncertain.get() ||
            failed.containsKey(preferences) || uncertainWrites.containsKey(preferences) ||
            preferences.all[KEY] != true) return@withLock null
        action()
    }

    private fun isFenced(): Boolean = when (preferences.all[KEY]) {
        null, false -> false
        else -> true
    }

    companion object {
        private class Admission {
            val gate = DittoPublicationGate()
            val uncertain = AtomicBoolean(false)
            val confirmed = AtomicBoolean(false)
            val fencing = ReentrantLock()
            val depth = object : ThreadLocal<Int>() {
                override fun initialValue(): Int = 0
            }
        }
        private const val KEY = "local-point-writers-fenced"
        private const val TAG = "WearTAK-PointFence"
        private val lock = ReentrantLock()
        private val admissions = WeakHashMap<SharedPreferences, Admission>()
        private val failed = WeakHashMap<SharedPreferences, Boolean>()
        private val uncertainWrites = WeakHashMap<SharedPreferences, Boolean>()
    }
}
