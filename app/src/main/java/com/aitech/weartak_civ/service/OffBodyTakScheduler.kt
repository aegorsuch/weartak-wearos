package com.aitech.weartak_civ.service

import android.app.AlarmManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

/** Service-owned, inexact opportunities: no exact alarm permission or catch-up wakeups. */
internal class OffBodyTakScheduler(
    context: Context,
    private val connect: () -> Unit,
    private val disconnect: () -> Unit,
    private val waitingChanged: (Boolean) -> Unit = {},
) {
    private val handler = Handler(Looper.getMainLooper())
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WearTAK:offbody-tak-window")
    private val policy = OffBodyTakPolicy()
    private var eligible = false
    @Volatile private var stopped = false
    private var generation = 0L
    private var pending: AlarmManager.OnAlarmListener? = null
    private var closeWindow: Runnable? = null

    fun allows(): Boolean = !stopped && policy.allows(SystemClock.elapsedRealtime())

    @Synchronized
    fun update(worn: Boolean?, directEligible: Boolean, chargingOrDocked: Boolean = false,
        stayConnectedOffWrist: Boolean = false) {
        if (stopped) return
        val changed = policy.observe(worn, chargingOrDocked, stayConnectedOffWrist)
        if (!changed && eligible == directEligible) return
        eligible = directEligible
        cancel()
        policy.close()
        record(if (policy.batching) 0L else 1L)
        if (policy.batching) {
            disconnect()
            if (eligible) schedule()
        } else if (changed || eligible) connect()
    }

    private fun schedule() {
        val token = generation
        val listener = AlarmManager.OnAlarmListener {
            synchronized(this) {
                if (stopped || token != generation || !eligible || !policy.batching) return@OnAlarmListener
                pending = null
                policy.open(SystemClock.elapsedRealtime())
                // Hold only the bounded window so its one-shot close is not postponed by sleep.
                wakeLock.acquire(OffBodyTakPolicy.WINDOW_MS + 5_000L)
                record(2L)
                closeWindow = Runnable {
                    synchronized(this) {
                        if (stopped || token != generation) return@Runnable
                        policy.close()
                        disconnect()
                        if (wakeLock.isHeld) wakeLock.release()
                        closeWindow = null
                        record(0L)
                    }
                }.also { handler.postDelayed(it, OffBodyTakPolicy.WINDOW_MS) }
                schedule()
                connect()
            }
        }
        pending = listener
        alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + OffBodyTakPolicy.INTERVAL_MS,
            "WearTAK-offbody-reconnect", listener, handler)
    }

    private fun record(mode: Long) {
        waitingChanged(mode == 0L && eligible && policy.batching && !stopped)
        AppDiagnostics.record(DiagnosticEvent.OFF_BODY_TAK, mapOf(
            DiagnosticMetric.MODE to mode,
            DiagnosticMetric.ENABLED to if (eligible) 1L else 0L,
            DiagnosticMetric.INTERVAL_SECONDS to OffBodyTakPolicy.INTERVAL_MS / 1000,
            DiagnosticMetric.DURATION_MS to OffBodyTakPolicy.WINDOW_MS
        ))
    }

    private fun cancel() {
        generation++
        pending?.let(alarms::cancel)
        pending = null
        closeWindow?.let(handler::removeCallbacks)
        closeWindow = null
        if (wakeLock.isHeld) wakeLock.release()
    }

    @Synchronized
    fun stop() {
        stopped = true
        cancel()
        policy.close()
        waitingChanged(false)
    }
}
