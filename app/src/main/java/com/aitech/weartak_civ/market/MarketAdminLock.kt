package com.aitech.weartak_civ.market

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class MarketAdminLock(context: Context) {
    private val preferences = context.getSharedPreferences("market_admin", Context.MODE_PRIVATE)
    private val guard = Any()
    private val _locked = MutableStateFlow(preferences.getBoolean(KEY_LOCKED, false))
    val locked = _locked.asStateFlow()

    fun setLocked(value: Boolean): Boolean = synchronized(guard) {
        if (!preferences.edit().putBoolean(KEY_LOCKED, value).commit()) {
            Log.e(TAG, "Cannot persist Market lock; keeping Market locked")
            preferences.edit().putBoolean(KEY_LOCKED, true).commit()
            _locked.value = true
            return false
        }
        _locked.value = value
        true
    }

    fun <T> ifUnlocked(action: () -> T): T? = synchronized(guard) {
        if (_locked.value) null else action()
    }

    private companion object {
        const val KEY_LOCKED = "locked"
        const val TAG = "MarketAdminLock"
    }
}
