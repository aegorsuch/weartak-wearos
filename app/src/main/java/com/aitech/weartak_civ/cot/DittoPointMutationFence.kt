package com.aitech.weartak_civ.cot

import android.content.SharedPreferences
import java.util.WeakHashMap

/** Persistent, one-way local-point writer fence; cutover must not start on a failed commit. */
internal class DittoPointMutationFence(private val preferences: SharedPreferences) {
    fun fence(): Boolean = synchronized(lock) {
        if (failed.containsKey(preferences) || uncertainWrites.containsKey(preferences)) {
            return@synchronized false
        }
        when (preferences.all[KEY]) {
            true -> return@synchronized true
            null, false -> Unit
            else -> return@synchronized false
        }
        failed[preferences] = true
        preferences.edit().putBoolean(KEY, true).commit().also {
            if (it) failed.remove(preferences)
        }
    }

    fun <T> mutate(block: () -> T): T = synchronized(lock) {
        check(!failed.containsKey(preferences) && !isFenced()) { "Ditto local point migration fenced" }
        block()
    }

    fun commit(editor: SharedPreferences.Editor): Boolean = synchronized(lock) {
        try {
            editor.commit().also { if (!it) uncertainWrites[preferences] = true }
        } catch (error: Exception) {
            uncertainWrites[preferences] = true
            throw error
        }
    }

    private fun isFenced(): Boolean = when (preferences.all[KEY]) {
        null, false -> false
        else -> true
    }

    companion object {
        private const val KEY = "local-point-writers-fenced"
        private val lock = Any()
        private val failed = WeakHashMap<SharedPreferences, Boolean>()
        private val uncertainWrites = WeakHashMap<SharedPreferences, Boolean>()
    }
}
