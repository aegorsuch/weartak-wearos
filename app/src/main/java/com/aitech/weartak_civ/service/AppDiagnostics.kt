package com.aitech.weartak_civ.service

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.annotation.Keep
import com.aitech.weartak_civ.BuildConfig
import com.aitech.weartak_civ.data.DataRepository
import com.google.gson.Gson
import com.google.gson.JsonParseException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

@Keep
enum class DiagnosticEvent {
    ENABLED, DISABLED, PROCESS_START, JAVA_CRASH, ACTIVITY_RESUMED, ACTIVITY_STOPPED,
    BATTERY, LOCATION_REQUEST, LOCATION_ACCEPTED, LOCATION_REJECTED, LOCATION_TIMEOUT,
    LOCATION_FAILURE, LOCATION_COALESCED, LOCATION_STOPPED, OBSERVATION_SCHEDULED, REPORT_POLICY,
    CONFIGURATION, SPEED_VALIDITY,
    PLI_ATTEMPT, PLI_RESULT, POWER_CHANGED, WAKE_LOCK, CONNECTIVITY, SYNC, COMMAND,
    CHAT, SENSOR, SERVICE_REQUEST, APP_EVENT, SUBSCRIBER_FAILURE, QUEUE_OVERFLOW,
    DISPLAY_INTERACTIVE, ROTATION_LISTENER, PROCESSING_WORK,
    NETWORK_LIFECYCLE, TAK_CONNECTION, TAK_CONNECTION_COUNTS, CONNECTIVITY_PATH,
    SAMSUNG_HR_CONTINUOUS, STAY_AWAKE_AUTO_DISABLED, STAY_AWAKE_START_REFUSED,
    MOTION_CONFIRMATION, POWER_CONTEXT, DEVICE_SETTING, RESOURCE_LIFETIME,
    MISSION_LIST, // RESULT: 0 paused, 1 fetched, 2 unchanged, 3 failed
    OFF_BODY_TAK,
    BLE_DISCOVERY, SPO2_SKIPPED_MOTION,
    DITTO_LIFECYCLE, DITTO_STATE, DITTO_TRANSPORTS, DITTO_PEERS, DITTO_SDK_LOG
}

@Keep
enum class DiagnosticMetric {
    SOURCE, KIND, DURATION_MS, INTERVAL_SECONDS, REPORT_SECONDS, OBSERVATION_SECONDS,
    BATTERY_PERCENT, DOCK, CHARGING, RESULT, SCREEN, COUNT, AGGREGATED, ACTIVE,
    POWER_THROTTLED, DOCK_THROTTLED, FOOT_SECONDS, VEHICLE_SECONDS, STATIONARY_SECONDS,
    ALERT_SECONDS, CONSTANT_SECONDS, DYNAMIC, LOCATION_ENABLED, PHYSIO_ENABLED, HR_ENABLED,
    ACTIVITY, REPORT_REASON, OBSERVATION_REASON,
    BYTES, FRAMES, DURATION_US, MAX_DURATION_US, FAILURES, WINDOW_MS,
    GENERATION, ATTEMPT, DELAY_MS, STATUS, VALIDATED, INTERNET, TRANSPORT,
    CONNECTED_COUNT, CONNECTING_COUNT, SOCKET_COUNT, UI_CONNECTED, UI_CONNECTING,
    ENABLED, CONFIGURED_COUNT, MODE, GROUP_SELECTED, REQUESTED, LOCK_HELD, IDLE_RELEASED,
    BACKOFF_LEVEL, PRIORITY, MAX_AGE_MS,
    COALESCED, SKIPPED, REASON,
    CONTEXT_VERSION, CAPTURE_ELAPSED_MS, UPTIME_MS, PREVIOUS_ELAPSED_MS,
    ELAPSED_DELTA_MS, UPTIME_DELTA_MS, ASLEEP_MS, ASLEEP_BASIS_POINTS,
    NEVER_SUSPENDED, SHORT_INTERVAL, CLOCK_STATUS, CLOCK_SKEW_MS,
    IDLE, LIGHT_IDLE, INTERACTIVE, DISPLAY_STATE, SAVER, CURRENT_UA, CURRENT_STATUS,
    CHARGE_UAH, CHARGE_STATUS, SETTING_ID, NAMESPACE, READ_STATUS, VALUE,
    SEMANTICS_VERIFIED, OBSERVER_AVAILABLE, OBSERVATION_FAILURES,
    RESOURCE_ID, RESOURCE_CLASS, TOKEN, ACTION, START_MS, TIMEOUT_MS, PERIOD_US,
    UNION_MS, FIRST_MS, LAST_MS, SUPPRESSED
}

internal enum class ProcessingStage(val code: Long) {
    TCP_READ(0), TCP_FRAMING(1), TCP_INGEST(2), PLUGIN_OFFER(3), USER_STORE(4), MARKER_STORE(5),
    SA_DEFERRED(6)
}

@Keep
internal data class DiagnosticRecord(
    val schema: Int = 1,
    val version: String = BuildConfig.VERSION_NAME,
    val versionCode: Int = BuildConfig.VERSION_CODE,
    val sourceCommit: String = BuildConfig.GIT_COMMIT_SHORT,
    val policy: String = "hybrid-motion-confirm-v3",
    val wallTimeMs: Long,
    val elapsedMs: Long,
    val event: DiagnosticEvent,
    val metrics: Map<DiagnosticMetric, Long>
)

internal class DiagnosticJournal(
    private val directory: () -> File,
    private val maxBytes: Long = 2 * 1024 * 1024L,
    private val retentionMs: Long = 3 * 24 * 60 * 60 * 1000L,
    private val wallClock: () -> Long = System::currentTimeMillis
) {
    private val gson = Gson()
    private val firstTimestamp = mutableMapOf<String, Long>()

    private fun parseRecord(line: String): DiagnosticRecord {
        try {
            val record = gson.fromJson(line, DiagnosticRecord::class.java)
                ?: throw IOException("Diagnostics file contains an empty record")
            if (record.schema != 1 || record.wallTimeMs <= 0 || record.elapsedMs < 0)
                throw IOException("Diagnostics file contains invalid metadata")
            return record
        } catch (error: JsonParseException) {
            throw IOException("Diagnostics file is incomplete; retrieve original JSONL files", error)
        }
    }

    private fun firstWallTime(file: File): Long = firstTimestamp.getOrPut(file.name) {
        if (file.length() > maxBytes) throw IOException("Diagnostics file exceeds storage limit")
        file.bufferedReader().use { reader ->
            reader.readLine()?.let { parseRecord(it).wallTimeMs } ?: wallClock()
        }
    }

    private fun files(prune: Boolean = true): List<File> {
        val root = directory()
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Diagnostics storage unavailable")
        return (2 downTo 0).map { File(root, if (it == 0) "events.jsonl" else "events.$it.jsonl") }
            .also { files ->
                files.filter { prune && it.exists() && wallClock() - firstWallTime(it) > retentionMs }.forEach {
                    if (!it.delete()) throw IOException("Cannot expire diagnostics")
                    firstTimestamp.remove(it.name)
                }
            }
    }

    @Synchronized
    fun append(record: DiagnosticRecord) {
        val line = gson.toJson(record) + "\n"
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (record.event in contextEvents && bytes.size > 2048)
            throw IOException("Diagnostics context event exceeds 2 KiB")
        if (bytes.size > maxBytes) throw IOException("Diagnostics event exceeds storage limit")
        val files = files()
        if (files.last().length() + bytes.size > maxBytes) {
            if (files.first().exists() && !files.first().delete()) throw IOException("Cannot rotate diagnostics")
            for (index in 1 until files.size) {
                val source = files[index]
                if (source.exists() && !source.renameTo(files[index - 1])) throw IOException("Cannot rotate diagnostics")
            }
            firstTimestamp.clear()
        }
        if (!files.last().exists()) firstTimestamp[files.last().name] = record.wallTimeMs
        files.last().appendBytes(bytes)
    }

    @Synchronized
    fun exportJson(): String {
        val records = files().filter { it.exists() }.flatMap { file ->
            if (file.length() > maxBytes) throw IOException("Diagnostics file exceeds storage limit")
            file.useLines { lines -> lines.map(::parseRecord).toList() }
        }
        return gson.toJson(mapOf("schema" to 1, "events" to records))
    }

    @Synchronized
    fun clear() {
        files(prune = false).filter { it.exists() }.forEach {
            if (!it.delete()) throw IOException("Cannot clear diagnostics")
        }
        firstTimestamp.clear()
    }

    companion object {
        internal val contextEvents = setOf(DiagnosticEvent.POWER_CONTEXT,
            DiagnosticEvent.DEVICE_SETTING, DiagnosticEvent.RESOURCE_LIFETIME)
    }
}

/**
 * Only compiled event names and numeric, allowlisted metrics enter the journal. Never pass
 * payloads, exception messages, locations, peer identifiers or network addresses to this API.
 */
class AppDiagnostics internal constructor(private val application: Application) :
    Application.ActivityLifecycleCallbacks {
    private val preferences = application.getSharedPreferences("app_diagnostics", Context.MODE_PRIVATE)
    private val mutableEnabled = MutableStateFlow(preferences.getBoolean("enabled", false))
    val enabled = mutableEnabled.asStateFlow()
    private val mutableFailure = MutableStateFlow(false)
    val storageFailed = mutableFailure.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private sealed interface Pending {
        data class Write(val generation: Long, val record: DiagnosticRecord) : Pending
        data class Barrier(val completion: CompletableDeferred<Unit>) : Pending
    }
    private val queue = Channel<Pending>(128)
    private val generation = AtomicLong()
    private val dropped = AtomicLong()
    private val storageLock = Any()
    private val rateLock = Any()
    private data class Rate(val at: Long, val metrics: Map<DiagnosticMetric, Long>, var skipped: Long = 0)
    private data class RateKey(
        val event: DiagnosticEvent, val kind: Long?, val source: Long?,
        val active: Long?, val result: Long?, val dock: Long?, val charging: Long?,
        val activity: Long?, val reportReason: Long?, val observationReason: Long?,
        val powerThrottled: Long?, val dockThrottled: Long?
    )
    private val rates = mutableMapOf<RateKey, Rate>()
    private val contextBudget = ContextDetailBudget()
    private val powerContext = PowerContextDiagnostics(application, ::captureContext)
    private class Work {
        var at = 0L
        var count = 0L
        var bytes = 0L
        var frames = 0L
        var durationUs = 0L
        var maxDurationUs = 0L
        var failures = 0L
        var coalesced = 0L
        var skipped = 0L

        fun reset() {
            at = 0L
            count = 0L
            bytes = 0L
            frames = 0L
            durationUs = 0L
            maxDurationUs = 0L
            failures = 0L
            coalesced = 0L
            skipped = 0L
        }
    }
    private val work = Array(ProcessingStage.entries.size) { Work() }
    private val journal = DiagnosticJournal({
        application.getExternalFilesDir("diagnostics") ?: throw IOException("Diagnostics storage unavailable")
    })
    private var previousCrashHandler: Thread.UncaughtExceptionHandler? = null
    private var crashHandler: Thread.UncaughtExceptionHandler? = null

    init {
        scope.launch {
            for (pending in queue) {
                if (pending is Pending.Barrier) {
                    pending.completion.complete(Unit)
                    continue
                }
                val write = pending as Pending.Write
                synchronized(storageLock) {
                    if (enabled.value && write.generation == generation.get()) {
                        if (storageFailed.value) {
                            dropped.incrementAndGet()
                            return@synchronized
                        }
                        storage {
                            val lost = dropped.getAndSet(0)
                            if (lost > 0) journal.append(record(DiagnosticEvent.QUEUE_OVERFLOW,
                                mapOf(DiagnosticMetric.COUNT to lost)))
                            journal.append(write.record)
                        }
                    }
                }
            }
        }
        scope.launch {
            enabled.collectLatest { recording ->
                if (!recording) return@collectLatest
                combine(DittoService.syncActive, DittoService.powerSuppressed,
                    DittoService.isReconfiguring) { active, suppressed, changing ->
                    mapOf(
                        DiagnosticMetric.ACTIVE to if (active) 1L else 0L,
                        DiagnosticMetric.SUPPRESSED to if (suppressed) 1L else 0L,
                        DiagnosticMetric.REQUESTED to if (changing) 1L else 0L
                    )
                }.distinctUntilChanged().collect { capture(DiagnosticEvent.DITTO_STATE, it) }
            }
        }
        if (enabled.value) {
            attach()
            enqueue(DiagnosticEvent.PROCESS_START)
            recordSamsungHrState()
            startContext()
        }
    }

    private fun record(event: DiagnosticEvent, metrics: Map<DiagnosticMetric, Long> = emptyMap()) =
        DiagnosticRecord(wallTimeMs = System.currentTimeMillis(), elapsedMs = SystemClock.elapsedRealtime(),
            event = event, metrics = metrics.toMap())

    private fun storage(action: () -> Unit) {
        try {
            action()
            mutableFailure.value = false
        } catch (error: IOException) {
            storageFailure(error)
        } catch (error: SecurityException) {
            storageFailure(error)
        }
    }

    private fun storageFailure(error: Exception) {
        if (!mutableFailure.value) Log.e("WearTAK-Diagnostics", "Diagnostics storage failed", error)
        mutableFailure.value = true
    }

    private fun attach() {
        application.registerActivityLifecycleCallbacks(this)
        EventBus.getDefault().register(this)
        previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
        val inherited = previousCrashHandler ?: return
        lateinit var handler: Thread.UncaughtExceptionHandler
        handler = Thread.UncaughtExceptionHandler { thread, error ->
            // Each installation captures its predecessor; later third-party wrappers cannot form a cycle.
            try {
                synchronized(storageLock) {
                    if (enabled.value && crashHandler === handler)
                        storage { journal.append(record(DiagnosticEvent.JAVA_CRASH)) }
                }
            } finally {
                inherited.uncaughtException(thread, error)
            }
        }
        crashHandler = handler
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }

    private fun detach() {
        application.unregisterActivityLifecycleCallbacks(this)
        EventBus.getDefault().unregister(this)
        if (Thread.getDefaultUncaughtExceptionHandler() === crashHandler)
            Thread.setDefaultUncaughtExceptionHandler(previousCrashHandler)
        crashHandler = null
    }

    internal fun bindRepository(repo: DataRepository) {
        scope.launch {
            enabled.collectLatest { recording ->
                if (!recording) return@collectLatest
                val fields = listOf(
                    DiagnosticMetric.FOOT_SECONDS to repo.getOnFootReportingInterval.map { it.toLong() },
                    DiagnosticMetric.VEHICLE_SECONDS to repo.getVehicleReportingInterval.map { it.toLong() },
                    DiagnosticMetric.STATIONARY_SECONDS to repo.getStationaryReportingInterval.map { it.toLong() },
                    DiagnosticMetric.ALERT_SECONDS to repo.getWhileAlertingReportingInterval.map { it.toLong() },
                    DiagnosticMetric.CONSTANT_SECONDS to repo.getConstantReportingInterval.map { it.toLong() },
                    DiagnosticMetric.DYNAMIC to repo.getEnableDynamicReportingStrategy.map { if (it) 1L else 0L },
                    DiagnosticMetric.SOURCE to repo.getLocationInput.map { it?.ordinal?.toLong() ?: -1L },
                    DiagnosticMetric.LOCATION_ENABLED to repo.getEnableLocationService.map { if (it) 1L else 0L },
                    DiagnosticMetric.PHYSIO_ENABLED to repo.getEnablePhysiologicalServices.map { if (it) 1L else 0L },
                    DiagnosticMetric.HR_ENABLED to repo.getEnableHeartRateService.map { if (it) 1L else 0L }
                )
                combine(fields.map { it.second }) { values ->
                    fields.mapIndexed { index, field -> field.first to values[index] }.toMap()
                }.distinctUntilChanged().catch { error ->
                    when (error) {
                        is IOException -> storageFailure(error)
                        is SecurityException -> storageFailure(error)
                        else -> throw error
                    }
                }.collect { configuration ->
                    capture(DiagnosticEvent.CONFIGURATION, configuration)
                    val battery = mapOf(
                        DiagnosticMetric.DOCK to repo.dockType.value.ordinal.toLong(),
                        DiagnosticMetric.CHARGING to if (repo.chargingState.value) 1L else 0L
                    ) + repo.latestBatteryPercentage.value?.let {
                        mapOf(DiagnosticMetric.BATTERY_PERCENT to it.toLong())
                    }.orEmpty()
                    capture(DiagnosticEvent.BATTERY, battery)
                }
            }
        }
    }

    internal fun bindConnectivity(repo: DataRepository,
        plugins: com.aitech.weartak_civ.integration.plugin.PluginSettingsCoordinator) {
        ConnectivityPathDiagnostics(scope, enabled, repo, plugins,
            { capture(DiagnosticEvent.CONNECTIVITY_PATH, it) },
            powerSnapshot = {
                DiagnosticResources.flush(observeActive = true)
                powerContext.snapshot()
            })
    }

    // Call storage/settings operations on IO, like the existing diagnostic export path.
    suspend fun setEnabled(value: Boolean) {
        if (!value) {
            powerContext.stop()
            DiagnosticResources.stop()
        }
        if (!value) awaitPendingWrites()
        synchronized(storageLock) {
            if (enabled.value == value) return
            if (!preferences.edit().putBoolean("enabled", value).commit())
                throw IOException("Cannot save Diagnostics preference")
            synchronized(rateLock) {
                if (!value) {
                    storage {
                        flushAggregates()
                        journal.append(record(DiagnosticEvent.DISABLED))
                    }
                }
                generation.incrementAndGet()
                mutableEnabled.value = value
                rates.clear()
                work.forEach { it.reset() }
                contextBudget.reset()
            }
            if (value) {
                attach()
                enqueue(DiagnosticEvent.ENABLED)
                recordSamsungHrState()
            } else detach()
        }
        if (value) startContext()
    }

    private fun startContext() {
        DiagnosticResources.start(resourceEmitter())
        powerContext.start()
    }

    private fun resourceEmitter(): (DiagnosticEvent, Map<DiagnosticMetric, Long>) -> Boolean {
        val epoch = generation.get()
        return { event, metrics -> captureContextAt(epoch, event, metrics) }
    }

    private fun captureContext(event: DiagnosticEvent, metrics: Map<DiagnosticMetric, Long>) =
        captureContextAt(generation.get(), event, metrics)

    private fun captureContextAt(epoch: Long, event: DiagnosticEvent, metrics: Map<DiagnosticMetric, Long>): Boolean {
        if (!enabled.value) return false
        synchronized(rateLock) {
            if (!enabled.value || generation.get() != epoch) return false
            val now = SystemClock.elapsedRealtime()
            val values = metrics + (DiagnosticMetric.CONTEXT_VERSION to 1L)
            if (!contextBudget.admit(event, values, now)) return false
            if (event == DiagnosticEvent.POWER_CONTEXT) {
                contextBudget.flush().forEach { (summaryEvent, summary) ->
                    if (queue.trySend(Pending.Write(generation.get(), record(summaryEvent, summary))).isFailure)
                        dropped.incrementAndGet()
                }
            }
            val accepted = queue.trySend(Pending.Write(generation.get(), record(event, values))).isSuccess
            if (!accepted) dropped.incrementAndGet()
            return accepted
        }
    }

    private fun recordSamsungHrState() = capture(DiagnosticEvent.SAMSUNG_HR_CONTINUOUS, mapOf(
        DiagnosticMetric.ENABLED to if (BuildConfig.SAMSUNG_HRV_ENABLED) 1L else 0L,
        DiagnosticMetric.ACTIVE to if (samsungHrContinuousRunning) 1L else 0L
    ))

    private fun enqueue(event: DiagnosticEvent, metrics: Map<DiagnosticMetric, Long> = emptyMap()) {
        if (queue.trySend(Pending.Write(generation.get(), record(event, metrics))).isFailure) dropped.incrementAndGet()
    }

    private fun capture(event: DiagnosticEvent, metrics: Map<DiagnosticMetric, Long>) {
        if (!enabled.value) return
        synchronized(rateLock) {
            if (!enabled.value) return
            if (event == DiagnosticEvent.CONNECTIVITY_PATH || event == DiagnosticEvent.SAMSUNG_HR_CONTINUOUS ||
                event == DiagnosticEvent.STAY_AWAKE_AUTO_DISABLED || event == DiagnosticEvent.STAY_AWAKE_START_REFUSED) {
                enqueue(event, metrics + (DiagnosticMetric.COUNT to 1L))
                return
            }
            val now = SystemClock.elapsedRealtime()
            val key = RateKey(event, metrics[DiagnosticMetric.KIND], metrics[DiagnosticMetric.SOURCE],
                metrics[DiagnosticMetric.ACTIVE], metrics[DiagnosticMetric.RESULT],
                metrics[DiagnosticMetric.DOCK], metrics[DiagnosticMetric.CHARGING],
                metrics[DiagnosticMetric.ACTIVITY], metrics[DiagnosticMetric.REPORT_REASON],
                metrics[DiagnosticMetric.OBSERVATION_REASON], metrics[DiagnosticMetric.POWER_THROTTLED],
                metrics[DiagnosticMetric.DOCK_THROTTLED])
            val prior = rates[key]
            if (prior != null && now - prior.at < 5_000L) {
                rates[key] = prior.copy(metrics = metrics.toMap(), skipped = prior.skipped + 1)
                return
            }
            if (prior == null && rates.size >= 256) {
                dropped.incrementAndGet()
                return
            }
            rates[key] = Rate(now, metrics.toMap())
            enqueue(event, metrics + (DiagnosticMetric.COUNT to (1 + (prior?.skipped ?: 0))))
        }
    }

    internal inline fun <T> measureProcessing(
        stage: ProcessingStage,
        bytes: (T) -> Long = { 0L },
        frames: (T) -> Long = { 0L },
        block: () -> T,
    ): T {
        if (!enabled.value) return block()
        val epoch = generation.get()
        val start = SystemClock.elapsedRealtimeNanos()
        var completed = false
        var transferred = 0L
        var produced = 0L
        try {
            val result = block()
            transferred = bytes(result)
            produced = frames(result)
            completed = true
            return result
        } finally {
            captureProcessing(stage, epoch, transferred, produced,
                (SystemClock.elapsedRealtimeNanos() - start).coerceAtLeast(0L) / 1_000L,
                if (completed) 0L else 1L)
        }
    }

    private fun captureProcessing(
        stage: ProcessingStage, epoch: Long, bytes: Long, frames: Long, durationUs: Long, failures: Long,
        coalesced: Long = 0, skipped: Long = 0, count: Long = 1,
    ) {
        synchronized(rateLock) {
            if (!enabled.value || generation.get() != epoch) return
            val now = SystemClock.elapsedRealtime()
            val total = work[stage.ordinal]
            if (total.count == 0L && total.coalesced == 0L && total.skipped == 0L) total.at = now
            fun add(a: Long, b: Long) = a + b.coerceIn(0L, Long.MAX_VALUE - a)
            total.count = add(total.count, count)
            total.bytes = add(total.bytes, bytes)
            total.frames = add(total.frames, frames)
            total.durationUs = add(total.durationUs, durationUs)
            total.maxDurationUs = maxOf(total.maxDurationUs, durationUs)
            total.failures = add(total.failures, failures)
            total.coalesced = add(total.coalesced, coalesced)
            total.skipped = add(total.skipped, skipped)
            if (now - total.at >= 30_000L) {
                enqueue(DiagnosticEvent.PROCESSING_WORK, processingMetrics(stage, total, now))
                total.reset()
            }
        }
    }

    private fun processingMetrics(stage: ProcessingStage, total: Work, now: Long) = mapOf(
        DiagnosticMetric.KIND to stage.code,
        DiagnosticMetric.COUNT to total.count,
        DiagnosticMetric.BYTES to total.bytes,
        DiagnosticMetric.FRAMES to total.frames,
        DiagnosticMetric.DURATION_US to total.durationUs,
        DiagnosticMetric.MAX_DURATION_US to total.maxDurationUs,
        DiagnosticMetric.FAILURES to total.failures,
        DiagnosticMetric.COALESCED to total.coalesced,
        DiagnosticMetric.SKIPPED to total.skipped,
        DiagnosticMetric.WINDOW_MS to (now - total.at).coerceAtLeast(0L),
        DiagnosticMetric.AGGREGATED to 1L,
    )

    internal fun flushProcessing() {
        synchronized(rateLock) {
            if (!enabled.value) return
            val now = SystemClock.elapsedRealtime()
            ProcessingStage.entries.forEach { stage ->
                val total = work[stage.ordinal]
                if (total.count > 0 || total.coalesced > 0 || total.skipped > 0) {
                    enqueue(DiagnosticEvent.PROCESSING_WORK, processingMetrics(stage, total, now))
                    total.reset()
                }
            }
        }
    }

    private fun flushAggregates() {
        val lost = dropped.getAndSet(0)
        if (lost > 0) journal.append(record(DiagnosticEvent.QUEUE_OVERFLOW,
            mapOf(DiagnosticMetric.COUNT to lost)))
        val pending = synchronized(rateLock) {
            val pending = rates.filterValues { it.skipped > 0 }.map { (key, rate) ->
                record(key.event, rate.metrics +
                    mapOf(DiagnosticMetric.COUNT to rate.skipped, DiagnosticMetric.AGGREGATED to 1L))
            }.toMutableList()
            rates.clear()
            contextBudget.flush().forEach { (event, metrics) -> pending.add(record(event, metrics)) }
            val now = SystemClock.elapsedRealtime()
            ProcessingStage.entries.forEach { stage ->
                val total = work[stage.ordinal]
                if (total.count > 0 || total.coalesced > 0 || total.skipped > 0)
                    pending.add(record(DiagnosticEvent.PROCESSING_WORK, processingMetrics(stage, total, now)))
                total.reset()
            }
            pending
        }
        pending.forEach(journal::append)
    }

    private suspend fun awaitPendingWrites() {
        val barrier = CompletableDeferred<Unit>()
        queue.send(Pending.Barrier(barrier))
        barrier.await()
    }

    suspend fun exportJson(): String {
        DiagnosticResources.flush()
        awaitPendingWrites()
        return synchronized(storageLock) {
            try {
                flushAggregates()
                journal.exportJson().also { mutableFailure.value = false }
            } catch (error: IOException) {
                storageFailure(error)
                throw error
            } catch (error: SecurityException) {
                storageFailure(error)
                throw error
            }
        }
    }

    fun clear() {
        powerContext.reset {
            synchronized(storageLock) {
                synchronized(rateLock) {
                    generation.incrementAndGet()
                    dropped.set(0)
                    rates.clear()
                    work.forEach { it.reset() }
                    contextBudget.reset()
                }
                try {
                    journal.clear()
                    mutableFailure.value = false
                } catch (error: IOException) {
                    storageFailure(error)
                    throw error
                } catch (error: SecurityException) {
                    storageFailure(error)
                    throw error
                }
            }
        }
        DiagnosticResources.reset(resourceEmitter())
    }

    internal fun close() {
        powerContext.stop()
        DiagnosticResources.stop()
        synchronized(storageLock) {
            if (enabled.value) {
                storage { flushAggregates() }
                detach()
            }
            scope.cancel()
            queue.close()
        }
    }

    @Subscribe(threadMode = ThreadMode.POSTING)
    fun onAppEvent(event: Any) {
        val category = when (event.javaClass.name) {
            "com.aitech.weartak_civ.events.NwConnEvent",
            "com.aitech.weartak_civ.events.TakServerConnStatusEvent" -> DiagnosticEvent.CONNECTIVITY
            "com.aitech.weartak_civ.events.PowerManagerEvent" -> DiagnosticEvent.POWER_CHANGED
            "com.aitech.weartak_civ.events.DittoDocumentEvent",
            "com.aitech.weartak_civ.events.SitxServiceEventBus" -> DiagnosticEvent.SYNC
            "com.aitech.weartak_civ.events.SitxServiceCommandBus" -> DiagnosticEvent.COMMAND
            "com.aitech.weartak_civ.events.TakChatEvent" -> DiagnosticEvent.CHAT
            "com.aitech.weartak_civ.events.IsDeviceWornEvent" -> DiagnosticEvent.SENSOR
            "com.aitech.weartak_civ.events.InteractionPliRefresh",
            "com.aitech.weartak_civ.events.MessageEvent" -> DiagnosticEvent.APP_EVENT
            "org.greenrobot.eventbus.SubscriberExceptionEvent" -> DiagnosticEvent.SUBSCRIBER_FAILURE
            else -> return
        }
        val metrics = when (event) {
            is com.aitech.weartak_civ.events.NwConnEvent -> mapOf(
                DiagnosticMetric.ACTIVE to if (event.isConn) 1L else 0L,
                DiagnosticMetric.KIND to event.transport.toLong()
            )
            is com.aitech.weartak_civ.events.TakServerConnStatusEvent ->
                mapOf(DiagnosticMetric.RESULT to event.connEvent.toLong())
            is com.aitech.weartak_civ.events.PowerManagerEvent -> mapOf(
                DiagnosticMetric.REPORT_SECONDS to CotDispatchService.actualReportingIntervalSecs.toLong(),
                DiagnosticMetric.POWER_THROTTLED to if (PowerManagerService.observationPowerThrottled) 1L else 0L,
                DiagnosticMetric.DOCK_THROTTLED to if (PowerManagerService.observationDockThrottled) 1L else 0L
            )
            else -> emptyMap()
        }
        capture(category, metrics)
    }

    private fun screen(activity: Activity): Long = when {
        activity.javaClass.simpleName.contains("Map") -> 1L
        activity.javaClass.simpleName.contains("Settings") -> 2L
        activity.javaClass.simpleName.contains("Chat") -> 3L
        activity.javaClass.simpleName.contains("Main") -> 4L
        else -> 0L
    }
    override fun onActivityResumed(activity: Activity) =
        capture(DiagnosticEvent.ACTIVITY_RESUMED, mapOf(DiagnosticMetric.SCREEN to screen(activity)))
    override fun onActivityStopped(activity: Activity) =
        capture(DiagnosticEvent.ACTIVITY_STOPPED, mapOf(DiagnosticMetric.SCREEN to screen(activity)))
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        @Volatile private var instance: AppDiagnostics? = null
        @Volatile private var samsungHrContinuousRunning = false
        internal fun updateSamsungHrContinuousRunning(running: Boolean) {
            if (samsungHrContinuousRunning == running) return
            samsungHrContinuousRunning = running
            instance?.recordSamsungHrState()
        }
        internal val recordingEnabled get() = instance?.enabled
        internal fun powerBatteryCallback(chargingBoundary: Boolean) {
            instance?.powerContext?.battery(chargingBoundary)
        }
        fun initialize(application: Application): AppDiagnostics =
            instance ?: synchronized(this) {
                instance ?: AppDiagnostics(application).also { instance = it }
            }
        fun record(event: DiagnosticEvent, metrics: Map<DiagnosticMetric, Long> = emptyMap()) {
            instance?.capture(event, metrics)
        }
        internal inline fun <T> measureProcessing(
            stage: ProcessingStage,
            bytes: (T) -> Long = { 0L },
            frames: (T) -> Long = { 0L },
            block: () -> T,
        ): T {
            val recorder = instance ?: return block()
            return recorder.measureProcessing(stage, bytes, frames, block)
        }
        internal fun flushProcessingWork() {
            instance?.flushProcessing()
        }
        internal fun recordIngestReduction(stage: ProcessingStage, coalesced: Long, skipped: Long) {
            val recorder = instance ?: return
            if (!recorder.enabled.value) return
            recorder.captureProcessing(stage, recorder.generation.get(), 0, 0, 0, 0,
                coalesced = coalesced, skipped = skipped, count = 0)
        }
    }
}
