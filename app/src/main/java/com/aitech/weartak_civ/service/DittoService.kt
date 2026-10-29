/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

/*
 *  DittoService handles Ditto's sync lifecycle (startSync, stopSync) and related operations.
 */

package com.aitech.weartak_civ.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.Utils.stopForegroundServiceCompat
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.command.SensitiveEnrollment
import com.aitech.weartak_civ.cot.CotDetailBuilder
import com.aitech.weartak_civ.cot.DittoChatDocument
import com.aitech.weartak_civ.cot.DittoCotReceiver
import com.aitech.weartak_civ.cot.CotMessageParser
import com.aitech.weartak_civ.cot.CotXml
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.DittoDocumentEvent
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.aitech.weartak_civ.presentation.MainActivity.Constants.role
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import live.ditto.Ditto
import live.ditto.DittoError
import live.ditto.DittoIdentity
import live.ditto.DittoSyncSubscription
import live.ditto.DittoStore
import live.ditto.DittoCounter
import live.ditto.android.DefaultAndroidDittoDependencies
import live.ditto.transports.DittoTransportConfig
import java.util.concurrent.atomic.AtomicBoolean
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

class DittoService : Service() {

    private lateinit var dataRepository: DataRepository
    private var dittoUpdateHandler: Handler? = null
    private var dittoUpdateThread: HandlerThread? = null
    private val ioScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO) // Single CoroutineScope for background I/O tasks, ensuring lifecycle management, error isolation, and cleaner code.
    private val uuid = MainActivity.device_uid
    private var subscriptionTrack: DittoSyncSubscription? = null
    private var subscriptionAlert: DittoSyncSubscription? = null
    private var subscriptionMapItem: DittoSyncSubscription? = null
    private var chatRetention: DittoChatRetention? = null
    private var storeObservers: DittoStoreObservers? = null
    private var bleDiscovery: DittoBleDiscoverySession? = null
    private var networkMonitor: DittoNetworkMonitor? = null
    private var powerMonitor: DittoPowerMonitor? = null
    private var publicationRetry: DittoPublicationRetry? = null
    private val powerPolicy = DittoPowerPolicy()
    private var powerState = DittoPowerState()
    private var alertGraceJob: Job? = null
    private var lastTransportSet: Set<String>? = null
    private var changeCounterTrackDocument: Int = 1
    private var ownsDitto = false
    private val peerLock = Any()
    private val transportLock = Any()
    private val trackGate = DittoTrackPublicationGate()
    @Volatile private var peerStopped = false
    @Volatile private var lastSelfTrackDocument: Map<String, Any?>? = null

    private val model = Build.MODEL
    private val manufacturer = Build.MANUFACTURER
    private lateinit var dittoVersion: String

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "DittoService onCreate()")
        isRunning.set(true)
        startForegroundServiceCompat(
            notificationId = DITTO_SERVICE_NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )
        val application = applicationContext as WearTakApplication
        dataRepository = application.repo
        activeService.value = this
        if (reconfiguring.value || dataRepository.shouldSuppressWatchTransportNow()) {
            Log.i(TAG, "Ditto startup suppressed: identity changing or Companion handoff active")
            stopSelf()
            return
        }

        observeCompanionConnection()
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }

        if (getDittoInstance() == null) {
            val initialized = initializeDitto(
                applicationContext,
                dataRepository
            )

            if (!initialized) {
                startupResult.value = false
                stopSelf()
                return
            }
        }

        ownsDitto = true
        localPeerKeyString = ditto.presence.graph.localPeer.peerKeyString

        try {
            configureDitto()
            powerMonitor = DittoPowerMonitor(this, dataRepository, ioScope, ::onPowerStateChanged)
            powerState = powerMonitor!!.initial(runBlocking { dataRepository.getBatteryWarnPercentage.first() })
            setPowerSuppression(powerPolicy.suppressionReason(powerState, android.os.SystemClock.elapsedRealtime()))
            subscribeToCollections()
            storeObservers = DittoStoreObservers(
                ditto.store, ioScope, DittoCotReceiver(
                    repository = dataRepository,
                    bloodhounds = application.bhRepo,
                    journal = getSharedPreferences("ditto-cot-replay-v2", Context.MODE_PRIVATE),
                    deviceUid = { MainActivity.device_uid },
                    callsign = { callsign },
                    localPeer = { localPeerKeyString },
                    chatCommandHandler = ChatCommandRuntime.create(applicationContext, dataRepository)
                )
            )
            if (!startPeerSync()) {
                stopSelf()
                return
            }
            bleDiscovery = DittoBleDiscoverySession(
                application, dataRepository, ditto, ioScope,
                kotlinx.coroutines.flow.combine(
                    dataRepository.getEnableDittoService,
                    dataRepository.isCompanionConnected,
                    reconfiguring,
                    syncActive
                ) { enabled, companion, changing, syncing -> enabled && !companion && !changing && syncing },
                transportLock = transportLock,
                transportChanged = ::logTransportSet
            )
            bleDiscovery?.start()
            powerMonitor?.start()
            publicationRetry = DittoPublicationRetry(
                ioScope, dataRepository.dittoPointLedger.changes,
                dataRepository.dittoPointLedger::hasPending, ::retryPointPublications, syncActive
            )
            ioScope.launch {
                while (isActive) {
                    delay(DittoSubscriptions.REFRESH_MS)
                    try {
                        synchronized(peerLock) {
                            if (!peerStopped) {
                                refreshPointSubscriptions()
                                chatRetention?.refreshIfDue()
                            }
                        }
                    } catch (error: Exception) {
                        Log.e(TAG, "Could not restore Ditto chat subscription; stopping peer", error)
                        stopSelf()
                        return@launch
                    }
                }
            }
            startDispatchScheduler()
            observeTrackInputs()
            dataRepository.setEndpointConnected(DataRepository.Endpoint.DITTO)
            startupResult.value = true
            Log.i(TAG, "DittoService startSync()")
        } catch (e: Exception) {
            Log.e(TAG, "DittoService failed to start", e)
            startupResult.value = false
            stopPeer()
            isRunning.set(false)
            syncActiveState.value = false
            dataRepository.setEndpointDisconnected(DataRepository.Endpoint.DITTO)
            stopSelf()
        }
    }

    internal fun startPeerSync(): Boolean = synchronized(peerLock) {
        if (peerStopped || dataRepository.shouldSuppressWatchTransportNow()) return@synchronized false
        val reason = powerPolicy.suppressionReason(powerState, android.os.SystemClock.elapsedRealtime())
        if (reason != null) {
            setPowerSuppression(reason)
            return@synchronized true
        }
        ditto.startSync()
        setPowerSuppression(null)
        syncActiveState.value = true
        true
    }

    internal fun onPowerStateChanged(state: DittoPowerState) = synchronized(peerLock) {
        powerState = state
        if (peerStopped || !ownsDitto || dataRepository.shouldSuppressWatchTransportNow()) return@synchronized
        val reason = powerPolicy.suppressionReason(state, android.os.SystemClock.elapsedRealtime())
        val suppressed = reason != null
        if (suppressed == powerSuppressedState.value && syncActiveState.value == !suppressed) {
            setPowerSuppression(reason)
            return@synchronized
        }
        try {
            if (suppressed) {
                ditto.stopSync()
                syncActiveState.value = false
                setPowerSuppression(reason)
                dittoUpdateHandler?.removeCallbacks(dittoUpdateRunnable)
            } else {
                ditto.startSync()
                setPowerSuppression(null)
                syncActiveState.value = true
                trackGate.reset()
                updateTrackDocument()
                dittoUpdateHandler?.removeCallbacks(dittoUpdateRunnable)
                dittoUpdateHandler?.postDelayed(dittoUpdateRunnable,
                    CotDispatchService.actualReportingIntervalSecs.coerceAtLeast(1) * 1_000L)
            }
            Log.i(TAG, "Ditto power suppression=$suppressed")
            com.aitech.weartak_civ.presentation.requestStatusIconsComplicationUpdate(this)
        } catch (error: Exception) {
            Log.e(TAG, "Could not apply Ditto power policy; stopping peer", error)
            stopSelf()
        }
    }

    internal fun resumeForAlert() = synchronized(peerLock) {
        if (peerStopped || dataRepository.shouldSuppressWatchTransportNow()) return@synchronized
        powerPolicy.resumeForAlert(android.os.SystemClock.elapsedRealtime())
        onPowerStateChanged(powerState)
        alertGraceJob?.cancel()
        alertGraceJob = ioScope.launch {
            delay(DittoPowerPolicy.ALERT_GRACE_MS)
            synchronized(peerLock) { onPowerStateChanged(powerState) }
        }
    }

    internal fun observeCompanionConnection() {
        ioScope.launch(Dispatchers.Main.immediate) {
            dataRepository.isCompanionConnected.collect { connected ->
                if (connected) {
                    Log.i(TAG, "Bonded companion ready; stopping watch Ditto peer and workers")
                    stopPeer()
                    stopSelf()
                }
            }
        }
    }

    internal suspend fun prepareForCompanionHandoff() {
        val expired = try {
            expireSelfTrackBeforeHandoff()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Could not publish final expired Ditto self track", error)
            false
        }
        if (expired) delay(DITTO_TRACK_FLUSH_WAIT_MS)
    }

    private suspend fun expireSelfTrackBeforeHandoff(): Boolean = withContext(Dispatchers.IO) {
        val lastTrack = lastSelfTrackDocument ?: return@withContext false
        synchronized(peerLock) {
            if (peerStopped) return@withContext false
            val expiredTrack = DittoTrackExpiry.expire(lastTrack, System.currentTimeMillis())
            // This single expiry update is the handoff exception to the publication gate.
            writeFinalExpiredSelfTrack(expiredTrack)
            lastSelfTrackDocument = expiredTrack
        }
        Log.i(TAG, "Published final expired Ditto self track before Companion handoff")
        true
    }

    private fun stopPeer() = synchronized(peerLock) {
        if (peerStopped) return@synchronized
        peerStopped = true
        if (ownsDitto) {
            try {
                getDittoInstance()?.stopSync()
                Log.i(TAG, "Watch Ditto sync stopped (all peer transports)")
            } catch (error: DittoError) {
                Log.e(TAG, "Error stopping Ditto sync", error)
            }
        }
        syncActiveState.value = false
        setPowerSuppression(null)
        networkMonitor?.close()
        networkMonitor = null
        powerMonitor?.close()
        powerMonitor = null
        publicationRetry?.close()
        publicationRetry = null
        dittoUpdateHandler?.removeCallbacks(dittoUpdateRunnable)
        dittoUpdateThread?.quitSafely()
        dittoUpdateHandler = null
        dittoUpdateThread = null
        storeObservers?.close()
        storeObservers = null
        bleDiscovery?.close()
        bleDiscovery = null
        ioScope.cancel()
        unSubscribeToCollections()
        dataRepository.setEndpointDisconnected(DataRepository.Endpoint.DITTO)
        if (EventBus.getDefault().isRegistered(this)) EventBus.getDefault().unregister(this)
    }

    private fun subscribeToCollections() {
        Log.d(TAG, "DittoService subscribeToCollections(): [ track, alert, mapitem, chat ]")
        refreshPointSubscriptions()
        chatRetention = DittoChatRetention(
            getSharedPreferences("ditto-retention", Context.MODE_PRIVATE),
            subscribe = { ditto.sync.registerSubscription(it) },
            evict = { evictExpiredChatDocuments(ditto.store, it) },
            onError = { Log.e(TAG, "Could not evict expired Ditto chat; retrying next day", it) }
        )
        chatRetention?.start()

    }

    private fun refreshPointSubscriptions() {
        val cutoff = System.currentTimeMillis()
        subscriptionTrack?.close()
        subscriptionTrack = null
        subscriptionTrack = ditto.sync.registerSubscription(DittoSubscriptions.query("track", cutoff))
        subscriptionMapItem?.close()
        subscriptionMapItem = null
        subscriptionMapItem = ditto.sync.registerSubscription(DittoSubscriptions.query("mapitem", cutoff))
        subscriptionAlert?.close()
        subscriptionAlert = null
        subscriptionAlert = ditto.sync.registerSubscription(DittoSubscriptions.query("alert", cutoff))
    }

    private fun unSubscribeToCollections() {
        Log.d(TAG, "Subscriptions have been closed.")
        subscriptionTrack?.close()
        subscriptionAlert?.close()
        subscriptionMapItem?.close()
        chatRetention?.close()

        subscriptionTrack = null
        subscriptionAlert = null
        subscriptionMapItem = null
        chatRetention = null
    }

    private fun configureDitto() {
        val config = ditto.transportConfig.clone()
        config.peerToPeer.bluetoothLe.enabled = false // Discovery owns bounded BLE windows.
        ditto.deviceName = callsign // set deviceName for presence viewer
        dittoVersion = ditto.sdkVersion // used in the cot detail for ditto
        ditto.transportConfig = config
        val websocketUrls = config.connect.websocketUrls.toSet()
        networkMonitor = DittoNetworkMonitor(this) { wifi, internet ->
            synchronized(transportLock) {
                if (!peerStopped) {
                    try {
                        val next = ditto.transportConfig.clone()
                        DittoTransportPolicy.apply(next, packageManager, wifi, internet, websocketUrls)
                        if (next != ditto.transportConfig) ditto.transportConfig = next
                        logTransportSet(next)
                    } catch (error: Exception) {
                        Log.e(TAG, "Could not reconfigure Ditto transports; stopping peer", error)
                        stopSelf()
                    }
                }
            }
        }
        networkMonitor?.start()
    }

    private fun logTransportSet(config: DittoTransportConfig) {
        val enabled = DittoTransportPolicy.enabled(config)
        if (lastTransportSet != enabled) {
            lastTransportSet = enabled
            Log.i(TAG, "Ditto enabled transports=$enabled (Wi-Fi Direct: not exposed by SDK 4)")
        }
    }

    private val dittoUpdateRunnable = object : Runnable {
        override fun run() {
            if (peerStopped || dataRepository.shouldSuppressWatchTransportNow()) return
            ioScope.launch {

                val battery = dataRepository.latestBatteryPercentage.value

                if (battery != null && syncActiveState.value) {
                    updateTrackDocument()
//                    logAllDevices()
                } else {
                    Log.w(TAG, "Missing data for Ditto database. Not sending.")
                }
            }

            // Schedule the next run
            if (syncActiveState.value) dittoUpdateHandler?.postDelayed(
                this,
                CotDispatchService.actualReportingIntervalSecs * 1000L
            )
        }
    }

    private fun startDispatchScheduler() {
        dittoUpdateThread = HandlerThread("CotDispatchThread")
        dittoUpdateThread?.start()
        dittoUpdateHandler = dittoUpdateThread?.looper?.let { Handler(it) }

        // Schedule the first run
        if (syncActiveState.value) dittoUpdateHandler?.postDelayed(dittoUpdateRunnable, DISPATCH_INTERVAL)
    }

    private fun observeTrackInputs() {
        ioScope.launch {
            CotDispatchService.actualReportingIntervalSecsFlow.collect {
                if (syncActiveState.value) {
                    updateTrackDocument()
                    dittoUpdateHandler?.removeCallbacks(dittoUpdateRunnable)
                    dittoUpdateHandler?.postDelayed(dittoUpdateRunnable, it.coerceAtLeast(1) * 1_000L)
                }
            }
        }
        ioScope.launch {
            kotlinx.coroutines.flow.combine(dataRepository.selfStatusInputs(),
                dataRepository.latestBatteryPercentage) { _, _ -> Unit }
                .collect { updateTrackDocument() }
        }
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun onLocationChanged(event: com.aitech.weartak_civ.events.LocationRcvd) {
        updateTrackDocument()
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun onDittoDoc(evt: DittoDocumentEvent) {
        ioScope.launch {
            try {
                val raw = evt.cotXml
                if (SensitiveEnrollment.containsSensitiveXml(raw)) return@launch
                logVerbose { "Publishing Ditto document collection=${evt.collection}" }
                if (evt.collection == "chat") {
                    publishDocument("chat", DittoChatDocument.encode(raw, localPeerKeyString))
                    return@launch
                }

                // 1) Core CoT parsing using XmlPullParser
                val parser = XmlPullParserFactory.newInstance()
                    .newPullParser().apply {
                        setInput(raw.reader())
                    }
                var id = ""
                var timeStart = 0L
                var timeStale = 0L
                var lat = 0.0
                var lon = 0.0
                var hae = 0.0
                var ce = 0.0
                var le = 0.0

                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG) {
                        when (parser.name) {
                            "event" -> {
                                parser.getAttributeValue(null, "uid")?.let { id = it }
                                    ?: throw IllegalArgumentException("Missing uid in CoT XML")
                                parser.getAttributeValue(null, "time")?.let {
                                    timeStart = Instant.parse(it).toEpochMilli()
                                } ?: throw IllegalArgumentException("Missing time in CoT XML")
                                parser.getAttributeValue(null, "stale")?.let {
                                    timeStale = Instant.parse(it).toEpochMilli()
                                } ?: throw IllegalArgumentException("Missing stale in CoT XML")
                            }

                            "point" -> {
                                parser.getAttributeValue(null, "lat")?.let { lat = it.toDouble() }
                                    ?: throw IllegalArgumentException("Missing lat in CoT XML")
                                parser.getAttributeValue(null, "lon")?.let { lon = it.toDouble() }
                                    ?: throw IllegalArgumentException("Missing lon in CoT XML")
                                parser.getAttributeValue(null, "hae")?.let { hae = it.toDouble() }
                                    ?: throw IllegalArgumentException("Missing hae in CoT XML")
                                parser.getAttributeValue(null, "ce")?.let { ce = it.toDouble() }
                                    ?: throw IllegalArgumentException("Missing ce in CoT XML")
                                parser.getAttributeValue(null, "le")?.let { le = it.toDouble() }
                                    ?: throw IllegalArgumentException("Missing le in CoT XML")
                                break
                            }
                        }
                    }
                    parser.next()
                }

                // 2) Point attributes parsed via parser, skip original extract code

                // 3) Detail + author info
                val how = extractOrNull(raw, "how") ?: ""
                val access = extractOrNull(raw, "access") ?: "Undefined"
                val detailXml = raw
                    .substringAfter("<detail>", "")
                    .takeIf { it.contains("</detail>") }
                    ?.let { "<detail>${it.substringBefore("</detail>")}</detail>" }
                    ?: ""
                val typeAlias = extract(raw, "type")
                val cs = extractOrNull(raw, "callsign") ?: callsign
                val pks = extractOrNull(raw, "pks") ?: localPeerKeyString

                // 4) Build v2‐style doc for mapitem vs alert
                val doc = when (evt.collection) {
                    "mapitem" -> mapOf<String, Any?>(
                        // — Ditto‐managed metadata (_c, _r, _v) are omitted here —
                        "_id" to id,
                        "_r" to false,
                        "_v" to 2,

                        "authorCs" to callsign,
                        "AuthorId" to uuid,
                        "authorType" to typeAlias,
                        "visible" to true,

                        // — common props + visibility —
                        "a" to pks,
                        "b" to timeStart,
                        "c" to cs,
                        "d" to uuid,
                        "e" to callsign,
                        "f" to true,

                        // — CoT point & timing —
                        "g" to "2.0",
                        "h" to ce,
                        "i" to hae,
                        "j" to lat,
                        "k" to le,
                        "l" to lon,
                        "n" to timeStart,
                        "o" to timeStale,

                        // — how/access/detail —
                        "p" to how,
                        "q" to access,
                        "r" to detailXml,

                        // — type alias —
                        "w" to typeAlias
                    )

                    "alert" -> mapOf<String, Any?>(
                        "_id" to id,
                        "_r" to false,
                        "_v" to 2,

                        // only common & CoT props—no c/f for alerts
                        "a" to pks,
                        "b" to timeStart,
                        "d" to uuid,
                        "e" to callsign,

                        "authorCs" to callsign,
                        "AuthorId" to uuid,
                        "authorType" to typeAlias,
                        "visible" to true,

                        "g" to "2.0",
                        "h" to ce,
                        "i" to hae,
                        "j" to lat,
                        "k" to le,
                        "l" to lon,
                        "n" to timeStart,
                        "o" to timeStale,

                        "p" to how.ifEmpty { "m-g" },
                        "q" to access,
                        "r" to detailXml,

                        "w" to typeAlias
                    )

                    else -> return@launch
                }

                // 5) Upsert into Ditto
                if (publishDocument(evt.collection, doc)) {
                    logVerbose { "Wrote to ${evt.collection}: $doc" }
                }

            } catch (t: Throwable) {
                Log.e(TAG, "Ditto write failed")
            }

        }
    }

    internal fun retryPointPublications() {
        if (peerStopped || powerSuppressedState.value || dataRepository.shouldSuppressWatchTransportNow()) return
        dataRepository.dittoPointLedger.retry(
            write = ::writeOwnedPointDocument,
            onError = { Log.e(TAG, "Ditto point publication pending; retrying later", it) }
        )
    }

    internal fun evictExpiredChatDocuments(
        store: DittoStore = ditto.store,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        // Local eviction, not replicated deletion: expiry never removes inbox history.
        val query = DittoChatRetention.expiredQuery(nowMillis)
        val count = store.collection("chat").find(query).evict().size
        check(store.collection("chat").find(query).limit(1).exec().isEmpty()) {
            "Expired Ditto chat remains after local eviction"
        }
        Log.i(TAG, "Ditto chat retention completed: evicted=$count remainingExpired=0")
    }

    internal fun publishDocument(collection: String, document: Map<String, Any?>): Boolean {
        if (SensitiveEnrollment.containsSensitiveDocument(document)) return false
        if (peerStopped || dataRepository.shouldSuppressWatchTransportNow()) {
            Log.d(TAG, "Suppressing direct Ditto publication: peer stopped or bonded companion connected")
            return false
        }
        if (collection == "alert" || CotMessageParser.isEmergencyType(document["w"] as? String ?: "")) resumeForAlert()
        if (collection == "mapitem" && CotMessageParser.isMarkerType(document["w"] as? String ?: "")) {
            val published = dataRepository.dittoPointLedger.publish(document, ::writeOwnedPointDocument)
            if (!published) {
                Log.w(TAG, "Ditto point not published: no active local-origin ownership")
            }
            return published
        } else {
            writeDocument(collection, document)
            return true
        }
    }

    internal fun writeDocument(
        collection: String,
        document: Map<String, Any?>,
    ) {
        if (SensitiveEnrollment.containsSensitiveDocument(document)) return
        synchronized(peerLock) {
            check(!peerStopped && !dataRepository.shouldSuppressWatchTransportNow()) {
                "Ditto peer stopped or handed off to Companion before document write"
            }
            val properties = if (collection == "chat") document + ("_c" to DittoCounter()) else document
            ditto.store.write { tx -> tx.scoped(collection).upsert(properties) }
        }
    }

    private fun writeFinalExpiredSelfTrack(document: Map<String, Any?>) {
        if (SensitiveEnrollment.containsSensitiveDocument(document)) return
        synchronized(peerLock) {
            check(!peerStopped) { "Ditto peer stopped before final self-track expiry" }
            val activeDitto = checkNotNull(getDittoInstance()) {
                "Ditto instance unavailable before final self-track expiry"
            }
            activeDitto.store.write { tx -> tx.scoped("track").upsert(document) }
        }
    }

    private fun writeOwnedPointDocument(document: Map<String, Any?>) {
        require(document["a"] == localPeerKeyString) { "Pending Ditto point belongs to another local peer store" }
        writeDocument("mapitem", document)
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun onOutboundTrackRefresh(event: com.aitech.weartak_civ.events.OutboundDittoTrackRefresh) {
        updateTrackDocument(android.location.Location(event.location))
    }

    private suspend fun outboundTrackRefreshAllowed(location: android.location.Location): Boolean =
        startupResult.value == true &&
            dataRepository.isEndpointConnected(DataRepository.Endpoint.DITTO) &&
            dataRepository.getEnableDittoService.first() &&
            dataRepository.getEnableLocationService.first() &&
            !dataRepository.shouldSuppressWatchTransportNow() &&
            dataRepository.isCurrentLocationInput(location) &&
            (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED) &&
            OutboundPliAnnouncementGate.fresh(location, android.os.SystemClock.elapsedRealtimeNanos())

    private fun updateTrackDocument() = updateTrackDocument(null)

    private fun updateTrackDocument(freshLocation: android.location.Location?) {
        ioScope.launch {
            if (peerStopped || powerSuppressedState.value || dataRepository.shouldSuppressWatchTransportNow()) return@launch
            if (freshLocation != null && !outboundTrackRefreshAllowed(freshLocation)) {
                Log.i(TAG, "Skipping outbound Ditto track refresh: route/input unavailable or fix expired")
                return@launch
            }
            val freshSnapshot = freshLocation?.let { dataRepository.snapshotOutboundAnnouncement(it) }
            val timeMs = freshLocation?.time ?: System.currentTimeMillis()
            val interval = CotDispatchService.actualReportingIntervalSecs.coerceAtLeast(1)
            val standaloneStaleMsOffset = (2L * interval + 15) * 1_000L
            val staleMsOffset = com.aitech.weartak_civ.cot.SelfPliStalePolicy.offsetMillis(
                companionBonded = dataRepository.hasCompanionEverBeenReady(),
                effectiveIntervalSeconds = interval,
                standaloneOffsetMillis = standaloneStaleMsOffset,
            )
            val staleTimeMs = timeMs + staleMsOffset
            val location = if (freshSnapshot != null) freshSnapshot.loc
                else dataRepository.snapshotPliLocation()
            if (location == null) {
                Log.i(TAG, "Skipping Ditto track: awaiting first valid location fix")
                return@launch
            }
            val pliSnapshot = freshSnapshot ?: dataRepository.snapshotPli()
            val battery = freshSnapshot?.systemData?.batteryPercent ?: dataRepository.latestBatteryPercentage.value ?: -1
            val callsign = freshSnapshot?.systemData?.callsign ?: MainActivity.callsign
            val role = freshSnapshot?.systemData?.role ?: MainActivity.role
            val teamValue = freshSnapshot?.systemData?.team ?: MainActivity.teamValue
            val uuid = freshSnapshot?.systemData?.deviceId ?: this@DittoService.uuid
            val sample = DittoTrackPublicationGate.Sample(location, battery,
                listOf(callsign, role, teamValue, uuid), pliSnapshot.remarks, pliSnapshot.monitoredPhysio, interval)
            val latActual = location.lat
            val lonActual = location.lon
            val haeActual = location.hae ?: 0.0
            var ceActual = location.ce ?: 9999999.0F
            if (ceActual.isNaN()) ceActual = 9999999.0F
            var leActual: Float = location.le ?: 9999999.0F
            if (leActual.isNaN()) leActual = 9999999.0F
            val bearingActual = location.course ?: 0.0F
            val speedActual = location.speed ?: 0.0F
            val typeString = "a-f-G-U-C"
            val access = "Undefined"
            val how = "m-g"


            // 2) Merged version (original + ditto + remarks/biometrics/track)
            val cotDetail = CotDetailBuilder.dittoTrack(
                // your original args:
                monitoredPhysio = pliSnapshot.monitoredPhysio,
                battery = battery,
                callsign = callsign,
                role = role,
                team = teamValue,
                uid = uuid,
                course = bearingActual,
                speed = speedActual,
                altitudeSource = location.coordinateSource?.uppercase() ?: "UNKNOWN",
                geopointSource = location.coordinateSource?.uppercase() ?: "UNKNOWN",
                // if you need a custom endpoint/phone (these also have defaults):
                endpoint = "*:-1:stcp",
                // ditto payload:
                dittoA = localPeerKeyString,                     // "a-f-G-U-C"
                dittoIp = "0.0.0.0",                         // "Undefined"
                dittoVersion = dittoVersion,                            // "m-g"
                dittoDeviceName = callsign,
                remarks = pliSnapshot.remarks,
            ).let { detail ->
                if (timeMs - location.timeMillis > 3_000) {
                    detail.replace("</detail>", CotXml.element("weartak_location",
                        listOf("timeMillis" to location.timeMillis)) + "</detail>")
                } else detail
            }

            val trackDocument = mapOf<String, Any?>(
                // — common properties —
                "_id" to uuid,
//                "_c" to changeCounterTrackDocument.toDouble(),
                "_r" to false,
                "_v" to 2,

                "authorCs" to callsign,
                "AuthorId" to uuid,
                "authorType" to typeString,
                "visible" to true,

                "a" to localPeerKeyString,
                "b" to timeMs,
                "c" to callsign,
                "d" to uuid,
                "e" to callsign,
                "f" to true,            // visible (tracks are always “on”)
                "g" to "2.0",           // your app version or Cot version

                // — CoT point fields —
                "h" to ceActual.toDouble(),   // CE
                "i" to haeActual,             // HAE
                "j" to latActual,             // LAT
                "k" to leActual.toDouble(),   // LE
                "l" to lonActual,             // LON

                // — timing & lifecycle —
                "n" to timeMs,          // start
                "o" to staleTimeMs,     // stale

                // — how/access/detail —
                "p" to how,
                "q" to access,
                "r" to cotDetail,      // must be the full `<detail>…</detail>` string

                // — optional telemetry aliases —
                "r1" to speedActual.toString(),   // speed
                "r2" to haeActual.toString(),     // sometimes used for additional metric

                // — type string for this document —
                "w" to typeString     // e.g. "a-f-G-U-C"
            )

            try {
                if (dataRepository.shouldSuppressWatchTransportNow()) return@launch
                if (location.provider != null && !dataRepository.isCurrentLocationSnapshot(location)) {
                    Log.i(TAG, "Skipping Ditto track built before a location input change")
                    return@launch
                }
                if (freshLocation != null && !outboundTrackRefreshAllowed(freshLocation)) {
                    Log.i(TAG, "Skipping outbound Ditto track refresh: eligibility changed while building")
                    return@launch
                }
                synchronized(peerLock) {
                    if (peerStopped || powerSuppressedState.value) return@launch
                    val elapsed = android.os.SystemClock.elapsedRealtime()
                    if (freshLocation == null && !trackGate.due(sample, elapsed)) return@launch
                    writeDocument("track", trackDocument)
                    trackGate.published(sample, elapsed)
                    lastSelfTrackDocument = trackDocument
                    changeCounterTrackDocument++
                    dittoUpdateHandler?.removeCallbacks(dittoUpdateRunnable)
                    if (syncActiveState.value) dittoUpdateHandler?.postDelayed(dittoUpdateRunnable,
                        interval * 1_000L)
                }
                logVerbose { "Wrote to track: $trackDocument" }
            } catch (t: Throwable) {
                Log.e(TAG, "Ditto write failed", t)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "DittoService onDestroy()")
        isRunning.set(false)
        stopForegroundServiceCompat()
        stopPeer()
        if (activeService.value === this) activeService.value = null
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d(TAG, "DittoService onStartCommand()")
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // Not a bound service
    }

    //––––––––– | Helper tools for Cot Parsing from DittoDocumentEvent
    /** Pulls a required attribute or throws if missing */
    private fun extract(raw: String, name: String): String =
        """$name=['"]([^'"]+)['"]""".toRegex()
            .find(raw)!!.groupValues[1]

    /** Pulls an optional attribute or null if absent */
    private fun extractOrNull(raw: String, name: String): String? =
        """$name=['"]([^'"]+)['"]""".toRegex()
            .find(raw)?.groupValues?.get(1)

    /** Pulls the type of the cot messages author **/
    private fun extractLinkAttrOrNull(raw: String, attrName: String): String? =
        """<link[^>]*\b$attrName=['"]([^'"]+)['"]""".toRegex()
            .find(raw)
            ?.groupValues
            ?.get(1)

    companion object {
        private val TAG = "WearTAK-" + DittoService::class.java.simpleName
        internal inline fun logVerbose(message: () -> String) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, message())
        }

        private val DITTO_SERVICE_NOTIFICATION_ID = 3333
        val isRunning: AtomicBoolean = AtomicBoolean(false)
        private val syncActiveState = MutableStateFlow(false)
        val syncActive = syncActiveState.asStateFlow()
        private val powerSuppressedState = MutableStateFlow(false)
        val powerSuppressed = powerSuppressedState.asStateFlow()
        private val powerSuppressionReasonState = MutableStateFlow<DittoPowerSuppression?>(null)
        internal val powerSuppressionReason = powerSuppressionReasonState.asStateFlow()
        private fun setPowerSuppression(reason: DittoPowerSuppression?) {
            powerSuppressionReasonState.value = reason
            powerSuppressedState.value = reason != null
        }
        private const val DISPATCH_INTERVAL = 3_000L // 3 seconds
        private const val DITTO_TRACK_FLUSH_WAIT_MS = 1_000L
        lateinit var localPeerKeyString: String
        private var dittoInstance: Ditto? = null
        private val ditto: Ditto get() = requireNotNull(dittoInstance)
        private val activeService = MutableStateFlow<DittoService?>(null)
        private val startupResult = MutableStateFlow<Boolean?>(null)
        private val reconfiguring = MutableStateFlow(false)
        val isReconfiguring = reconfiguring.asStateFlow()
        private val identityMutex = Mutex()

        fun getDittoInstance(): Ditto? = dittoInstance

        internal fun onDittoScreenOpened() {
            activeService.value?.bleDiscovery?.onUiOpened()
        }

        internal fun resumeSyncForAlert() { activeService.value?.resumeForAlert() }

        internal fun publishCommandReply(xml: String): Boolean {
            val service = activeService.value ?: return false
            if (!isRunning.get() || service.dataRepository.shouldSuppressWatchTransportNow()) return false
            return service.publishDocument("chat", DittoChatDocument.encode(xml, localPeerKeyString))
        }

        internal suspend fun prepareForCompanionHandoffIfRunning() {
            val service = activeService.value ?: return
            if (isRunning.get()) service.prepareForCompanionHandoff()
        }

        internal suspend fun applyEnabled(context: Context, repo: DataRepository, enabled: Boolean): Boolean =
            identityMutex.withLock {
                if (repo.phoneManagedSettingsLocked.first() || repo.getNetworkPreferencesLocked.first()) return@withLock false
                val before = repo.getEnableDittoService.first()
                try {
                    if (enabled && !isRunning.get()) startupResult.value = null
                    repo.storeEnableDittoService(enabled)
                    if (repo.getEnableDittoService.first() != enabled) return@withLock false
                    if (!enabled) {
                        withContext(Dispatchers.Main) { context.stopService(Intent(context, DittoService::class.java)) }
                        withTimeout(15_000) { activeService.first { it == null } }
                        true
                    } else {
                        val app = context.applicationContext as WearTakApplication
                        val config = ServiceConfiguration(context, repo, app.audioRepo)
                        if (!config.getServiceEnabledFlow(ReactiveServiceManager.ServiceType.DITTO).first() ||
                            !config.isServiceAvailable(ReactiveServiceManager.ServiceType.DITTO)) {
                            true
                        } else if (isRunning.get()) true
                        else {
                            check(ReactiveServiceManager.isRunning.get())
                            check(withTimeout(20_000) { startupResult.filterNotNull().first() })
                            true
                        }
                    }
                } catch (error: CancellationException) {
                    withContext(kotlinx.coroutines.NonCancellable) { repo.storeEnableDittoService(before) }
                    if (error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                    Log.e(TAG, "Ditto enable update timed out")
                    false
                } catch (_: Exception) {
                    repo.storeEnableDittoService(before)
                    Log.e(TAG, "Ditto enable update failed")
                    false
                }
            }

        suspend fun applySelectedIdentity(context: Context, repo: DataRepository): Boolean =
            identityMutex.withLock {
                reconfiguring.value = true
                try {
                    val previous = activeService.value
                    val thread = previous?.dittoUpdateThread
                    withContext(Dispatchers.Main) {
                        context.stopService(Intent(context, DittoService::class.java))
                    }
                    withTimeout(15_000) {
                        activeService.first { it == null }
                        // Cancellation is not completion: wait for store writes/observers before closing JNI.
                        previous?.ioScope?.coroutineContext?.get(Job)?.join()
                        if (thread != null) withContext(Dispatchers.IO) { thread.join(10_000) }
                        check(thread?.isAlive != true) { "Ditto worker did not stop" }
                    }
                    withContext(Dispatchers.Main) {
                        dittoInstance?.close()
                        dittoInstance = null
                        check(initializeDitto(context, repo)) { "Ditto identity could not be applied" }
                    }
                    startupResult.value = null
                } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                    Log.e(TAG, "Ditto identity apply timed out")
                    return@withLock false
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.e(TAG, "Ditto identity apply failed: ${error.javaClass.simpleName}")
                    return@withLock false
                } finally {
                    reconfiguring.value = false
                }
                val app = context.applicationContext as WearTakApplication
                val config = ServiceConfiguration(context, repo, app.audioRepo)
                if (!config.getServiceEnabledFlow(ReactiveServiceManager.ServiceType.DITTO).first() ||
                    !config.isServiceAvailable(ReactiveServiceManager.ServiceType.DITTO)) {
                    // The identity is applied, but the normal companion/license/permission gates still win.
                    return@withLock true
                }
                if (!ReactiveServiceManager.isRunning.get()) {
                    Log.e(TAG, "Ditto identity applied but service manager is unavailable")
                    return@withLock false
                }
                try {
                    withTimeout(20_000) { startupResult.filterNotNull().first() }
                } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                    Log.e(TAG, "Ditto identity applied but service startup timed out")
                    false
                }
            }

        private fun initializeDitto(
            applicationContext: Context,
            dataRepository: DataRepository
        ): Boolean {
            Log.d(TAG, "Initializing Ditto")

            // Ditto has already been initialized
            if (dittoInstance != null) {
                return true
            }

            // Load the selected Community or Custom PDL
            val encodedPdl = runBlocking {
                dataRepository.getEffectiveDittoPdl.first()
            }.trim()

            // Do not log the actual PDL because it contains credentials
            Log.d(
                TAG,
                "Ditto identity present=${encodedPdl.isNotBlank()}, " +
                        "length=${encodedPdl.length}"
            )

            // Decode and validate the PDL
            val license = try {
                DittoCommunityLicenseHelper.parse(encodedPdl)
            } catch (error: Exception) {
                Log.e(TAG, "Invalid PDL")
                Toast.makeText(applicationContext, "Invalid PDL", Toast.LENGTH_LONG).show()
                return false
            }

            // Stop if the PDL has expired
            if (license.isExpired) {
                Log.e(TAG, "Expired PDL")
                Toast.makeText(applicationContext, "Expired PDL", Toast.LENGTH_LONG).show()
                return false
            }

            // Initialize Ditto using the identity contained in the PDL
            var candidate: Ditto? = null
            return try {
                val androidDependencies =
                    DefaultAndroidDittoDependencies(applicationContext)

                val identity = DittoIdentity.SharedKey(
                    androidDependencies,
                    appId = license.appId,
                    sharedKey = license.sharedKey
                )

                candidate = Ditto(
                    androidDependencies,
                    identity
                )

                // Apply the PDL's offline license token
                candidate.setOfflineOnlyLicenseToken(
                    license.licenseToken
                )

                dittoInstance = candidate
                Log.i(TAG, "Ditto initialized successfully")
                true
            } catch (error: Exception) {
                candidate?.close()
                Log.e(TAG, "Failed to initialize Ditto")
                Toast.makeText(applicationContext, "Ditto failed to start", Toast.LENGTH_LONG).show()
                false
            }
        }
    }
}
