package com.aitech.weartak_civ.integration.plugin

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.service.CotDispatchService
import com.aitech.weartak_civ.service.DittoPowerMonitor
import com.aitech.weartak_civ.service.DittoPowerPolicy
import com.aitech.weartak_civ.service.DittoPowerState
import com.aitech.weartak_civ.service.DittoTrackPublicationGate
import com.aitech.weartak_civ.service.DittoTrackSnapshot
import com.aitech.weartak_civ.service.OutboundPliAnnouncementGate
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Captures fresh host-owned PLI; transport admission is not a delivery receipt. */
internal class PluginOnlyPliProducer(
    private val app: WearTakApplication,
    private val transport: PluginTransportHostV1Impl,
    private val ownershipActive: () -> Boolean,
    private val provisionedIdentity: () -> String?,
    private val onUnavailable: () -> Unit,
) : AutoCloseable {
    private val repo = app.repo
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO +
        CoroutineExceptionHandler { _, error ->
            Log.e(TAG, "Plugin PLI producer failed", error)
            unavailable()
        })
    private val active = AtomicBoolean(true)
    private val triggers = Channel<Unit>(Channel.CONFLATED)
    private val powerLock = Any()
    private val powerPolicy = DittoPowerPolicy()
    private val gate = DittoTrackPublicationGate()
    private var powerMonitor: DittoPowerMonitor? = null
    @Volatile private var enabled = false
    @Volatile private var locationEnabled = false
    @Volatile private var suppressed = true
    @Volatile private var powerAllowed = false

    fun start() {
        scope.launch {
            try {
                val monitor = DittoPowerMonitor(app, repo, scope, ::onPowerChanged)
                val initial = monitor.initial(repo.getBatteryWarnPercentage.first())
                synchronized(powerLock) {
                    if (!active.get()) return@launch
                    powerMonitor = monitor
                    onPowerChanged(initial)
                    if (active.get()) monitor.start()
                }
                if (!active.get()) return@launch
                launch {
                    combine(repo.getEnableDittoService, repo.getEnableLocationService,
                        repo.watchTransportSuppressed) { ditto, location, companion ->
                        Triple(ditto, location, companion)
                    }.distinctUntilChanged().collect { (ditto, location, companion) ->
                        enabled = ditto
                        locationEnabled = location
                        suppressed = companion
                        if (!ditto || companion) unavailable() else triggers.trySend(Unit)
                    }
                }
                launch {
                    repo.getEffectiveDittoPdl.distinctUntilChanged().collect { selected ->
                        val provisioned = provisionedIdentity()
                        if (provisioned != null && provisioned != selected) unavailable()
                    }
                }
                launch { repo.latestLocation.collect { triggers.trySend(Unit) } }
                launch { repo.selfStatusInputs().collect { triggers.trySend(Unit) } }
                launch { repo.latestBatteryPercentage.collect { triggers.trySend(Unit) } }
                launch { CotDispatchService.actualReportingIntervalSecsFlow.collect { triggers.trySend(Unit) } }
                launch {
                    while (isActive) {
                        triggers.trySend(Unit)
                        delay(CotDispatchService.actualReportingIntervalSecs.coerceAtLeast(1) * 1_000L)
                    }
                }
                for (ignored in triggers) publishLatest()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Plugin PLI producer failed", error)
                unavailable()
            }
        }
    }

    private fun onPowerChanged(state: DittoPowerState) {
        powerAllowed = !powerPolicy.suppressed(state, SystemClock.elapsedRealtime())
        if (!powerAllowed) unavailable() else triggers.trySend(Unit)
    }

    private suspend fun publishLatest() {
        if (!ownershipActive()) {
            unavailable()
            return
        }
        if (!baseEligible()) return
        val location = repo.bestKnownLocation() ?: return
        if (!repo.isCurrentLocationInput(location) ||
            !OutboundPliAnnouncementGate.fresh(location, SystemClock.elapsedRealtimeNanos())) return
        val snapshot = DittoTrackSnapshot.capture(repo, null, repo.androidDeviceUid) {
            HandlerExertion.read(app.pluginManager)
        } ?: return
        if (!eligible(snapshot)) return
        val sample = DittoTrackPublicationGate.Sample(snapshot.location, snapshot.battery,
            listOf(snapshot.callsign, snapshot.role, snapshot.team, snapshot.uid), snapshot.remarks,
            snapshot.monitoredPhysio, snapshot.intervalSeconds)
        val now = SystemClock.elapsedRealtime()
        if (!gate.due(sample, now)) return
        if (transport.offerPluginPli(snapshot) { eligible(snapshot) }) gate.published(sample, now)
    }

    private fun baseEligible(): Boolean {
        if (!active.get() || !enabled || !locationEnabled || suppressed || !powerAllowed ||
            !ownershipActive() || repo.shouldSuppressWatchTransportNow()) return false
        val identity = provisionedIdentity() ?: return false
        val current = runBlocking {
            Triple(repo.getEnableDittoService.first(), repo.getEnableLocationService.first(),
                repo.getEffectiveDittoPdl.first())
        }
        if (current.third != identity) {
            unavailable()
            return false
        }
        return current.first && current.second &&
            (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }

    private fun eligible(snapshot: DittoTrackSnapshot): Boolean {
        if (!baseEligible() || snapshot.uid != repo.androidDeviceUid ||
            !MoppPluginAvailability.isCurrent(snapshot.moppGeneration) ||
            !repo.isCurrentLocationSnapshot(snapshot.location)) return false
        val location = repo.bestKnownLocation() ?: return false
        return location.elapsedRealtimeNanos == snapshot.location.elapsedRealtimeNanos &&
            OutboundPliAnnouncementGate.fresh(location, SystemClock.elapsedRealtimeNanos())
    }

    private fun unavailable() {
        if (!active.compareAndSet(true, false)) return
        synchronized(powerLock) { powerMonitor?.close(); powerMonitor = null }
        triggers.close()
        scope.cancel()
        onUnavailable()
    }

    override fun close() {
        if (!active.compareAndSet(true, false)) return
        synchronized(powerLock) { powerMonitor?.close(); powerMonitor = null }
        triggers.close()
        scope.cancel()
    }

    companion object {
        private const val TAG = "WearTAK-PluginPli"
    }
}
