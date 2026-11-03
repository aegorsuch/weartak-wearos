package com.aitech.weartak_civ.service

import android.Manifest
import android.app.Activity
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.aitech.weartak_civ.data.DataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import live.ditto.Ditto
import live.ditto.DittoConnectionType
import live.ditto.DittoError
import live.ditto.DittoPresenceGraph
import live.ditto.DittoPresenceObserver
import kotlin.random.Random

// Diagnostics store ordinals: append only.
internal enum class BleDiscoveryReason {
    STARTUP, UI, WRIST_ON, SETTINGS, PRESENCE, WINDOW_EXPIRED, GATED, CLOSED,
    RETRY, RADIO_ON, RADIO_OFF, BLE_LINK_LOST, ENABLE_FAILED, STOP_FAILED,
    NO_LINK_TIMEOUT, BLE_LINK_GAINED, RETRY_NOW, USER_PAUSE, DITTO_RESET
}

internal enum class BleDiscoveryAction { START, STOP, BACKOFF, WARNING_ON, WARNING_OFF }

/** Persisted by name; ACTIVE means the 30-minute recovery budget may run. */
internal enum class BleRecoveryState { ACTIVE, TIMED_OUT, USER_PAUSED }

/** Local top-metric warning about this watch's own direct BLE links only. */
enum class BleLinkWarning { NONE, DISCONNECTED, PAUSED }

/** Anonymous discovery transition: counts, reasons and timings only, never peer identity. */
internal data class BleDiscoveryRecord(
    val action: BleDiscoveryAction,
    val reason: BleDiscoveryReason,
    val bleConnections: Int,
    val radioAvailable: Boolean,
    val delayMs: Long = 0L,
    val backoffLevel: Int = 0
)

/**
 * Bounded BLE discovery windows with capped automatic idle retries.
 *
 * With no direct BLE link, each window lasts [WINDOW_MS]. When it expires the Ditto
 * BLE transport is disabled and one retry is scheduled after an idle gap from
 * [RETRY_DELAYS_MS]: nominal 30, 60, 90, then 120 seconds repeating. Each gap gets
 * +/-[RETRY_JITTER] jitter so co-located watches do not synchronise, clamped to
 * [RETRY_MAX_DELAY_MS], so actual gaps are 27-33, 54-66, 81-99, then 108-120 seconds
 * and never exceed 120 seconds. While disconnected the steady state is one 30-second
 * window per ~150 seconds (~20% nominal window duty), never continuous scanning.
 *
 * Retries reset to the shortest gap on service/gate start, eligible adapter ON,
 * UI/wrist-on/settings triggers, a direct BLE link appearing, and loss of the last
 * direct BLE link (which also opens a window immediately). LAN/topology churn and
 * retries themselves advance the ladder. Topology-only reopens keep the five-minute
 * cooldown after an expiry; explicit triggers, adapter ON, link loss and retries
 * bypass it. Adapter OFF, missing Bluetooth permissions, service gates and close
 * cancel all pending work. Rejected config writes never update internal state; a
 * rejected enable or stop is retried on the same ladder rather than dropped.
 *
 * V4 has no independent scanner gate: while at least one direct BLE link exists the
 * transport, including SDK discovery, stays enabled so other peers can still return.
 * One connected peer is not treated as proof that all peers have recovered.
 *
 * Automatic recovery has a hard [NO_LINK_BUDGET_MS] (30 minute) budget of zero direct
 * BLE links while discovery is active. When it runs out, any in-flight window or retry
 * is cancelled, BLE discovery stops (a rejected stop is retried) and the state becomes
 * TIMED_OUT with a local "Ditto BLE disconnected" warning. A retry whose jittered start
 * would land at or past the budget end is never scheduled. Only explicit Retry now, an
 * explicit Ditto off/on, or a real direct BLE link starts a new budget; UI, wrist-on,
 * settings, presence, gate and adapter events never override a timeout or a user
 * Pause retries. Budget is consumed only while eligible (Ditto allowed, adapter ON,
 * Bluetooth permissions) with zero direct BLE links; ineligible periods freeze it, and
 * automatic eligibility return resumes the remaining budget. The warning is shown only
 * while eligible, describes this watch's own BLE links, never mesh or all-peer health,
 * and pausing never disables Bluetooth, other Ditto transports, trust or data.
 * TIMED_OUT and USER_PAUSED persist across service, process and reboot; an ACTIVE budget
 * in use is checkpointed so recreation continues it (see [restoreBleBudget]).
 */
internal class DittoBleDiscovery(
    private val scope: CoroutineScope,
    private val setEnabled: (Boolean) -> Boolean,
    private val record: (BleDiscoveryRecord) -> Unit,
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime,
    private val jitter: () -> Double = { Random.nextDouble() },
    initialRecovery: BleRecoveryState = BleRecoveryState.ACTIVE,
    private val recoveryChanged: (BleRecoveryState) -> Unit = {},
    private val warningChanged: (BleLinkWarning) -> Unit = {},
    initialBudgetUsedMs: Long = 0L,
    private val budgetChanged: (BleBudgetSnapshot) -> Unit = {},
) : AutoCloseable {
    private enum class Timer { WINDOW, RETRY, STOP }

    private var allowed = false
    private var radioAvailable = true
    private var closed = false
    private var enabled = false
    private var bleConnections = 0
    private var topology: Set<String>? = null
    private var timer: Job? = null
    private var timerKind: Timer? = null
    private var generation = 0L
    private var topologyNotBefore = Long.MIN_VALUE
    private var retryLevel = 0
    private var recovery = initialRecovery
    private var budgetUsedMs = initialBudgetUsedMs.coerceIn(0L, NO_LINK_BUDGET_MS)
    private var budgetSince: Long? = null
    private var lastCheckpoint: Triple<BleRecoveryState, Boolean, Long>? = null
    private var budgetJob: Job? = null
    private var budgetGeneration = 0L
    private var warning = BleLinkWarning.NONE

    private val eligible get() = !closed && allowed && radioAvailable
    private val active get() = eligible && recovery == BleRecoveryState.ACTIVE

    @Synchronized
    fun setAllowed(value: Boolean) {
        if (closed || allowed == value) return
        allowed = value
        if (value) request(BleDiscoveryReason.STARTUP) else disable(BleDiscoveryReason.GATED)
        updateBudget(BleDiscoveryReason.GATED)
    }

    /** Adapter ON plus runtime Bluetooth permissions; false cancels all discovery work. */
    @Synchronized
    fun setRadioAvailable(value: Boolean) {
        if (closed || radioAvailable == value) return
        radioAvailable = value
        if (value) request(BleDiscoveryReason.RADIO_ON) else disable(BleDiscoveryReason.RADIO_OFF)
        updateBudget(if (value) BleDiscoveryReason.RADIO_ON else BleDiscoveryReason.RADIO_OFF)
    }

    /** Explicit user action: new 30-minute budget, shortest gap, immediate window if eligible. */
    @Synchronized
    fun retryNow() {
        if (closed) return
        resetBudget(BleRecoveryState.ACTIVE)
        retryLevel = 0
        if (eligible) {
            if (timerKind == Timer.WINDOW) cancelTimer()
            open(BleDiscoveryReason.RETRY_NOW)
        }
        updateBudget(BleDiscoveryReason.RETRY_NOW)
    }

    /**
     * Explicit user action: stop automatic BLE discovery until Retry now or a Ditto
     * off/on. Ignored while a direct BLE link exists so a working link is never torn down.
     * Bluetooth itself, trust, data and other Ditto transports are untouched.
     */
    @Synchronized
    fun pauseRetries() {
        if (closed || bleConnections > 0 || recovery == BleRecoveryState.USER_PAUSED) return
        pauseDiscovery(BleRecoveryState.USER_PAUSED, BleDiscoveryReason.USER_PAUSE)
    }

    /** Explicit Ditto off/on: discard any timeout or user pause. */
    @Synchronized
    fun resetRecovery() {
        if (closed) return
        resetBudget(BleRecoveryState.ACTIVE)
        retryLevel = 0
        updateBudget(BleDiscoveryReason.DITTO_RESET)
    }

    @Synchronized
    fun request(reason: BleDiscoveryReason) {
        if (!active) return
        if (reason == BleDiscoveryReason.PRESENCE && !enabled &&
            elapsedClock() < topologyNotBefore) return
        if (reason in RESETTING) retryLevel = 0
        open(reason)
    }

    @Synchronized
    fun peersChanged(connections: Set<String>, localBleConnections: Int) {
        if (closed) return
        val previous = topology
        val previousBle = bleConnections
        topology = connections
        bleConnections = localBleConnections
        if (bleConnections > 0 && (previousBle == 0 || recovery != BleRecoveryState.ACTIVE)) {
            // A real direct link ends any timeout or pause and refunds the budget.
            resetBudget(BleRecoveryState.ACTIVE)
        }
        when {
            bleConnections > 0 -> if (eligible) {
                if (timerKind != null) cancelTimer()
                retryLevel = 0
                open(BleDiscoveryReason.PRESENCE)
            }
            previousBle > 0 -> request(BleDiscoveryReason.BLE_LINK_LOST)
            previous != null && previous != connections -> request(BleDiscoveryReason.PRESENCE)
        }
        updateBudget(BleDiscoveryReason.BLE_LINK_GAINED)
    }

    private fun resetBudget(state: BleRecoveryState) {
        budgetGeneration++
        budgetJob?.cancel()
        budgetJob = null
        budgetSince = null
        budgetUsedMs = 0L
        setRecovery(state)
    }

    private fun setRecovery(state: BleRecoveryState) {
        if (recovery == state) return
        recovery = state
        recoveryChanged(state)
    }

    /**
     * Budget is consumed only while discovery is active with zero direct BLE links.
     * Ineligible periods freeze it, so gate or adapter churn can neither extend nor
     * refund it; LAN/topology churn and retries never touch it.
     */
    private fun updateBudget(reason: BleDiscoveryReason) {
        val counting = active && bleConnections == 0
        val since = budgetSince
        if (counting && since == null) {
            budgetSince = elapsedClock()
            val current = ++budgetGeneration
            val remaining = (NO_LINK_BUDGET_MS - budgetUsedMs).coerceAtLeast(0L)
            budgetJob = scope.launch {
                delay(remaining)
                budgetExpired(current)
            }
        } else if (!counting && since != null) {
            budgetUsedMs += (elapsedClock() - since).coerceAtLeast(0L)
            budgetSince = null
            budgetGeneration++
            budgetJob?.cancel()
            budgetJob = null
        }
        checkpoint(force = false)
        publishWarning(reason)
    }

    /**
     * Persists enough state for a recreated service to continue, not restart, the budget.
     * Emitted on state/counting/reset changes and on each window change while counting.
     * Nothing is emitted after close, so a recreated service charges the downtime since
     * the last checkpoint.
     */
    private fun checkpoint(force: Boolean) {
        if (closed) return
        val counting = budgetSince != null
        val key = Triple(recovery, counting, budgetUsedMs)
        if (!force && key == lastCheckpoint) return
        lastCheckpoint = key
        budgetChanged(BleBudgetSnapshot(recovery, NO_LINK_BUDGET_MS - remainingBudgetMs(), counting,
            elapsedClock()))
    }

    @Synchronized
    private fun budgetExpired(current: Long) {
        if (closed || current != budgetGeneration) return
        budgetJob = null
        budgetSince = null
        budgetUsedMs = NO_LINK_BUDGET_MS
        pauseDiscovery(BleRecoveryState.TIMED_OUT, BleDiscoveryReason.NO_LINK_TIMEOUT)
    }

    private fun pauseDiscovery(state: BleRecoveryState, reason: BleDiscoveryReason) {
        setRecovery(state)
        // Cancels any in-flight window or retry; a rejected stop is retried while paused.
        disable(reason)
        updateBudget(reason)
    }

    /** Visible only while eligible: Ditto off, Companion, power gates and adapter OFF hide it. */
    private fun publishWarning(reason: BleDiscoveryReason) {
        val next = when {
            !eligible -> BleLinkWarning.NONE
            recovery == BleRecoveryState.TIMED_OUT -> BleLinkWarning.DISCONNECTED
            recovery == BleRecoveryState.USER_PAUSED -> BleLinkWarning.PAUSED
            else -> BleLinkWarning.NONE
        }
        if (warning == next) return
        warning = next
        record(BleDiscoveryRecord(
            if (next == BleLinkWarning.NONE) BleDiscoveryAction.WARNING_OFF else BleDiscoveryAction.WARNING_ON,
            reason, bleConnections, radioAvailable
        ))
        warningChanged(next)
    }

    private fun open(reason: BleDiscoveryReason) {
        if (timerKind == Timer.WINDOW) return
        if (timerKind != null) cancelTimer()
        if (!changeEnabled(true, reason)) {
            scheduleRetry(BleDiscoveryReason.ENABLE_FAILED)
            return
        }
        if (bleConnections == 0) schedule(Timer.WINDOW, WINDOW_MS, ::expire)
    }

    private fun expire() {
        if (!active || bleConnections > 0) return
        if (changeEnabled(false, BleDiscoveryReason.WINDOW_EXPIRED)) {
            topologyNotBefore = elapsedClock() + TOPOLOGY_BACKOFF_MS
            scheduleRetry(BleDiscoveryReason.WINDOW_EXPIRED)
        } else {
            scheduleStop(BleDiscoveryReason.WINDOW_EXPIRED)
        }
    }

    private fun disable(reason: BleDiscoveryReason) {
        cancelTimer()
        if (!changeEnabled(false, reason)) scheduleStop(reason)
    }

    private fun scheduleRetry(reason: BleDiscoveryReason) {
        val level = retryLevel
        val delayMs = nextDelay()
        record(BleDiscoveryRecord(BleDiscoveryAction.BACKOFF, reason, bleConnections,
            radioAvailable, delayMs, level))
        // A jittered retry landing at or past the budget end would only be cancelled by
        // the timeout; never schedule it.
        if (delayMs >= remainingBudgetMs()) return
        schedule(Timer.RETRY, delayMs) { if (active) open(BleDiscoveryReason.RETRY) }
    }

    private fun remainingBudgetMs(): Long {
        val running = budgetSince?.let { (elapsedClock() - it).coerceAtLeast(0L) } ?: 0L
        return NO_LINK_BUDGET_MS - budgetUsedMs - running
    }

    private fun scheduleStop(reason: BleDiscoveryReason) {
        val level = retryLevel
        val delayMs = nextDelay()
        record(BleDiscoveryRecord(BleDiscoveryAction.BACKOFF, BleDiscoveryReason.STOP_FAILED,
            bleConnections, radioAvailable, delayMs, level))
        schedule(Timer.STOP, delayMs) {
            when {
                !active -> disable(reason)
                bleConnections == 0 -> expire()
            }
        }
    }

    private fun nextDelay(): Long {
        val base = RETRY_DELAYS_MS[retryLevel]
        retryLevel = minOf(retryLevel + 1, RETRY_DELAYS_MS.lastIndex)
        val spread = jitter().coerceIn(0.0, 1.0) * 2.0 - 1.0
        return (base * (1.0 + RETRY_JITTER * spread)).toLong().coerceAtMost(RETRY_MAX_DELAY_MS)
    }

    private fun schedule(kind: Timer, delayMs: Long, action: () -> Unit) {
        cancelTimer()
        val current = generation
        timerKind = kind
        timer = scope.launch {
            delay(delayMs)
            fire(current, action)
        }
    }

    @Synchronized
    private fun fire(current: Long, action: () -> Unit) {
        if (closed || current != generation) return
        timer = null
        timerKind = null
        action()
    }

    private fun cancelTimer() {
        generation++
        timer?.cancel()
        timer = null
        timerKind = null
    }

    private fun changeEnabled(value: Boolean, reason: BleDiscoveryReason): Boolean {
        if (enabled == value) return true
        if (!setEnabled(value)) return false
        enabled = value
        if (budgetSince != null) checkpoint(force = true)
        record(BleDiscoveryRecord(
            if (value) BleDiscoveryAction.START else BleDiscoveryAction.STOP,
            reason, bleConnections, radioAvailable, if (value && bleConnections == 0) WINDOW_MS else 0L,
            retryLevel
        ))
        return true
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        allowed = false
        cancelTimer()
        changeEnabled(false, BleDiscoveryReason.CLOSED)
        updateBudget(BleDiscoveryReason.CLOSED)
    }

    companion object {
        const val WINDOW_MS = 30_000L
        const val TOPOLOGY_BACKOFF_MS = 300_000L
        const val RETRY_JITTER = 0.1
        const val RETRY_MAX_DELAY_MS = 120_000L
        const val NO_LINK_BUDGET_MS = 30 * 60 * 1_000L
        val RETRY_DELAYS_MS = longArrayOf(30_000L, 60_000L, 90_000L, RETRY_MAX_DELAY_MS)
        private val RESETTING = setOf(
            BleDiscoveryReason.STARTUP, BleDiscoveryReason.UI, BleDiscoveryReason.WRIST_ON,
            BleDiscoveryReason.SETTINGS, BleDiscoveryReason.RADIO_ON, BleDiscoveryReason.BLE_LINK_LOST
        )
    }
}

/** [usedMs] includes the running interval at [elapsedMs] (elapsedRealtime). */
internal data class BleBudgetSnapshot(
    val state: BleRecoveryState,
    val usedMs: Long,
    val counting: Boolean,
    val elapsedMs: Long,
)

/** Version tag of the persisted ACTIVE budget record. */
internal const val BLE_BUDGET_RECORD = "v1"

/** Passed instead of a record when the stored value could not be read. */
internal const val BLE_BUDGET_UNREADABLE = "?"

/**
 * TIMED_OUT/USER_PAUSED persist by name and survive restarts and reboots. An ACTIVE
 * budget in use persists as `v1;ACTIVE;usedMs;counting;elapsedRealtimeMs;bootCount`
 * (bootCount -1 when unavailable); only a fresh, idle ACTIVE budget persists as nothing.
 */
internal fun encodeBleBudget(snapshot: BleBudgetSnapshot, bootCount: Int?): String? = when {
    snapshot.state != BleRecoveryState.ACTIVE -> snapshot.state.name
    snapshot.usedMs <= 0L && !snapshot.counting -> null
    else -> listOf(BLE_BUDGET_RECORD, BleRecoveryState.ACTIVE.name, snapshot.usedMs,
        if (snapshot.counting) 1 else 0, snapshot.elapsedMs, bootCount ?: -1).joinToString(";")
}

/** [corrupt]: a stored record existed but was unreadable, malformed or of an unknown version. */
internal data class BleBudgetRestore(val state: BleRecoveryState, val usedMs: Long, val corrupt: Boolean = false)

/**
 * Only an absent record (null) starts a fresh ACTIVE budget. Any other record that does
 * not parse exactly fails closed as USER_PAUSED with the budget spent, so corruption can
 * never grant a new 30-minute budget or bypass a pause; Retry now reinitialises it.
 *
 * Same boot (matching boot counts): a budget that was counting is charged for the whole
 * downtime, so service/process recreation never restarts it. Reboot (differing boot
 * counts): the used budget carries over without refund; reboot downtime is not charged
 * because elapsedRealtime restarted. Without boot identity on either side a reboot is
 * only detectable when elapsedRealtime went backwards (treated as a reboot as above);
 * otherwise the gap is charged as same-boot downtime. That can over-charge a reboot and
 * time out early, but never refunds, so accounting is conservative rather than exact.
 * A budget that is spent restores as TIMED_OUT without opening a window.
 */
internal fun restoreBleBudget(encoded: String?, nowElapsedMs: Long, bootCount: Int?): BleBudgetRestore {
    val budget = DittoBleDiscovery.NO_LINK_BUDGET_MS
    if (encoded == null) return BleBudgetRestore(BleRecoveryState.ACTIVE, 0L)
    val corrupt = BleBudgetRestore(BleRecoveryState.USER_PAUSED, budget, corrupt = true)
    if (encoded == BleRecoveryState.TIMED_OUT.name || encoded == BleRecoveryState.USER_PAUSED.name) {
        return BleBudgetRestore(BleRecoveryState.valueOf(encoded), budget)
    }
    val parts = encoded.split(';')
    if (parts.size != 6 || parts[0] != BLE_BUDGET_RECORD || parts[1] != BleRecoveryState.ACTIVE.name) return corrupt
    val storedUsed = parts[2].toLongOrNull()?.takeIf { it >= 0 } ?: return corrupt
    val counting = when (parts[3]) { "1" -> true; "0" -> false; else -> return corrupt }
    val elapsed = parts[4].toLongOrNull()?.takeIf { it >= 0 } ?: return corrupt
    val savedBoot = parts[5].toIntOrNull()?.takeIf { it >= -1 } ?: return corrupt
    var used = storedUsed.coerceAtMost(budget)
    val currentBoot = bootCount?.takeIf { it >= 0 }
    val sameBoot = if (savedBoot >= 0 && currentBoot != null) savedBoot == currentBoot
        else nowElapsedMs >= elapsed
    if (counting && sameBoot && nowElapsedMs >= elapsed) used += nowElapsedMs - elapsed
    return if (used >= budget) BleBudgetRestore(BleRecoveryState.TIMED_OUT, budget)
    else BleBudgetRestore(BleRecoveryState.ACTIVE, used)
}

internal fun deviceBootCount(context: Context): Int? = runCatching {
    android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
}.getOrNull()?.takeIf { it >= 0 }

internal data class BlePeerSnapshot(val topology: Set<String>, val localBleConnections: Int)

internal fun blePeerSnapshot(graph: DittoPresenceGraph): BlePeerSnapshot {
    val local = graph.localPeer.peerKeyString
    val connections = (graph.localPeer.connections + graph.remotePeers.flatMap { it.connections })
        .distinctBy { it.id }
    return BlePeerSnapshot(
        // SDK connection IDs and client/server roles churn for the same peer.
        // Only logical endpoint/transport changes are discovery triggers.
        connections.map {
            "${minOf(it.peerKeyString1, it.peerKeyString2)}:" +
                "${maxOf(it.peerKeyString1, it.peerKeyString2)}:${it.connectionType}"
        }.toSet(),
        connections.count {
            it.connectionType == DittoConnectionType.Bluetooth &&
                (it.peerKeyString1 == local || it.peerKeyString2 == local)
        }
    )
}

/** Adapter enabled and Ditto's runtime Bluetooth permissions granted. */
internal fun bleRadioAvailable(context: Context): Boolean {
    if (listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }) return false
    return try {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true
    } catch (_: SecurityException) {
        false
    }
}

/** Owns event registrations only while DittoService owns sync, independently of diagnostics. */
internal class DittoBleDiscoverySession(
    private val application: Application,
    private val repo: DataRepository,
    private val ditto: Ditto,
    private val scope: CoroutineScope,
    private val allowed: Flow<Boolean>,
    elapsedClock: () -> Long = SystemClock::elapsedRealtime,
    private val transportLock: Any = Any(),
    private val transportChanged: (live.ditto.transports.DittoTransportConfig) -> Unit = {},
    private val radioProbe: () -> Boolean = { bleRadioAvailable(application) },
    jitter: () -> Double = { Random.nextDouble() },
    persistedRecovery: String? = null,
    private val warningChanged: (BleLinkWarning) -> Unit = repo::setDittoBleLinkWarning,
    private val bootCount: () -> Int? = { deviceBootCount(application) },
) : Application.ActivityLifecycleCallbacks, AutoCloseable {
    private val jobs = mutableListOf<Job>()
    private val graphs = Channel<DittoPresenceGraph>(Channel.CONFLATED)
    // Ordered, unconflated: a fast OFF->ON toggle must still reach discovery as OFF then ON.
    private val radioEvents = Channel<Boolean>(Channel.UNLIMITED)
    // Single writer, latest state wins; drained after close so the final state persists.
    private val recoveryWrites = Channel<BleBudgetSnapshot>(Channel.CONFLATED)
    private var observer: DittoPresenceObserver? = null
    private var receiverRegistered = false
    private val restored = restoreBleBudget(persistedRecovery, elapsedClock(), bootCount())
    private val discovery = DittoBleDiscovery(
        scope, ::setBleEnabled, ::recordTransition, elapsedClock, jitter,
        restored.state, {}, { warningChanged(it) }, restored.usedMs, { recoveryWrites.trySend(it) }
    )

    init {
        if (restored.corrupt) {
            Log.w(TAG, "BLE recovery state unreadable; discovery paused until Retry now")
            // Replace the bad record with a well-formed paused one.
            recoveryWrites.trySend(BleBudgetSnapshot(restored.state, restored.usedMs, false, elapsedClock()))
        }
    }

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> radioEvents.trySend(false)
                BluetoothAdapter.STATE_ON -> radioEvents.trySend(true)
            }
        }
    }

    fun start() {
        scope.launch {
            for (snapshot in recoveryWrites) {
                try {
                    val value = encodeBleBudget(snapshot, bootCount())
                    // An explicit Ditto disable clears the state; a late write must not restore it.
                    if (value != null && !repo.getEnableDittoService.first()) continue
                    repo.storeDittoBleRecoveryState(value)
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Log.e(TAG, "Could not persist BLE recovery state", error)
                }
            }
        }
        discovery.setRadioAvailable(radioProbe())
        updatePeers(ditto.presence.graph)
        jobs += scope.launch {
            for (graph in graphs) updatePeers(graph)
        }
        jobs += scope.launch {
            // true re-probes adapter and permissions; false is an adapter OFF transition.
            for (on in radioEvents) discovery.setRadioAvailable(on && radioProbe())
        }
        // The SDK invokes observers under its presence lock; never write config on that callback.
        observer = ditto.presence.observe { graph: DittoPresenceGraph -> graphs.trySend(graph); Unit }
        application.registerActivityLifecycleCallbacks(this)
        application.registerReceiver(
            adapterReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            Context.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
        jobs += scope.launch {
            allowed.distinctUntilChanged().collect { discovery.setAllowed(it) }
        }
        jobs += scope.launch {
            repo.latestDeviceWornObservation.collect {
                if (it == true) discovery.request(BleDiscoveryReason.WRIST_ON)
            }
        }
        jobs += scope.launch {
            repo.dittoBleRecoveryActions.collect {
                when (it) {
                    DataRepository.DittoBleRecoveryAction.RETRY_NOW -> discovery.retryNow()
                    DataRepository.DittoBleRecoveryAction.PAUSE -> discovery.pauseRetries()
                }
            }
        }
        jobs += scope.launch {
            repo.getEnableDittoService.distinctUntilChanged().drop(1).collect {
                if (!it) discovery.resetRecovery()
            }
        }
        jobs += scope.launch {
            combine(
                repo.getDittoAppId, repo.getDittoAuthToken,
                repo.getDittoPdlSource, repo.getDittoCustomPdl
            ) { appId, token, source, pdl -> listOf(appId, token, source.name, pdl) }
                .distinctUntilChanged().drop(1).collect {
                    discovery.request(BleDiscoveryReason.SETTINGS)
                }
        }
    }

    private fun updatePeers(graph: DittoPresenceGraph) {
        val snapshot = blePeerSnapshot(graph)
        discovery.peersChanged(snapshot.topology, snapshot.localBleConnections)
    }

    private fun recordTransition(event: BleDiscoveryRecord) {
        Log.i(TAG, "BLE discovery ${event.action} reason=${event.reason} " +
            "radio=${if (event.radioAvailable) "on" else "off"} bleLinks=${event.bleConnections} " +
            "delayMs=${event.delayMs} level=${event.backoffLevel}")
        AppDiagnostics.record(DiagnosticEvent.BLE_DISCOVERY, mapOf(
            DiagnosticMetric.KIND to event.action.ordinal.toLong(),
            DiagnosticMetric.REASON to event.reason.ordinal.toLong(),
            DiagnosticMetric.CONNECTED_COUNT to event.bleConnections.toLong(),
            DiagnosticMetric.WINDOW_MS to DittoBleDiscovery.WINDOW_MS,
            DiagnosticMetric.DELAY_MS to event.delayMs,
            DiagnosticMetric.BACKOFF_LEVEL to event.backoffLevel.toLong(),
            DiagnosticMetric.STATUS to if (event.radioAvailable) 1L else 0L,
            DiagnosticMetric.RESULT to 1L
        ))
    }

    private fun setBleEnabled(enabled: Boolean): Boolean {
        // Diagnostics describe requested SDK transport state, not hardware scan callbacks.
        return synchronized(transportLock) {
            try {
                val config = ditto.transportConfig.clone()
                config.peerToPeer.bluetoothLe.enabled = enabled
                ditto.transportConfig = config
                transportChanged(config)
                true
            } catch (error: DittoError) {
                Log.e(TAG, "Failed to change Ditto BLE transport (enabled=$enabled)", error)
                AppDiagnostics.record(DiagnosticEvent.BLE_DISCOVERY, mapOf(
                    DiagnosticMetric.ENABLED to if (enabled) 1L else 0L,
                    DiagnosticMetric.RESULT to 0L,
                    DiagnosticMetric.FAILURES to 1L
                ))
                false
            }
        }
    }

    fun onUiOpened() {
        scope.launch { discovery.request(BleDiscoveryReason.UI) }
    }

    override fun onActivityResumed(activity: Activity) {
        // Permission grants arrive through UI without a broadcast.
        radioEvents.trySend(true)
        onUiOpened()
    }

    override fun close() {
        discovery.close()
        recoveryWrites.close()
        graphs.close()
        radioEvents.close()
        application.unregisterActivityLifecycleCallbacks(this)
        if (receiverRegistered) {
            receiverRegistered = false
            try {
                application.unregisterReceiver(adapterReceiver)
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "Bluetooth adapter receiver was not registered", error)
            }
        }
        observer?.close()
        observer = null
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private companion object {
        const val TAG = "WearTAK-DittoBle"
    }
}
