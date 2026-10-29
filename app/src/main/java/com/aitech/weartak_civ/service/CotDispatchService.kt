/*
 * Copyright (C) 2024, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.aitech.weartak_civ.NetworkUtils
import com.aitech.weartak_civ.events.MessageEvent
import com.aitech.weartak_civ.DEFAULT_CONSTANT_REPORTING_INTERVAL
import com.aitech.weartak_civ.Utils.getValueFromJson
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.Utils.stopForegroundServiceCompat
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.integration.plugin.PluginCotEgress
import com.aitech.weartak_civ.data.CompanionAppRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.DataRepository.PhysioData
import com.aitech.weartak_civ.data.DataRepository.SitxServerStatus
import com.aitech.weartak_civ.cot.CotCompanionPayload
import com.aitech.weartak_civ.cot.CotDetail
import com.aitech.weartak_civ.cot.CotEndpointAvailability
import com.aitech.weartak_civ.cot.CotEndpointDispatcher
import com.aitech.weartak_civ.cot.CotDispatchPolicy
import com.aitech.weartak_civ.cot.CotMessageBuilder
import com.aitech.weartak_civ.cot.CotMessageKind
import com.aitech.weartak_civ.cot.CotOutboundMessage
import com.aitech.weartak_civ.events.DittoDocumentEvent
import com.aitech.weartak_civ.events.LocationRcvd
import com.aitech.weartak_civ.events.InteractionLocationRcvd
import com.aitech.weartak_civ.events.InteractionPliRefresh
import com.aitech.weartak_civ.events.PliRefreshReason
import com.aitech.weartak_civ.events.OutboundDittoTrackRefresh
import com.aitech.weartak_civ.events.LocationUpdateRequired
import com.aitech.weartak_civ.events.MessageEvent.Companion.CALLSIGN_TEAM_ROLE_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.CUSTOM_DEVELOPER_CONFIG_JSON_CHANGED
import com.aitech.weartak_civ.manager.SMSManager
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.bhRepo
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableEsriEndpoint
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableTabletCommandEndpoint
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBatdokCot
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.role
import com.aitech.weartak_civ.presentation.MainActivity.Constants.smsNumbersList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamValue
import com.aitech.weartak_civ.presentation.requestBloodhoundComplicationUpdate
import com.aitech.weartak_civ.presentation.requestPhysioStatusAndAlertingComplicationUpdate
import com.aitech.weartak_civ.service.HailService.checkLicense
import com.aitech.weartak_civ.service.HailService.checkLocalHailLicense
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid
import com.aitech.weartak_civ.service.TAKConnectService.Companion.sendDataTAKServers
import com.aitech.weartak_civ.service.sitx.SitxServiceCommand
import com.aitech.weartak_civ.service.sitx.SitxServiceCommandBus
import com.rain.hail.sdk.HailDetail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class CotDispatchService : Service() {
    private val TAG = "WearTAK-" + CotDispatchService::class.java.simpleName
    private var cotDispatchHandler: Handler? = null
    private var cotDispatchThread: HandlerThread? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val alertDispatchMutex = Mutex()
    private val markerDispatchMutex = Mutex()
    private val periodicPliMutex = Mutex()
    private val periodicReportingGate = PeriodicReportingGate()
    private var identityPliJob: kotlinx.coroutines.Job? = null
    private val outboundAnnouncementGate = OutboundPliAnnouncementGate()
    private val outboundAnnouncementMutex = Mutex()
    private var outboundAnnouncementJob: kotlinx.coroutines.Job? = null

    private lateinit var dataRepository: DataRepository
    private var lastLocationUpdateRcvd: Long = 0L

    data class CustomEmergencyAlertWithAddedState(
        var smsSent: Boolean = false,
        val alert: DataRepository.CustomEmergencyAlert
    )

    private val manualAlertMap = java.util.concurrent.ConcurrentHashMap<String, DataRepository.CustomEmergencyAlert>()
    private val selfMarkerRefreshes = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val pendingManualAlertQueue = ArrayDeque<CustomEmergencyAlertWithAddedState>()

    private val physioAndAutoGenAlertMap = java.util.concurrent.ConcurrentHashMap<String, DataRepository.CustomEmergencyAlert>()
    private val pendingPhysioAndAutoGenAlertQueue = ArrayDeque<CustomEmergencyAlertWithAddedState>()

    private var iconSetPath: String = ""
    private var markerType: String = ""
    private val markerDropMap = java.util.concurrent.ConcurrentHashMap<String, DataRepository.DroppedMarker>()
    private val pendingMarkerCotList = java.util.concurrent.CopyOnWriteArrayList<DataRepository.DroppedMarker>()

    private val pendingChatMsgQueue = java.util.concurrent.ConcurrentLinkedDeque<DataRepository.SendChatRequest>()

    private var combatId: Int = 0

    private var sentBatteryWarnChatMsg = false
    private var sentBatteryAlertChatMsg = false

    private val capRepo by lazy {
        try {
            (applicationContext as WearTakApplication).capRepo
        } catch (_: Throwable) {
            CompanionAppRepository(applicationContext)
        }
    }

    private val endpointDispatcher by lazy {
        CotEndpointDispatcher(
            sendTak = ::sendDataTAKServers,
            sendSitx = { xml -> SitxServiceCommandBus.trySend(SitxServiceCommand.SendCot(xml)) },
            sendDitto = { collection, xml -> EventBus.getDefault().post(DittoDocumentEvent(collection, xml)) },
            sendMulticast = ::sendMulticastDatagram,
            sendPliMulticast = NetworkUtils.MulticastPublisher::send,
            sendPlugin = { xml ->
                PluginCotEgress.offer(applicationContext as WearTakApplication, xml)
            },
            sendCompanion = { payload ->
                when (payload) {
                    is CotCompanionPayload.Pli -> capRepo.enqueuePliSnapshotForBle(payload.snapshot)
                    is CotCompanionPayload.Chat -> capRepo.enqueueChatSnapshotForBle(payload.snapshot)
                    is CotCompanionPayload.Marker -> capRepo.enqueueMarkerSnapshotForBle(payload.snapshot)
                    is CotCompanionPayload.OwnedPointDeletion ->
                        error("Owned point deletion requires a negotiated Companion gateway")
                    is CotCompanionPayload.Emergency -> capRepo.enqueueEmergencySnapshotForBle(payload.snapshot)
                }
            },
        )
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.v(TAG, "CotDispatchService onStartCommand()")
        return START_NOT_STICKY
    }
    val context: Context = mainActivity.applicationContext


    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "CotDispatchService onCreate()")
        isRunning.set(true)
        instance = this
        dataRepository = MainActivity.dataRepository

        startForegroundServiceCompat(
            notificationId = COT_DISPATCH_SERVICE_NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )

        CoroutineScope(Dispatchers.Main).launch {
            setValuesFromCustomDeveloperConfig()
        }

        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }

        startDispatchScheduler()

        startBatteryAlerts()

        collectHailFlows()

        collectReportingIntervalSecs()
        startReportingIntervalRepoBridge()

        startMarkerPipelines()

        startManualAlertPipelines()

        startPhysioAndAutoGenAlertPipelines()

        startSendChatMessageCollector()

        startAnyEndpointConnectedCollector()
        startOutboundPliAnnouncements()
    }

    private fun startAnyEndpointConnectedCollector() {
        serviceScope.launch {
            (applicationContext as WearTakApplication).pluginManager.settingsCoordinator
                .outboundCotAvailable.collect { available ->
                    if (available) {
                        sendAllManualAlerts(null)
                        sendAllPhysioAndAutoGenAlerts(null)
                        sendPendingChatMessages()
                        sendPendingMarkerCots()
                    }
                }
        }
        serviceScope.launch {
            dataRepository.anyEndpointConnected.collect { connected ->
                if (connected) {
                    sendPendingChatMessages()
                    sendPendingMarkerCots()
                }
            }
        }
        serviceScope.launch {
            combine(
                dataRepository.endpointConnected,
                dataRepository.multicastPublisherRunning,
                dataRepository.sitxStatus,
                dataRepository.isCompanionConnected,
                combine(
                    dataRepository.getEnableSitxService,
                    dataRepository.getEnableCompanionAppService,
                ) { sitx, companion -> sitx to companion },
            ) { _, _, _, _, _ -> endpointAvailability() }
                .distinctUntilChanged()
                .collect { availability ->
                    if (CotDispatchPolicy.routesFor(CotMessageKind.EMERGENCY, availability).isNotEmpty()) {
                        sendAllManualAlerts(null)
                        sendAllPhysioAndAutoGenAlerts(null)
                    }
                }
        }
    }

    internal fun outboundReadyRouteFlow(): Flow<Set<String>> {
        val enabled = combine(
            dataRepository.getEnableDittoService, dataRepository.getEnableSitxService,
            dataRepository.getEnableCompanionAppService, dataRepository.getEnableMulticast,
            dataRepository.getEnableTakEndpoint
        ) { ditto, sitx, companion, multicast, tak ->
            buildSet {
                if (tak) add(DataRepository.Endpoint.TAK_SERVER)
                if (ditto) add(DataRepository.Endpoint.DITTO)
                if (sitx) add(DataRepository.Endpoint.SITX)
                if (companion) add(DataRepository.Endpoint.COMPANION_APP)
                if (multicast) add(DataRepository.Endpoint.MULTICAST)
            }
        }
        val policy = combine(dataRepository.getEnableLocationService,
            (applicationContext as WearTakApplication).pluginManager.settingsCoordinator.outboundCotReadyRoutes
        ) { location, plugin -> location to plugin }
        return combine(dataRepository.outboundReadyRoutes, enabled, dataRepository.isCompanionConnected,
            dataRepository.sitxStatus, policy
        ) { routes, allowed, companionConnected, sitx, (locationEnabled, pluginReady) ->
            val companion = companionConnected && DataRepository.Endpoint.COMPANION_APP in allowed
            if (!locationEnabled) emptySet() else buildSet {
                routes.forEach { (key, ready) ->
                    if (ready.endpoint !in allowed) return@forEach
                    if (companion != (ready.endpoint == DataRepository.Endpoint.COMPANION_APP)) return@forEach
                    if (ready.endpoint == DataRepository.Endpoint.TAK_SERVER && !isLicenseValid()) return@forEach
                    if (ready.endpoint == DataRepository.Endpoint.SITX && sitx != SitxServerStatus.CONNECTED) return@forEach
                    add("$key:${ready.generation}")
                }
                if (!companion) pluginReady.forEach { (id, generation) -> add("plugin:$id:$generation") }
            }
        }.distinctUntilChanged()
    }

    private fun startOutboundPliAnnouncements() {
        collectOutboundReadyRoutes(outboundReadyRouteFlow())
    }

    internal fun collectOutboundReadyRoutes(routes: Flow<Set<String>>) {
        serviceScope.launch {
            routes.collect { ready ->
                val added = outboundAnnouncementMutex.withLock {
                    outboundAnnouncementGate.updateReady(ready, android.os.SystemClock.elapsedRealtimeNanos())
                }
                if (added) {
                    outboundAnnouncementJob?.cancel()
                    outboundAnnouncementJob = serviceScope.launch {
                        kotlinx.coroutines.delay(150)
                        if (outboundAnnouncementGate.hasPending() &&
                            dataRepository.getEnableLocationService.first()) {
                            dataRepository.requestInteractionPliRefresh(PliRefreshReason.OUTBOUND_ROUTE_READY)
                        }
                    }
                }
            }
        }
    }

    private suspend fun readyPliAnnouncementAvailability(): CotEndpointAvailability {
        val ready = dataRepository.endpointConnected.value
        val available = endpointAvailability()
        return available.copy(
            tak = available.tak && DataRepository.Endpoint.TAK_SERVER in ready &&
                dataRepository.getEnableTakEndpoint.first(),
            sitx = available.sitx && DataRepository.Endpoint.SITX in ready,
            ditto = DataRepository.Endpoint.DITTO in ready && dataRepository.getEnableDittoService.first(),
            multicast = dataRepository.multicastPublisherRunning.value && dataRepository.getEnableMulticast.first()
        )
    }

    private suspend fun announceOutboundPli(location: android.location.Location): Boolean =
        outboundAnnouncementMutex.withLock {
            if (!outboundAnnouncementGate.canDispatch(location, android.os.SystemClock.elapsedRealtimeNanos()))
                return@withLock false
            if (!locationAnnouncementAllowed() || !dataRepository.isCurrentLocationInput(location)) {
                Log.i(TAG, "Outbound PLI announcement awaits permitted location input")
                return@withLock false
            }
            val snapshot = dataRepository.snapshotOutboundAnnouncement(location)
            val message = CotOutboundMessage(buildPliCot(snapshot), CotMessageKind.PLI, CotCompanionPayload.Pli(snapshot))
            if (!outboundAnnouncementGate.canDispatch(location, android.os.SystemClock.elapsedRealtimeNanos()) ||
                !locationAnnouncementAllowed() || !dataRepository.isCurrentLocationInput(location))
                return@withLock false
            val available = readyPliAnnouncementAvailability()
            val accepted = if (available.companion && capRepo.gateway.session != null) {
                endpointDispatcher.dispatchGateway(message) { capRepo.offerGatewayCot(message.xml) }
            } else endpointDispatcher.dispatch(message, available, readyPliOnly = true)
            val ditto = available.ditto && !available.companion
            if (ditto) EventBus.getDefault().post(OutboundDittoTrackRefresh(android.location.Location(location)))
            if (accepted || ditto) {
                outboundAnnouncementGate.complete()
                Log.i(TAG, "Announced current self PLI after outbound route became usable")
                true
            } else {
                Log.i(TAG, "Outbound PLI announcement remains pending; no ready route accepted it")
                false
            }
        }

    private suspend fun locationAnnouncementAllowed(): Boolean =
        dataRepository.getEnableLocationService.first() &&
            (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED)

    private fun startReportingIntervalRepoBridge() {
        serviceScope.launch {
            var wasStationary = stationaryLocation
            actualReportingIntervalSecsFlow
                .collect { secs ->
                    val previous = dataRepository.almightyReportingIntervalSecs.value
                    dataRepository.setAlmightyReportingInterval(secs)
                    // Advertise the longer stale window before deferring the next stationary fix.
                    if (stationaryLocation && secs > previous) buildAndSendPhysioLocationCotMsg()
                    if (secs < previous) {
                        if (wasStationary) periodicPliMutex.withLock { periodicReportingGate.reset() }
                        cotDispatchHandler?.removeCallbacks(cotDispatchRunnable)
                        cotDispatchHandler?.post(cotDispatchRunnable)
                    }
                    wasStationary = stationaryLocation
                }
        }
    }

    // The companion uses this interval as its BLE connection heartbeat.
    private fun collectReportingIntervalSecs() {
        serviceScope.launch {
            dataRepository.almightyReportingIntervalSecs
                .collect{ interval ->
                    Log.v(TAG, "Almighty reporting interval in DR has changed to -> $interval Seconds")
                    Log.v(TAG, "Cotdispatch Companion Obj: $desiredReportingIntervalSecs")
                    if (companionAppAvailable()) {
                        Log.v(TAG, "Sending to companion app via BLE")
                        capRepo.enqueueReportIntForBle(interval)
                    }
                }
        }
    }

    private fun startBatteryAlerts() {
        serviceScope.launch {
            dataRepository.latestBatteryPercentage
                .filterNotNull()
                .collect { batt ->
                    val batteryAlertsEnabled = dataRepository.getEnableBatteryAlerts.first()
                    if (!batteryAlertsEnabled) return@collect

                    val charging = dataRepository.chargingState.value
                    if (charging) {
                        if (batt > MainActivity.battAlertPercent) {
                            sentBatteryAlertChatMsg = false
                        } else if (batt > MainActivity.battWarnPercent) {
                            sentBatteryWarnChatMsg = false
                        }
                        return@collect
                    }

                    val endpointsToSendTo = (TAKConnectService.isAnyTakConnected || (dataRepository.sitxStatus.value == DataRepository.SitxServerStatus.CONNECTED) || dataRepository.multicastPublisherRunning.value || companionAppAvailable() || pluginAvailable())

                    if (endpointsToSendTo) {
                        if ((batt <= MainActivity.battWarnPercent)) {
                            Log.i(TAG, "Battery is at or below the WARN threshold: $batt <= ${MainActivity.battWarnPercent}")

                            if (batt > MainActivity.battAlertPercent) {
                                Log.i(TAG, "Battery is at or below the ALERT threshold: $batt <= ${MainActivity.battWarnPercent}")
                                if (!sentBatteryWarnChatMsg) {
                                    val msgText =
                                        "Watch Battery Warning: ${MainActivity.callsign} battery less than " +
                                                "${MainActivity.battWarnPercent}% (actual=$batt%)"
                                    val chatSnap = dataRepository.snapshotChatEvent(
                                        roomUid = "All Chat Rooms",
                                        roomTitle = "All Chat Rooms",
                                        message = msgText
                                    )

                                    val chatCot = CotMessageBuilder.chat(chatSnap, callsign, MainActivity.device_uid)
                                    Log.d(TAG, "Sending chat msg to 'All Chat Rooms' via snapshot: $chatCot")
                                    sendDataTAKServers(chatCot, true)
                                    if (companionAppAvailable()) {
                                        capRepo.enqueueChatSnapshotForBle(chatSnap)
                                    }
                                    if (dataRepository.multicastPublisherRunning.value) {
                                        sendMulticastDatagram(chatCot)
                                    }
                                    SitxServiceCommandBus.trySend(SitxServiceCommand.SendCot(chatCot))

                                    sentBatteryWarnChatMsg = true
                                    dataRepository.requestInteractionPliRefresh(PliRefreshReason.CHAT_SENT)
                                }
                            } else {
                                if (!sentBatteryAlertChatMsg) {
                                    val msgText =
                                        "Battery ALERT: ${MainActivity.callsign} battery less than " +
                                                "${MainActivity.battAlertPercent}% (actual=$batt%)"
                                    val chatSnap = dataRepository.snapshotChatEvent(
                                        roomUid = "All Chat Rooms",
                                        roomTitle = "All Chat Rooms",
                                        message = msgText
                                    )

                                    val chatCot = CotMessageBuilder.chat(chatSnap, callsign, MainActivity.device_uid)
                                    Log.d(TAG, "Sending chat msg to 'All Chat Rooms' via snapshot: $chatCot")
                                    sendDataTAKServers(chatCot, true)
                                    if (companionAppAvailable()) {
                                        capRepo.enqueueChatSnapshotForBle(chatSnap)
                                    }
                                    if (dataRepository.multicastPublisherRunning.value) {
                                        sendMulticastDatagram(chatCot)
                                    }
                                    SitxServiceCommandBus.trySend(SitxServiceCommand.SendCot(chatCot))

                                    sentBatteryAlertChatMsg = true
                                    dataRepository.requestInteractionPliRefresh(PliRefreshReason.CHAT_SENT)
                                }
                            }
                        } else {
                            sentBatteryWarnChatMsg = false
                            sentBatteryAlertChatMsg = false
                        }
                    }
            }
        }
    }

    private fun startWearTakDebugAlerts() {
        serviceScope.launch {
            dataRepository.getEnableWearTakDevMode
                .filterNotNull()
                .collect { enabled ->
                    val sendToTak = TAKConnectService.isAnyTakConnected || (dataRepository.sitxStatus.value == DataRepository.SitxServerStatus.CONNECTED)
                    val sendToCompApp = companionAppAvailable()
                    val sendOverMulticast = dataRepository.multicastPublisherRunning.value
                    val endpointsToSendTo = sendToTak || sendToCompApp || sendOverMulticast || pluginAvailable()


                    if (endpointsToSendTo && enabled) {
                        val msg = "Debug mode changed to { $enabled } on Watch: $callsign with uid: $deviceId"
                        val chatSnap = dataRepository.snapshotChatEvent(
                            roomUid = "All Chat Rooms",
                            roomTitle = "All Chat Rooms",
                            message = msg
                        )

                        if (sendToTak || sendOverMulticast) {
                            val chatCot = CotMessageBuilder.chat(chatSnap, callsign, MainActivity.device_uid)
                            if (sendToTak) {
                                sendDataTAKServers(chatCot, true)
                                SitxServiceCommandBus.trySend(SitxServiceCommand.SendCot(chatCot))
                            }
                            if (sendOverMulticast) {
                                sendMulticastDatagram(chatCot)
                            }
                            Log.d(TAG, "Sending chat msg to 'All Chat Rooms' via snapshot: $chatCot")
                        }

                        if (sendToCompApp) {
                            capRepo.enqueueChatSnapshotForBle(chatSnap)
                        }
                        dataRepository.requestInteractionPliRefresh(PliRefreshReason.CHAT_SENT)
                    }
                }
        }
    }

    private fun startManualAlertPipelines() {
        startAddManualAlertCollector()
        startClearManualAlertCollector()
    }

    private suspend fun hasUsableEmergencyRoute(): Boolean =
        CotDispatchPolicy.routesFor(CotMessageKind.EMERGENCY, endpointAvailability()).isNotEmpty()

    private suspend fun readyToSendAlertEvents(): Boolean = hasUsableEmergencyRoute()

    private fun startAddManualAlertCollector() {
        serviceScope.launch {
            dataRepository.addManualAlert.collect { req ->
                Log.i(TAG, "Received req to add manual alert: $req")
                val manualAlert = DataRepository.CustomEmergencyAlert(uuid = UUID.randomUUID().toString(), alertState = DataRepository.AlertState.ALERT, category = req.category, description = req.description, priority = 1, sendSms = true, timeMillis = req.timeMillis, enqueuedToBeSent = false)
                val manualAlertWithState = CustomEmergencyAlertWithAddedState(smsSent = false, alert = manualAlert)
                alertDispatchMutex.withLock {
                    manualAlertMap[manualAlert.uuid] = manualAlert
                    if (readyToSendAlertEvents()) {
                        sendAllManualAlertsLocked(manualAlertWithState)
                    } else {
                        enqueueManualAlertAndUpdateUI(manualAlertWithState)
                    }
                }
            }
        }
    }

    private suspend fun sendPendingManualAlerts(): Boolean {
        var sentAll = true
        Log.i(TAG, "pendingManualAlertQueue.size=${pendingManualAlertQueue.size}")
        repeat(pendingManualAlertQueue.size) {
            val alertWithAddedState = pendingManualAlertQueue.first()
            Log.i(TAG, "Trying to send pending manual alert: $alertWithAddedState")
            val sent = sendEmergencyAlert(alertWithAddedState)
            pendingManualAlertQueue.removeFirst()
            if (sent) {
                if (alertWithAddedState.alert.alertState == DataRepository.AlertState.ALERT) {
                    dataRepository.changeManualAlertEnqueueStatusUid(alertWithAddedState.alert.uuid, false)
                } else {
                    dataRepository.removeManualAlertByUid(alertWithAddedState.alert.uuid)
                }
            } else {
                sentAll = false
                pendingManualAlertQueue.addLast(alertWithAddedState)
            }
        }
        return sentAll
    }
    private suspend fun sendAllManualAlerts(manualAlertWithState: CustomEmergencyAlertWithAddedState?) {
        alertDispatchMutex.withLock {
            sendAllManualAlertsLocked(manualAlertWithState)
        }
    }

    private suspend fun sendAllManualAlertsLocked(manualAlertWithState: CustomEmergencyAlertWithAddedState?) {
        sendPendingManualAlerts()

        if (null == manualAlertWithState) {
            manualAlertMap.values.forEach { sendEmergencyAlert(CustomEmergencyAlertWithAddedState(smsSent = true, alert = it)) }
            return
        }

        if (sendEmergencyAlert(manualAlertWithState)) {
            if (manualAlertWithState.alert.alertState == DataRepository.AlertState.ALERT) {
                dataRepository.appendManualAlert(manualAlertWithState.alert)
            } else {
                dataRepository.removeManualAlertByUid(manualAlertWithState.alert.uuid)
            }
        } else {
            enqueueManualAlertAndUpdateUI(manualAlertWithState)
        }
    }

    private suspend fun enqueueManualAlertAndUpdateUI(manualAlertWithState: CustomEmergencyAlertWithAddedState) {
        if (pendingManualAlertQueue.any {
                it.alert.uuid == manualAlertWithState.alert.uuid && it.alert.alertState == manualAlertWithState.alert.alertState
            }) return
        resumeWatchTransportsForPendingOutbound()
        val pendingManualAlert = manualAlertWithState.alert.copy(enqueuedToBeSent = true)
        val pendingManualAlertWithState = CustomEmergencyAlertWithAddedState(smsSent = manualAlertWithState.smsSent, alert = pendingManualAlert)
        Log.i(TAG, "===> adding to pendingManualAlertQueue=$pendingManualAlert...")
        pendingManualAlertQueue.addLast(pendingManualAlertWithState)
        Log.i(TAG, "===> updating UI...")
        if (pendingManualAlert.alertState == DataRepository.AlertState.ALERT) {
            dataRepository.appendManualAlert(pendingManualAlert)
        } else {
            dataRepository.changeManualAlertToClearAndEnqueueStatusUid(pendingManualAlert.uuid, true)
        }
    }

    private fun startClearManualAlertCollector() {
        serviceScope.launch {
            dataRepository.clearManualAlert.collect { req ->
                Log.i(TAG, "Received req to clear manual alert: $req")
                when (req) {
                    is DataRepository.ClearRequest.Specific -> handleClearManualAlertUid(req.uid)
                    DataRepository.ClearRequest.Last -> {
                        val manualAlerts = dataRepository.manualAlertList.value.filter { it.alertState == DataRepository.AlertState.ALERT }
                        if (manualAlerts.isNotEmpty()) {
                            val last = manualAlerts.maxByOrNull { it.timeMillis }
                            if (last != null) {
                                Log.i(TAG, "Clearing last manual alert: $last")
                                handleClearManualAlertUid(last.uuid)
                            }
                        }
                    }
                    DataRepository.ClearRequest.All -> handleClearManualAlertAll()
                }
            }
        }
    }

    private suspend fun handleClearManualAlertUid(uid: String) {
        alertDispatchMutex.withLock {
            handleClearManualAlertUidLocked(uid)
        }
    }

    private suspend fun handleClearManualAlertUidLocked(uid: String) {
        val manualAlert = manualAlertMap[uid]
        if (null == manualAlert) return

        val manualAlertClearWithState = CustomEmergencyAlertWithAddedState(smsSent = false, alert = manualAlert.copy(alertState = DataRepository.AlertState.CANCEL))
        pendingManualAlertQueue.removeAll { it.alert.uuid == uid && it.alert.alertState == DataRepository.AlertState.ALERT }
        if (readyToSendAlertEvents()) {
            sendAllManualAlertsLocked(manualAlertClearWithState)
        } else {
            enqueueManualAlertAndUpdateUI(manualAlertClearWithState)
        }
        manualAlertMap.remove(uid)
    }

    private suspend fun handleClearManualAlertAll() {
        val manualAlerts = dataRepository.manualAlertList.value.filter { it.alertState == DataRepository.AlertState.ALERT }
        if (manualAlerts.isNotEmpty()) {
            val uids = manualAlerts.map { it.uuid }
            uids.forEach { uid ->
                handleClearManualAlertUid(uid)
            }
        }
    }

    private fun startPhysioAndAutoGenAlertPipelines() {
        Log.i(TAG, "Starting physioAndAutoGen alert pipelines")
        startAddPhysioAndAutoGenAlertCollector()
        startClearPhysioAndAutoGenAlertCollector()
    }

    private fun startAddPhysioAndAutoGenAlertCollector() {
        Log.i(TAG, "Subscribing to addPhysioAndAutoGenAlert flow")
        serviceScope.launch {
            dataRepository.addPhysioAndAutoGenAlert.collect { req ->
                Log.i(TAG, "Received req to add physio and auto generated alert: $req")
                Log.i(
                    TAG,
                    "Alert routing state before dispatch: ready=${readyToSendAlertEvents()} " +
                        "tak=${TAKConnectService.isAnyTakConnected} ditto=${DittoService.isRunning.get()} " +
                        "multicast=${dataRepository.multicastPublisherRunning.value} sitx=${sitxAvailable()} " +
                        "endpoints=${dataRepository.anyEndpointConnected.value}",
                )
                val physioAndAutoGenAlert = DataRepository.CustomEmergencyAlert(uuid = req.uuid, alertState = DataRepository.AlertState.ALERT, category = req.category, description = req.description, priority = req.priority, sendSms = req.sendSms, timeMillis = req.timeMillis, enqueuedToBeSent = false)
                val physioAndAutoGenAlertAlertWithState = CustomEmergencyAlertWithAddedState(smsSent = false, alert = physioAndAutoGenAlert)
                alertDispatchMutex.withLock {
                    physioAndAutoGenAlertMap[physioAndAutoGenAlert.uuid] = physioAndAutoGenAlert
                    if (readyToSendAlertEvents()) {
                        Log.i(TAG, "Alert is ready to send now: uid=${physioAndAutoGenAlert.uuid}")
                        sendAllPhysioAndAutoGenAlertsLocked(physioAndAutoGenAlertAlertWithState)
                    } else {
                        Log.i(TAG, "Alert queued locally until routing becomes available uid=${physioAndAutoGenAlert.uuid}")
                        enqueuePhysioAndAutoGenAlertAndUpdateUI(physioAndAutoGenAlertAlertWithState)
                    }
                }
            }
        }
    }

    private suspend fun sendPendingPhysioAndAutoGenAlerts(): Boolean {
        var sentAll = true
        Log.i(TAG, "pendingPhysioAndAutoGenAlertQueue.size=${pendingPhysioAndAutoGenAlertQueue.size}")
        repeat(pendingPhysioAndAutoGenAlertQueue.size) {
            val alertWithAddedState = pendingPhysioAndAutoGenAlertQueue.first()
            Log.i(TAG, "Trying to send pending physio and auto gen alert: $alertWithAddedState")
            val sent = sendEmergencyAlert(alertWithAddedState)
            pendingPhysioAndAutoGenAlertQueue.removeFirst()
            if (sent) {
                if (alertWithAddedState.alert.alertState == DataRepository.AlertState.ALERT) {
                    dataRepository.changePhysioAndAutoGenAlertEnqueueStatusUid(alertWithAddedState.alert.uuid, false)
                } else {
                    dataRepository.removePhysioAndAutoGenAlertByUid(alertWithAddedState.alert.uuid)
                }
            } else {
                sentAll = false
                pendingPhysioAndAutoGenAlertQueue.addLast(alertWithAddedState)
            }
        }
        return sentAll
    }
    private suspend fun sendAllPhysioAndAutoGenAlerts(alertWithState: CustomEmergencyAlertWithAddedState?) {
        alertDispatchMutex.withLock {
            sendAllPhysioAndAutoGenAlertsLocked(alertWithState)
        }
    }

    private suspend fun sendAllPhysioAndAutoGenAlertsLocked(alertWithState: CustomEmergencyAlertWithAddedState?) {
        sendPendingPhysioAndAutoGenAlerts()

        if (null == alertWithState) {
            physioAndAutoGenAlertMap.values.forEach { sendEmergencyAlert(CustomEmergencyAlertWithAddedState(smsSent = true, alert = it)) }
            return
        }

        if (sendEmergencyAlert(alertWithState)) {
            if (alertWithState.alert.alertState == DataRepository.AlertState.ALERT) {
                dataRepository.appendPhysioAndAutoGenAlert(alertWithState.alert)
            } else {
                dataRepository.removePhysioAndAutoGenAlertByUid(alertWithState.alert.uuid)
            }
        } else {
            enqueuePhysioAndAutoGenAlertAndUpdateUI(alertWithState)
        }
    }

    private suspend fun enqueuePhysioAndAutoGenAlertAndUpdateUI(alertWithState: CustomEmergencyAlertWithAddedState) {
        if (pendingPhysioAndAutoGenAlertQueue.any {
                it.alert.uuid == alertWithState.alert.uuid && it.alert.alertState == alertWithState.alert.alertState
            }) return
        resumeWatchTransportsForPendingOutbound()
        val pendingAlert = alertWithState.alert.copy(enqueuedToBeSent = true)
        val pendingAlertWithState = CustomEmergencyAlertWithAddedState(smsSent = alertWithState.smsSent, alert = pendingAlert)
        Log.i(TAG, "===> adding to pendingPhysioAndAutoGenAlertQueue=$pendingAlert...")
        pendingPhysioAndAutoGenAlertQueue.addLast(pendingAlertWithState)
        Log.i(TAG, "===> updating UI...")
        if (pendingAlert.alertState == DataRepository.AlertState.ALERT) {
            dataRepository.appendPhysioAndAutoGenAlert(pendingAlert)
        } else {
            dataRepository.changePhysioAndAutoGenAlertToClearAndEnqueueStatusUid(pendingAlert.uuid, true)
        }

    }

    private fun startClearPhysioAndAutoGenAlertCollector() {
        Log.i(TAG, "Subscribing to clearPhysioAndAutoGenAlert flow")
        serviceScope.launch {
            dataRepository.clearPhysioAndAutoGenAlert.collect { req ->
                Log.i(TAG, "Received req to clear physio or auto gen alert: $req")
                when (req) {
                    is DataRepository.ClearRequest.Specific -> handleClearPhysioAndAutoGenAlertUid(req.uid)
                    DataRepository.ClearRequest.Last -> {
                        val physioAndAutoGenAlerts = dataRepository.physioAndAutoGenAlertList.value.filter { it.alertState == DataRepository.AlertState.ALERT }
                        if (physioAndAutoGenAlerts.isNotEmpty()) {
                            val last = physioAndAutoGenAlerts.maxByOrNull { it.timeMillis }
                            if (last != null) {
                                Log.i(TAG, "Clearing last physio or auto gen alert: $last")
                                handleClearPhysioAndAutoGenAlertUid(last.uuid)
                            }
                        }
                    }
                    DataRepository.ClearRequest.All -> handleClearPhysioAndAutoGenAlertAll()
                }
                requestPhysioStatusAndAlertingComplicationUpdate(context)
            }
        }
    }

    private suspend fun handleClearPhysioAndAutoGenAlertUid(uid: String) {
        alertDispatchMutex.withLock {
            handleClearPhysioAndAutoGenAlertUidLocked(uid)
        }
    }

    private suspend fun handleClearPhysioAndAutoGenAlertUidLocked(uid: String) {
        val physioAndAutoGenAlert = physioAndAutoGenAlertMap[uid]
        if (null == physioAndAutoGenAlert) return

        val physioAndAutoGenAlertClearWithState = CustomEmergencyAlertWithAddedState(smsSent = false, alert = physioAndAutoGenAlert.copy(alertState = DataRepository.AlertState.CANCEL))
        pendingPhysioAndAutoGenAlertQueue.removeAll { it.alert.uuid == uid && it.alert.alertState == DataRepository.AlertState.ALERT }
        if (readyToSendAlertEvents()) {
            sendAllPhysioAndAutoGenAlertsLocked(physioAndAutoGenAlertClearWithState)
        } else {
            enqueuePhysioAndAutoGenAlertAndUpdateUI(physioAndAutoGenAlertClearWithState)
        }
        physioAndAutoGenAlertMap.remove(uid)
    }

    private suspend fun handleClearPhysioAndAutoGenAlertAll() {
        val physioAndAutoGenAlerts = dataRepository.physioAndAutoGenAlertList.value.filter { it.alertState == DataRepository.AlertState.ALERT }
        if (physioAndAutoGenAlerts.isNotEmpty()) {
            val uids = physioAndAutoGenAlerts.map { it.uuid }
            uids.forEach { uid ->
                handleClearPhysioAndAutoGenAlertUid(uid)
            }
        }
    }

    private fun startSendChatMessageCollector() {
        serviceScope.launch {
            dataRepository.chatRequestSend.collect { req ->
                if (com.aitech.weartak_civ.command.SensitiveEnrollment.isSensitiveText(req.message)) return@collect
                Log.i(TAG, "Received request to send chat message")
                if (hasConnectedEndpoint()) {
                    sendAllChatMessages(req)
                } else {
                    resumeWatchTransportsForPendingOutbound()
                    pendingChatMsgQueue.addLast(req)
                    Log.i(TAG, "pendingChatMsgQueue=${pendingChatMsgQueue.size}")
                }
            }
        }
    }

    private suspend fun sendPendingChatMessages(): Boolean {
        pendingChatMsgQueue.removeAll {
            com.aitech.weartak_civ.command.SensitiveEnrollment.isSensitiveText(it.message)
        }
        var sentAll = true
        Log.i(TAG, "pendingChatMsgQueue.size=${pendingChatMsgQueue.size}")
        while (pendingChatMsgQueue.isNotEmpty()) {
            val chatMsg = pendingChatMsgQueue.peekFirst() ?: break
            Log.i(TAG, "Trying to send pending chat message: $chatMsg")
            if (sendChatMessage(chatMsg)) {
                pendingChatMsgQueue.remove(chatMsg)
            } else {
                sentAll = false
                break
            }
        }
        return sentAll
    }

    private suspend fun sendChatMessage(chatMsg: DataRepository.SendChatRequest): Boolean {
        if (com.aitech.weartak_civ.command.SensitiveEnrollment.isSensitiveText(chatMsg.message)) {
            Log.w(TAG, "Sensitive enrollment command discarded before chat transmission")
            return false
        }
        val chatSnap = dataRepository.snapshotChatEvent(
            roomUid = chatMsg.dstUid, roomTitle = chatMsg.dstChatroom, message = chatMsg.message,
            isGroup = chatMsg.isGroup, participantUids = chatMsg.participantUids
        )
        val chatCotXml = try {
            CotMessageBuilder.chat(chatSnap, callsign, MainActivity.device_uid)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Cannot route chat to ${chatSnap.roomUid}: ${error.message}")
            return false
        }
        val sent = dispatch(
            CotOutboundMessage(chatCotXml, CotMessageKind.CHAT, CotCompanionPayload.Chat(chatSnap)),
        )

        if (sent) {
            Log.i(TAG, "sendChatMessage: Sent chat message")
        } else {
            Log.i(TAG, "sendChatMessage: Could NOT send chat message")
        }

        return sent
    }

    @Subscribe
    fun onPurgeSensitiveEnrollments(@Suppress("UNUSED_PARAMETER") event:
        com.aitech.weartak_civ.command.PurgeSensitiveEnrollmentCopies) {
        pendingChatMsgQueue.removeAll {
            com.aitech.weartak_civ.command.SensitiveEnrollment.isSensitiveText(it.message)
        }
    }

    private suspend fun sendAllChatMessages(chatReq: DataRepository.SendChatRequest?) {
        val sentPendingChatMessages = sendPendingChatMessages()

        if (null == chatReq) return
        if (com.aitech.weartak_civ.command.SensitiveEnrollment.isSensitiveText(chatReq.message)) return

        if (sentPendingChatMessages) {
            if (!sendChatMessage(chatReq)) {
                pendingChatMsgQueue.addLast(chatReq)
                Log.i(TAG, "pendingChatMsgQueue=${pendingChatMsgQueue.size}")
            }
        }
    }


    private fun startMarkerPipelines() {
        startMarkerDropCollector()
        startMarkerEditCollector()
        startMarkerClearCollector()
        startMarkerDropListCollector()
    }

    private fun startMarkerDropCollector() {
        serviceScope.launch {
            dataRepository.markerDropRequests.collect { req ->
                Log.i(TAG, "Received drop request...")
                val atSelf = req.lat == null || req.lon == null
                val location = if (atSelf) dataRepository.snapshotLocation(dataRepository.bestKnownLocation())
                    else dataRepository.snapshotMarkerLocation(req.uid, req.lat, req.lon, req.timeMillis)
                val waitingForUpdatedLocation = atSelf && location == null && !phoneSuppliesLocation()
                val droppedMarker = DataRepository.DroppedMarker(cotType = DataRepository.MarkerCotType.DROP, uid = req.uid.ifEmpty{ UUID.randomUUID().toString() }, markerType = req.markerType,
                    title = finalMarkerTitle(req.title), remark = req.remark, timeDropped = req.timeMillis,
                    location = location, enqueuedToBeSent = true, waitingForUpdatedLocation = waitingForUpdatedLocation)

                try {
                    dataRepository.appendDroppedMarker(droppedMarker)
                } catch (error: Exception) {
                    Log.e(TAG, "Point creation blocked: could not persist local ownership", error)
                    return@collect
                }
                enqueueMarkerDrop(droppedMarker)
                markerDropMap[droppedMarker.uid] = droppedMarker
                if (atSelf) {
                    selfMarkerRefreshes[droppedMarker.uid] = android.os.SystemClock.elapsedRealtimeNanos()
                    dataRepository.requestInteractionPliRefresh(PliRefreshReason.SELF_MARKER)
                }

                    if (waitingForUpdatedLocation) {
                    Log.i(TAG, "Drop lat(${req.lat}) and lon(${req.lon}) is null or location is stale. En-queued marker drop...")
                } else {
                    Log.i(TAG, "Drop lat(${req.lat}) and lon(${req.lon}) NOT null or location is fresh. Trying to send marker drop request now if possible...")
                    if (hasConnectedEndpoint()) {
                        sendPendingMarkerCots()
                    }
                }
            }
        }
    }

    private fun startMarkerEditCollector() {
        serviceScope.launch {
            dataRepository.markerEditRequests.collect { req ->
                Log.i(TAG, "Received marker edit request for uid=${req.uid}")
                val markerDrop = markerDropMap[req.uid]
                if (null == markerDrop) {
                    Log.w(TAG, "Cannot find marker drop for uid=${req.uid}. Ignoring this...")
                } else if (markerDrop.cotType == DataRepository.MarkerCotType.CLEAR) {
                    Log.w(TAG, "Cannot edit a marker that has already scheduled to be cleared. Ignoring this...")
                } else {
                    val bestLocation = if (req.updateLocation) dataRepository.snapshotLocation(dataRepository.bestKnownLocation()) else markerDrop.location
                    val waitingForUpdatedLocation = bestLocation == null && !phoneSuppliesLocation()
                    val editedMarkerDrop = markerDrop.copy(cotType = DataRepository.MarkerCotType.EDIT, markerType = req.markerType, title = finalMarkerTitle(req.title),
                        remark = req.remark, timeDropped = req.timeMillis, location = bestLocation,
                        enqueuedToBeSent = true, waitingForUpdatedLocation = waitingForUpdatedLocation)
                    enqueueMarkerEdit(editedMarkerDrop)
                    markerDropMap[req.uid] = editedMarkerDrop
                    if (req.updateLocation) {
                        selfMarkerRefreshes[req.uid] = android.os.SystemClock.elapsedRealtimeNanos()
                        dataRepository.requestInteractionPliRefresh(PliRefreshReason.SELF_MARKER)
                    }
                    if (waitingForUpdatedLocation) {
                        Log.i(TAG, "Marker Edit updateLocation(=${req.updateLocation}) is true or previously waitingForUpdatedLocation(=${markerDrop.waitingForUpdatedLocation}) is true. En-queued marker edit...")
                    } else {
                        Log.i(TAG, "Marker edit waitingForUpdatedLocation is false. Trying to send edit marker request now if possible...")
                        if (hasConnectedEndpoint()) {
                            sendPendingMarkerCots()
                        }
                    }
                }
            }
        }
    }

    private fun readyToSendMarkerEvents(): Boolean {
        return (hasConnectedEndpoint() && isLocationFresh())
    }

    private fun pluginAvailable(): Boolean =
        PluginCotEgress.hasSubscribers(applicationContext as WearTakApplication)

    private fun hasConnectedEndpoint(): Boolean =
        dataRepository.anyEndpointConnected.value || pluginAvailable()

    private fun isLocationFresh(): Boolean {
        val loc = dataRepository.latestLocation.value
        val fresh = (System.currentTimeMillis() - lastLocationUpdateRcvd) < FRESH_GPS_INTERVAL_MILLIS
        return (null != loc) && fresh
    }

    private fun startMarkerDropListCollector() {
        serviceScope.launch {
            dataRepository.droppedMarkerList.collect { list ->
                logMarkerDropList(list)
            }
        }
    }

    private fun logMarkerDropList(list: List<DataRepository.DroppedMarker>) {
        if (list.isEmpty()) {
            Log.v(TAG, "Dropped Marker List is empty")
        } else {
            Log.v(TAG, "Dropped Marker List updated (${list.size} items):")
            list.forEachIndexed { index, marker ->
                Log.v(TAG, "[$index] cot=${marker.cotType} (locUpdate=${marker.waitingForUpdatedLocation}, q=${marker.enqueuedToBeSent}) : ${marker.timeDropped}, uid=${marker.uid}, type=${marker.markerType}, time=${marker.timeDropped}, title=${marker.title}, remark=${marker.remark}, loc=(${marker.location?.lat ?: "null"}, ${marker.location?.lon ?: "null"})")
            }
        }
    }

    private fun startMarkerClearCollector() {
        serviceScope.launch {
            dataRepository.remotePointDeletions.collect { uid ->
                markerDropMap.remove(uid)
                pendingMarkerCotList.removeAll { it.uid == uid }
            }
        }
        serviceScope.launch {
            dataRepository.clearMarkerRequests.collect { req ->
                Log.i(TAG, "Received req to clear marker: $req")
                when (req) {
                    is DataRepository.ClearRequest.Specific -> handleClearMarkerUid(req.uid)
                    DataRepository.ClearRequest.Last -> {
                        val last = dataRepository.droppedMarkerList.value.maxByOrNull { it.timeDropped }
                        if (last != null) handleClearMarkerUid(last.uid)
                    }
                    DataRepository.ClearRequest.All -> handleClearMarkerAll()
                }
            }
        }
    }

    private fun startDispatchScheduler() {
        Log.v(TAG, "Starting the dispatch scheduler")
        cotDispatchThread = HandlerThread("CotDispatchThread")
        cotDispatchThread?.start()
        cotDispatchHandler = cotDispatchThread?.looper?.let { Handler(it) }

        cotDispatchHandler?.postDelayed(cotDispatchRunnable, DISPATCH_INTERVAL)
    }

    private fun stopDispatchScheduler() {
        Log.v(TAG, "Stopping the dispatch Scheduler")
        cotDispatchHandler?.removeCallbacks(cotDispatchRunnable)
        cotDispatchThread?.quitSafely()
    }

    private val cotDispatchRunnable = object : Runnable {
        override fun run() {
            cotDispatchHandler?.removeCallbacks(this)
            val dynamic = runBlocking { dataRepository.getEnableDynamicReportingStrategy.first() }
            if (dynamic || (System.currentTimeMillis()-lastLocationUpdateRcvd) > actualReportingIntervalSecs * 1000L) {
                // Fused location can update more slowly indoors, so dispatch when the reporting deadline lapses.
                buildAndSendPhysioLocationCotMsg(periodic = true)
            }
            val delayMillis = if (dynamic) runBlocking {
                periodicPliMutex.withLock {
                    periodicReportingGate.delayMillis(android.os.SystemClock.elapsedRealtimeNanos(),
                        actualReportingIntervalSecs)
                }
            } else actualReportingIntervalSecs * 1000L
            cotDispatchHandler?.postDelayed(this, delayMillis)
        }
    }

    data class BleLocationOutput(
        val lat: Double,
        val lon: Double,
        val hae: Double,
        val ce: Float // Horizontal Error
    )

    private suspend fun buildPliCot(snapshot: DataRepository.PliSnapshot): String {
        val callsign = snapshot.systemData.callsign
        val role = snapshot.systemData.role
        val teamValue = snapshot.systemData.team
        val compatibilityPayloads = LegacyPliPayloadBuilder.build(
            snapshot,
            LegacyPliPayloadBuilder.Options(
                callsign = callsign,
                role = role,
                team = teamValue,
                includeActivityMetrics = MainActivity.enablePhysiologicalServices &&
                    (MainActivity.enableActRecService || MainActivity.enableDynamicReportingStrategy),
                buildEsri = enableEsriEndpoint,
                buildTabletCommand = enableTabletCommandEndpoint,
            ),
        )
        compatibilityPayloads.esriGeoJson?.let {
            esriGeojsonMsg = it
            Log.d(TAG, "esriGeojsonMsg: $it")
        }
        compatibilityPayloads.tabletCommandJson?.let { tabletCommandJsonMsg = it }

        val hailDetail = buildHailDetail(snapshot.physioData.data, callsign)
        val xml = CotMessageBuilder.pli(
            snapshot,
            CotMessageBuilder.PliOptions(callsign, role, teamValue, enableBatdokCot, hailDetail,
                dittoMetadata = DittoService.currentCotMetadata()),
        )
        Log.e("WEARTAK_COT_XML", "XML=$xml")
        return xml
    }

    private var gateCtr = 0
    private fun lgGate(msg: String) = Log.d(TAG, "[gate#${++gateCtr}] $msg")

    private fun collectHailFlows() {
        serviceScope.launch {
            dataRepository.hailLicenseInfoFlow.first()?.let { HailService.hailLicenseInfo = it }
        }

        serviceScope.launch {
            dataRepository.hailCheckLicenseButtonPressed
                .collect { pressed ->
                    if (pressed) {
                        dataRepository.hailCheckLicenseButtonPressed.emit(false)
                        if (!dataRepository.getEnableHailService.first()) {
                            return@collect
                        }
                        Log.d(TAG,"Hail Check Button pressed ... Entering CHECKING state")
                        checkLicense {
                            checkLocalHailLicense()
                        }
                    }
                }
        }

        serviceScope.launch {
            dataRepository.getCombatId
                .collect { id -> combatId = id ?: 0 }
        }

        serviceScope.launch {
            dataRepository.hailInfoStateFlow
                .collect { info ->
                    lgGate("""
                    hailInfoStateFlow changed:
                    %-20s %s
                    %-20s %s
                    %-20s %s
                    %-20s %s
                    %-20s %s
                    %-20s %s
                    """.trimIndent().format(
                        "Pairing code:", info?.pairingCode ?: "null",
                        "Expiration date:", info?.expirationDate ?: "null",
                        "Soft exp. date:", info?.softExpirationDate ?: "null",
                        "Account number:", info?.accountNumber ?: "null",
                        "License ID:", info?.licenseId ?: "null",
                        "Device ID:", info?.deviceId ?: "null"
                    )
                    )
                }
        }
    }

    private fun buildHailDetail(physioData: PhysioData, callsign: String = MainActivity.callsign): String {
        val licenseValid = HailService.isHailLicenseValid()
        if (!dataRepository.buildHailDetail.value || !licenseValid) {
            return ""
        } else {
            val hailEncoded: Result<String> = HailService.encodeHailDetail(combatId, callsign) { hail ->
                hail.setHeartRate(physioData.heartRateBpm?.toShort() ?: 0)
                hail.setSkinTemp(physioData.skinTempF?.toInt()?.toShort() ?: 0)
                hail.setWeight(physioData.weightKg?.toShort() ?: -1)
                hail.setAge(physioData.ageYears?.toByte() ?: -1)
                if (manualAlertMap.isEmpty()) {
                    hail.setMedevent(false)
                } else {
                    hail.setMedevent(true)
                }
            }

            val cotDetail = hailEncoded.fold(
                onSuccess = { encoded ->
                    CotDetail(HailDetail.HAIL_DETAIL)
                        .setAttribute("version", HailDetail.HAIL_DETAIL_ENCODE_VERSION.toString())
                        .setInnerText(encoded)
                },
                onFailure = { e ->
                    Log.e("HAIL", "Error creating Hail CotDetail", e)
                    null
                }
            )
            return cotDetail?.toXmlString() ?: ""
        }
    }




    private fun escapeXmlText(s: String): String {
        val out = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&apos;")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    private fun finalMarkerTitle(markerTitle: String?): String {
        val calendar: Calendar = Calendar.getInstance()
        calendar.setTime(Date())
        val markerCallsignTimeFormatter = SimpleDateFormat("HHmmss'Z'")
        markerCallsignTimeFormatter.timeZone = TimeZone.getTimeZone("UTC")
        val markerCallsignAppendTime = markerCallsignTimeFormatter.format(calendar.time)
        return if (markerTitle.isNullOrEmpty()) {
            "${callsign}_$markerCallsignAppendTime"
        } else {
            escapeXmlText(markerTitle)
        }
    }

    private suspend fun companionAppAvailable(): Boolean {
        val enabled = dataRepository.getEnableCompanionAppService.first()
        return enabled && dataRepository.isCompanionConnected.value
    }

    private suspend fun endpointAvailability(): CotEndpointAvailability =
        CotEndpointAvailability(
            tak = isLicenseValid() && dataRepository.getEnableTakEndpoint.first() &&
                TAKConnectService.isAnyTakConnected,
            sitx = sitxAvailable(),
            ditto = DittoService.isRunning.get(),
            multicast = isLicenseValid() && dataRepository.multicastPublisherRunning.value,
            companion = companionAppAvailable(),
            plugin = pluginAvailable(),
        )

    // A paired phone stamps watch actions with its own location, so the watch fix is not needed.
    private suspend fun phoneSuppliesLocation(): Boolean =
        companionAppAvailable() && capRepo.gateway.session == null

    private suspend fun sitxAvailable(): Boolean {
        val enabled = dataRepository.getEnableSitxService.first()
        val connected = dataRepository.sitxStatus.value == SitxServerStatus.CONNECTED
        return enabled && connected
    }

    private suspend fun dispatch(message: CotOutboundMessage): Boolean {
        if (com.aitech.weartak_civ.command.SensitiveEnrollment.containsSensitiveXml(message.xml)) {
            Log.w(TAG, "Sensitive enrollment command excluded from outbound CoT")
            return false
        }
        // Routine self PLI waits out the grace window; only active traffic forces direct transports back.
        resumeWatchTransportsForDispatch(message.kind, emergencyNotice = message.emergencyDelivery != null)
        val started = android.os.SystemClock.elapsedRealtime()
        var accepted: Boolean? = null
        if (message.kind == CotMessageKind.PLI) AppDiagnostics.record(DiagnosticEvent.PLI_ATTEMPT)
        try {
            val position = (message.companionPayload as? CotCompanionPayload.Pli)?.snapshot?.loc
            if (message.kind == CotMessageKind.PLI && position?.provider != null &&
                !dataRepository.isCurrentLocationSnapshot(position)) {
                Log.i(TAG, "Skipping PLI built before a location input change")
                accepted = false
                return false
            }
            if (shouldDeferPliForTransportGrace(message.kind)) {
                Log.i(TAG, "Deferring self PLI while direct transports are in Companion grace")
                accepted = false
                return false
            }
            if (capRepo.gateway.session != null && companionAppAvailable() &&
                message.kind in listOf(
                    CotMessageKind.PLI, CotMessageKind.MARKER, CotMessageKind.MARKER_CLEAR,
                    CotMessageKind.CHAT, CotMessageKind.EMERGENCY
                )) {
                val gatewayAccepted = endpointDispatcher.dispatchGateway(message) { capRepo.offerGatewayCot(message.xml) }
                if (!gatewayAccepted) Log.w(TAG, "Gateway output queue full; CoT not sent")
                accepted = gatewayAccepted
                return gatewayAccepted
            }
            if (capRepo.gateway.session != null) {
                // Non-map traffic keeps its existing independent endpoints, never the gateway.
                return endpointDispatcher.dispatch(message, endpointAvailability().copy(companion = false)).also { accepted = it }
            }
            return endpointDispatcher.dispatch(message, endpointAvailability()).also { accepted = it }
        } finally {
            if (message.kind == CotMessageKind.PLI) AppDiagnostics.record(DiagnosticEvent.PLI_RESULT, mapOf(
                DiagnosticMetric.RESULT to when (accepted) { true -> 1L; false -> 0L; null -> -1L },
                DiagnosticMetric.DURATION_MS to android.os.SystemClock.elapsedRealtime() - started
            ))
        }
    }

    private suspend fun prepareForCompanionHandoff() {
        val now = System.currentTimeMillis()
        val system = dataRepository.snapshotSystem()
        val snapshot = dataRepository.snapshotPli()
        val message = CotOutboundMessage(
            CotMessageBuilder.expiredSelfPli(system.deviceId, system.callsign, now, snapshot.loc),
            CotMessageKind.PLI,
            CotCompanionPayload.Pli(snapshot),
        )
        val available = endpointAvailability()
        val accepted = endpointDispatcher.dispatchFinalSelfPliExpiry(
            message,
            available,
            sendTakExpiry = { TAKConnectService.sendFinalExpiredSelfPli(it) },
            sendMulticastExpiry = { NetworkUtils.MulticastPublisher.sendFinalExpiredSelfPli(it) },
        )
        if (accepted) {
            Log.i(TAG, "Published final expired self PLI before Companion handoff")
            kotlinx.coroutines.delay(COMPANION_HANDOFF_PLI_FLUSH_WAIT_MS)
        } else {
            Log.d(TAG, "No ready direct route for final self PLI expiry")
        }
    }

    private fun resumeWatchTransportsForPendingOutbound() {
        if (!dataRepository.isCompanionConnected.value &&
            dataRepository.resumeWatchTransportsImmediately()) {
            Log.i(TAG, "Resuming direct transports immediately for pending outbound CoT")
        }
    }

    internal fun resumeWatchTransportsForDispatch(kind: CotMessageKind, emergencyNotice: Boolean = false) {
        if (kind != CotMessageKind.PLI) resumeWatchTransportsForPendingOutbound()
        if (kind == CotMessageKind.EMERGENCY || emergencyNotice) DittoService.resumeSyncForAlert()
    }

    internal fun shouldDeferPliForTransportGrace(kind: CotMessageKind): Boolean =
        kind == CotMessageKind.PLI &&
            !dataRepository.isCompanionConnected.value &&
            dataRepository.shouldSuppressWatchTransportNow()

    private suspend fun handleClearMarkerUid(uid: String) = markerDispatchMutex.withLock {
        handleClearMarkerUidLocked(uid)
    }

    private suspend fun handleClearMarkerUidLocked(uid: String) {
        val marker = dataRepository.droppedMarkerList.value.firstOrNull { it.uid == uid } ?: return
        val owned = dataRepository.dittoPointLedger.owns(uid)
        val gatewayDeletion = owned && capRepo.gateway.session != null && companionAppAvailable()
        val removed = dataRepository.deleteDroppedPointLocally(uid, bhRepo, emitMirrorRequest = !gatewayDeletion)
        if (!removed) return
        requestBloodhoundComplicationUpdate(this)
        selfMarkerRefreshes.remove(uid)
        markerDropMap.remove(uid)
        pendingMarkerCotList.removeAll { it.uid == uid }
        if (!owned) {
            Log.w(TAG, "Pin removed locally without external deletion: no persisted local-origin ownership")
            return
        }
        val xml = CotMessageBuilder.ownedPointDeletion(uid, marker.location)
        val accepted = dispatch(CotOutboundMessage(xml, CotMessageKind.MARKER_CLEAR,
            CotCompanionPayload.OwnedPointDeletion(uid)))
        if (accepted) Log.i(TAG, "Owned pin removed locally; external deletion offered best effort")
        else Log.w(TAG, "Owned pin removed locally; no compatible route accepted external deletion (no new offline replay queue)")
    }

    private suspend fun handleClearMarkerAll() {
        val uids = dataRepository.droppedMarkerList.value.map { it.uid }
        uids.forEach { handleClearMarkerUid(it) }
    }


    private fun sendMulticastDatagram(cotXml: String) {
        // Non-PLI multicast remains license-gated for compatibility.
        if (isLicenseValid()) {
            Log.i(TAG, "Trying to multicast data to the wifi network! ")
            NetworkUtils.MulticastPublisher.send(cotXml)
        }
    }

    private suspend fun sendEmergencyAlert(alertWithSmsSentState: CustomEmergencyAlertWithAddedState): Boolean {
        val alert = alertWithSmsSentState.alert
        Log.i(
            TAG,
            "sendEmergencyAlert start uid=${alert.uuid} state=${alert.alertState} category=${alert.category} " +
                "priority=${alert.priority} tak=${TAKConnectService.isAnyTakConnected} " +
                "ditto=${DittoService.isRunning.get()} multicast=${dataRepository.multicastPublisherRunning.value} " +
                "sitx=${sitxAvailable()} companion=${companionAppAvailable()}",
        )
        if (MainActivity.enableSmsForAlerts && alert.sendSms && !alertWithSmsSentState.smsSent) {
            SMSManager.sendSMSMessage(numbers = smsNumbersList, description = "${alert.category}: ${alert.description}")
            alertWithSmsSentState.smsSent = true
        }

        val snapshot = dataRepository.snapshotEmergencyEvent(alert)
        val snapshotForDispatch = snapshot.copy(
            location = com.aitech.weartak_civ.cot.EmergencyLocationPolicy.forDispatch(snapshot.location, System.currentTimeMillis()),
        )
        if (snapshotForDispatch.location == null) {
            val text = if (alert.alertState == DataRepository.AlertState.CANCEL) {
                "EMERGENCY from ${snapshot.system.callsign}: cancelled"
            } else {
                "EMERGENCY from ${snapshot.system.callsign}: location unavailable, acquiring GPS"
            }
            val chat = DataRepository.ChatEventSnapshot(
                uid = "${alert.uuid}-location-unavailable-${alert.alertState}",
                roomUid = "All Chat Rooms", roomTitle = "All Chat Rooms", message = text,
                time = snapshot.time, location = null, physio = snapshot.physio, system = snapshot.system,
            )
            val accepted = dispatch(CotOutboundMessage(
                CotMessageBuilder.chat(chat, snapshot.system.callsign, snapshot.system.deviceId, messageId = chat.uid),
                CotMessageKind.CHAT, CotCompanionPayload.Chat(chat), emergencyDelivery = snapshotForDispatch,
            ))
            Log.w(TAG, "Emergency uid=${alert.uuid} has no valid fix; chat accepted=$accepted, positional alert awaits GPS")
            return accepted && alert.alertState == DataRepository.AlertState.CANCEL
        }
        val xml = CotMessageBuilder.emergency(snapshotForDispatch)

        val sent = dispatch(
            CotOutboundMessage(xml, CotMessageKind.EMERGENCY, CotCompanionPayload.Emergency(snapshotForDispatch)),
        )

        if (sent) {
            Log.i(
                TAG,
                "sendEmergencyAlert complete uid=${alert.uuid} sent=true tak=${TAKConnectService.isAnyTakConnected} " +
                    "ditto=${DittoService.isRunning.get()} multicast=${dataRepository.multicastPublisherRunning.value} " +
                    "sitx=${sitxAvailable()} companion=${companionAppAvailable()}",
            )
        } else {
            Log.i(
                TAG,
                "sendEmergencyAlert complete uid=${alert.uuid} sent=false reason=no_route_available " +
                    "tak=${TAKConnectService.isAnyTakConnected} ditto=${DittoService.isRunning.get()} " +
                    "multicast=${dataRepository.multicastPublisherRunning.value} sitx=${sitxAvailable()} " +
                    "companion=${companionAppAvailable()}",
            )
        }

        return sent
    }

    fun buildAndSendPhysioLocationCotMsg(periodic: Boolean = false) {
        // Preserve synchronous dispatch for scheduler and location-triggered calls.
        runBlocking(Dispatchers.IO) {
            periodicPliMutex.withLock {
                val gated = periodic && dataRepository.getEnableDynamicReportingStrategy.first()
                if (gated && !dataRepository.getEnableLocationService.first()) return@withLock
                if (gated &&
                    androidx.core.content.ContextCompat.checkSelfPermission(applicationContext,
                        android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    androidx.core.content.ContextCompat.checkSelfPermission(applicationContext,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) return@withLock
                val now = android.os.SystemClock.elapsedRealtimeNanos()
                if (gated && !periodicReportingGate.isDue(now, actualReportingIntervalSecs)) return@withLock
                if (outboundAnnouncementGate.hasPending()) {
                    val fresh = dataRepository.latestLocation.value
                    if (fresh != null && announceOutboundPli(fresh)) {
                        if (gated) periodicReportingGate.reported(android.os.SystemClock.elapsedRealtimeNanos())
                        return@withLock
                    }
                }
                val snapshot = dataRepository.snapshotPli()
                val loc = snapshot.loc
                if (loc == null || !com.aitech.weartak_civ.cot.EmergencyLocationPolicy.usable(loc)) {
                    Log.i(TAG, "Skipping outbound PLI: awaiting first valid location fix")
                    return@withLock
                }

                val xml = buildPliCot(snapshot)

                Log.i(
                    TAG,
                    "Outbound PLI built: uid=${snapshot.systemData.deviceId} " +
                        "lat=${loc.lat} lon=${loc.lon} " +
                        "hae=${loc.hae} takConnected=${TAKConnectService.isAnyTakConnected} " +
                        "sitxAvailable=${sitxAvailable()} companionAvailable=${companionAppAvailable()}"
                )

                if (dispatch(CotOutboundMessage(xml, CotMessageKind.PLI, CotCompanionPayload.Pli(snapshot))) && gated) {
                    periodicReportingGate.reported(android.os.SystemClock.elapsedRealtimeNanos())
                }
            }
        }
    }


    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onIdentityPliRefresh(event: InteractionPliRefresh) {
        if (event.reason !in setOf(
                PliRefreshReason.CALLSIGN_CHANGED, PliRefreshReason.TEAM_CHANGED, PliRefreshReason.ROLE_CHANGED
            )) return
        identityPliJob?.cancel()
        identityPliJob = serviceScope.launch {
            // Merge settings saved together while the independent new GPS acquisition continues.
            kotlinx.coroutines.delay(150L)
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED &&
                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Identity PLI refresh denied: ${event.reason}; no location permission")
                return@launch
            }
            val location = dataRepository.bestKnownLocation()
            if (location == null) {
                Log.i(TAG, "Identity PLI awaits first GPS fix: ${event.reason}")
                return@launch
            }
            val base = dataRepository.snapshotInteractionPli(location)
            val now = System.currentTimeMillis()
            val snapshot = base.copy(
                loc = com.aitech.weartak_civ.cot.EmergencyLocationPolicy.forDispatch(base.loc, now),
                time = if (now - location.time > FRESH_GPS_INTERVAL_MILLIS) {
                    com.aitech.weartak_civ.cot.CotEventTimeFactory.now(
                        staleOffset = com.aitech.weartak_civ.cot.CotTimeOffset(seconds = 2 * actualReportingIntervalSecs + 15),
                    )
                } else base.time,
            )
            val xml = buildPliCot(snapshot)
            Log.i(TAG, "Dispatching persisted identity PLI with best available fix while GPS refreshes: ${event.reason}")
            dispatch(CotOutboundMessage(xml, CotMessageKind.PLI, CotCompanionPayload.Pli(snapshot)))
        }
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun onInteractionLocationRcvd(event: InteractionLocationRcvd) {
        serviceScope.launch {
            if (event.reasons == setOf(PliRefreshReason.OUTBOUND_ROUTE_READY)) {
                announceOutboundPli(event.location)
                return@launch
            }
            val location = event.location
            if (!dataRepository.isCurrentLocationInput(location)) {
                Log.i(TAG, "Ignoring interaction fix from previous location input")
                return@launch
            }
            val ageNanos = android.os.SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
            if (ageNanos < 0 || ageNanos > InteractionPliRefreshGate.TIMEOUT_MILLIS * 1_000_000L ||
                !InteractionPliRefreshGate.usable(location.latitude, location.longitude, location.accuracy, location.hasAccuracy())) {
                Log.i(TAG, "Interaction PLI fix expired before dispatch: ${event.reasons}")
                return@launch
            }
            // This callback supplies a verified fresh fix; leave periodic reporting timing alone.
            if (readyToSendAlertEvents()) {
                sendAllManualAlerts(null)
                sendAllPhysioAndAutoGenAlerts(null)
            }
            val nowNanos = android.os.SystemClock.elapsedRealtimeNanos()
            selfMarkerRefreshes.entries.toList().forEach { (uid, requested) ->
                if (nowNanos - requested > InteractionPliRefreshGate.TIMEOUT_MILLIS * 1_000_000L) {
                    selfMarkerRefreshes.remove(uid, requested)
                } else if (location.elapsedRealtimeNanos >= requested && selfMarkerRefreshes.remove(uid, requested)) {
                    val marker = dataRepository.droppedMarkerList.value.firstOrNull { it.uid == uid }
                    if (marker != null && !dataRepository.isDeletedLocalPoint(uid)) {
                        val updated = marker.copy(
                            cotType = DataRepository.MarkerCotType.EDIT,
                            location = dataRepository.snapshotLocation(location), waitingForUpdatedLocation = false,
                            enqueuedToBeSent = true,
                        )
                        dataRepository.changeDroppedMarkerLocationByUid(uid, updated.location)
                        markerDropMap[uid] = updated
                        enqueueMarkerEdit(updated)
                    }
                }
            }
            sendPendingMarkerCots()
            if (PliRefreshReason.OUTBOUND_ROUTE_READY in event.reasons && announceOutboundPli(location))
                return@launch
            // Use this request's fix, not a concurrently replaced repository location.
            val snapshot = dataRepository.snapshotInteractionPli(location)
            val xml = buildPliCot(snapshot)
            if (android.os.SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos >
                InteractionPliRefreshGate.TIMEOUT_MILLIS * 1_000_000L) {
                Log.i(TAG, "Interaction PLI fix expired while building snapshot: ${event.reasons}")
                return@launch
            }
            Log.i(TAG, "Dispatching interaction PLI without waiting for periodic cadence: ${event.reasons}")
            dispatch(CotOutboundMessage(xml, CotMessageKind.PLI, CotCompanionPayload.Pli(snapshot)))
        }
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public fun onLocationRcvdEvent(event: LocationRcvd) {
        Log.i(TAG, "onLocationRcvdEvent: received location trigger for outbound PLI")
        lastLocationUpdateRcvd = System.currentTimeMillis()
        serviceScope.launch {
            if (readyToSendAlertEvents()) {
                sendAllManualAlerts(null)
                sendAllPhysioAndAutoGenAlerts(null)
            }
        }

        if (pendingMarkerCotList.isNotEmpty()) {
            serviceScope.launch {
                sendPendingMarkerCots()
            }
        }

        buildAndSendPhysioLocationCotMsg(periodic = true)
    }

    private suspend fun enqueueMarkerDrop(marker: DataRepository.DroppedMarker) {
        resumeWatchTransportsForPendingOutbound()
        markerDispatchMutex.withLock {
            Log.i(TAG, "===> Adding to marker cot list: Marker Drop for uid=${marker.uid}")
            pendingMarkerCotList.add(marker.copy(cotType = DataRepository.MarkerCotType.DROP))
        }
    }

    private suspend fun enqueueMarkerEdit(req: DataRepository.DroppedMarker) {
        resumeWatchTransportsForPendingOutbound()
        markerDispatchMutex.withLock {
            Log.i(TAG, "===> Adding to marker cot list: Marker Edit for uid=${req.uid}")
            pendingMarkerCotList.removeAll { it.uid == req.uid }
            pendingMarkerCotList.add(req)
        }
    }

    private suspend fun sendPendingMarkerCots(): Boolean = markerDispatchMutex.withLock {
        var sentAllReadyToSend = true
        val queueSize = pendingMarkerCotList.size
        Log.i(TAG, "pendingMarkerDropQueue.size=$queueSize")
        val newPendingMarkerDropList = mutableListOf<DataRepository.DroppedMarker>()
        val isLocationFresh = isLocationFresh()
        val phoneSuppliesLocation = phoneSuppliesLocation()
        for ((i, item) in pendingMarkerCotList.withIndex()) {
            if (item.cotType == DataRepository.MarkerCotType.CLEAR) {
                handleClearMarkerUidLocked(item.uid)
                if (dataRepository.droppedMarkerList.value.any { it.uid == item.uid }) {
                    newPendingMarkerDropList.add(item)
                    sentAllReadyToSend = false
                }
                continue
            }
            if (dataRepository.isDeletedLocalPoint(item.uid)) {
                markerDropMap.remove(item.uid)
                continue
            }
            val cotType = item.cotType
            var marker = item
            Log.i(TAG, "sendPendingMarkerCots: Looking at element $i ($cotType): time ${marker.timeDropped}, uid=${marker.uid} type=${marker.markerType}, title=${marker.title}, remark=${marker.remark}, loc=(${marker.location?.lat ?: "null"}, ${marker.location?.lon ?: "null"})")

            if (marker.waitingForUpdatedLocation) {
                if (isLocationFresh) {
                    Log.i(TAG, "sendPendingMarkerCots: waitingForUpdatedLocation is true but location is fresh. Updating...")
                    marker = marker.copy(location = dataRepository.snapshotLocation(), waitingForUpdatedLocation = false)
                    dataRepository.changeDroppedMarkerLocationByUid(marker.uid, marker.location)
                } else if (phoneSuppliesLocation) {
                    Log.i(TAG, "sendPendingMarkerCots: location is stale but the paired phone supplies location. Sending...")
                    marker = marker.copy(waitingForUpdatedLocation = false)
                } else {
                    Log.i(TAG, "sendPendingMarkerCots: waitingForUpdatedLocation is true and location is stale. Skipping...")
                    newPendingMarkerDropList.add(item)
                }
            }
            // The location refresh above may have made this marker sendable.
            if (!marker.waitingForUpdatedLocation) {
                if (sendMarkerDropNow(marker)) {
                    Log.i(TAG, "sendPendingMarkerCots: sendMarkerDropNow returned true. Removing....")
                    if (!dataRepository.isDeletedLocalPoint(marker.uid)) {
                        markerDropMap[marker.uid] = marker.copy(enqueuedToBeSent = false)
                    }
                    dataRepository.changeDroppedMarkerCotTypeStatusByUid(marker.uid,
                        cotType = marker.cotType,
                        enqueuedToBeSent = false,
                        waitingForUpdatedLocation = false
                    )
                } else {
                    Log.i(TAG, "sendPendingMarkerCots: sendMarkerDropNow returned false. Skipping....")
                    if (!dataRepository.isDeletedLocalPoint(marker.uid)) {
                        markerDropMap[marker.uid] = marker.copy(enqueuedToBeSent = true)
                    }
                    dataRepository.changeDroppedMarkerCotTypeStatusByUid(marker.uid,
                        cotType = marker.cotType,
                        enqueuedToBeSent = true,
                        waitingForUpdatedLocation = false
                    )
                    sentAllReadyToSend = false
                    newPendingMarkerDropList.add(item)
                }
            }
        }
        pendingMarkerCotList.clear()
        pendingMarkerCotList.addAll(newPendingMarkerDropList.filterNot {
            dataRepository.isDeletedLocalPoint(it.uid)
        })
        return sentAllReadyToSend
    }

    private suspend fun dispatchMarkerSnapshot(
        snapshot: DataRepository.MarkerSnapshot?,
        xml: String,
        kind: CotMessageKind = CotMessageKind.MARKER,
    ): Boolean {
        if (snapshot == null) return false
        if (dataRepository.isDeletedLocalPoint(snapshot.uid)) return false
        val sent = dispatch(
            CotOutboundMessage(xml, kind, CotCompanionPayload.Marker(snapshot)),
        )
        Log.i(TAG, "dispatchMarkerSnapshot: sent=$sent")
        return sent
    }

    private suspend fun sendMarkerDropNow(marker: DataRepository.DroppedMarker): Boolean {
        val editedMarker = if (marker.location == null) {
            Log.w(TAG, "sendMarkerDropNow called with null location; Setting to current location or lat=0, lon=0")
            val locationSnapshot = if (null != dataRepository.latestLocation.value) {
                dataRepository.snapshotLocation()
            } else {
                DataRepository.LocationSnapshot(timeMillis = System.currentTimeMillis(),
                    lat = 0.0, lon = 0.0, hae = 0.0, ce = 0.0f, le = 0.0f, course = 0.0f, speed = 0.0f)
            }
            marker.copy(location = locationSnapshot)
        } else {
            marker
        }
        Log.i(TAG, "sendMarkerDropNow::markerType:: = [ ${marker.markerType.type} ]")
        val snapshot = dataRepository.snapshotMarker(marker)
        val xml = CotMessageBuilder.marker(snapshot, finalMarkerTitle(snapshot.title), iconSetPath)
        return dispatchMarkerSnapshot(snapshot, xml)
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public fun onMessageEvent(event: MessageEvent) {
        val msg = event.message
        if (CALLSIGN_TEAM_ROLE_CHANGED == msg) {
            // Actual persisted changes issue InteractionPliRefresh. This legacy UI event can
            // also be posted for unchanged values; do not send a stale duplicate PLI here.
            Log.d(TAG, "Identity UI notification received; refresh handled by persisted identity changes")
        } else if (CUSTOM_DEVELOPER_CONFIG_JSON_CHANGED == msg) {
            Log.d(TAG, "onMessageEvent w/ msg($msg)")
            CoroutineScope(Dispatchers.Main).launch {
                setValuesFromCustomDeveloperConfig()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "CotDispatchService onDestroy()")
        isRunning.set(false)
        stopForegroundServiceCompat()
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
        stopDispatchScheduler()
        HailService.hailScope.cancel()
        serviceScope.cancel()
        isRunning.set(false)
    }

    private suspend fun setValuesFromCustomDeveloperConfig() {
        val jsonString = MainActivity.Constants.dataRepository.getCustomDeveloperConfigJson.first()
        var value: String? = getValueFromJson(jsonString, OVERRIDE_GPS_WITH_BLE_EXIT_HYSTERESIS_COUNT_JSON_KEY)
        if (null != value) {
            val intValue = value.toIntOrNull()
            Log.d(TAG, "$OVERRIDE_GPS_WITH_BLE_EXIT_HYSTERESIS_COUNT_JSON_KEY: $intValue")
            if (null != intValue) {
                OVERRIDE_GPS_WITH_BLE_EXIT_HYSTERESIS_COUNT = intValue
            }
        }
        value = getValueFromJson(jsonString, GPS_STALE_TIME_INTERVAL_MS_JSON_KEY)
        if (null != value) {
            val longValue = value.toLongOrNull()
            Log.d(TAG, "$GPS_STALE_TIME_INTERVAL_MS_JSON_KEY: $longValue")
            if (null != longValue) {
                GPS_STALE_TIME_INTERVAL_MS = longValue
            }
        }
    }

    companion object {
        private val TAG = "WearTAK-" + CotDispatchService::class.java.simpleName
        private const val DISPATCH_INTERVAL = 2_000L // 2 seconds
        private const val COMPANION_HANDOFF_PLI_FLUSH_WAIT_MS = 500L
        // Marker queue freshness; emergencies retain the best known fix with aged uncertainty.
        private const val FRESH_GPS_INTERVAL_MILLIS = 3_000L
        private const val COT_DISPATCH_SERVICE_NOTIFICATION_ID = 100
        var GPS_STALE_TIME_INTERVAL_MS = 10_000L
        var OVERRIDE_GPS_WITH_BLE_EXIT_HYSTERESIS_COUNT = 30
        private const val GPS_STALE_TIME_INTERVAL_MS_JSON_KEY = "gpsStaleTimeInterval"
        private const val OVERRIDE_GPS_WITH_BLE_EXIT_HYSTERESIS_COUNT_JSON_KEY = "overrideGpsWithBleExitHysteresisCount"
        val isRunning : AtomicBoolean = AtomicBoolean(false)

        private val _actualReportingIntervalSecsFlow =
            MutableStateFlow(DEFAULT_CONSTANT_REPORTING_INTERVAL)
        val actualReportingIntervalSecsFlow: StateFlow<Int> =
            _actualReportingIntervalSecsFlow.asStateFlow()

        @Volatile
        internal var stationaryIntervalSeconds = 0
            set(value) {
                if (field == value) return
                field = value
                _actualReportingIntervalSecsFlow.value = actualReportingIntervalSecs
                EventBus.getDefault().post(com.aitech.weartak_civ.events.PowerManagerEvent(
                    com.aitech.weartak_civ.events.PowerManagerEvent.CHANGED_UPDATE_INTERVAL))
            }

        internal var stationaryLocation: Boolean
            get() = stationaryIntervalSeconds > 0
            set(value) { stationaryIntervalSeconds = if (value) StationaryLocationPolicy.INTERVAL_SECONDS else 0 }

        var actualReportingIntervalSecs: Int = DEFAULT_CONSTANT_REPORTING_INTERVAL
            get() = StationaryLocationPolicy.intervalSeconds(field, stationaryLocation, stationaryIntervalSeconds)
            set(value) {
                if (field == value) return
                field = value
                _actualReportingIntervalSecsFlow.value = actualReportingIntervalSecs
            }

        private val _desiredReportingIntervalSecsFlow =
            MutableStateFlow(DEFAULT_CONSTANT_REPORTING_INTERVAL)
        val desiredReportingIntervalSecsFlow: StateFlow<Int> =
            _desiredReportingIntervalSecsFlow.asStateFlow()

        var desiredReportingIntervalSecs: Int = DEFAULT_CONSTANT_REPORTING_INTERVAL
            set(value) {
                if (field == value) return
                field = value
                _desiredReportingIntervalSecsFlow.value = value
            }

        @Volatile
        var desiredLocationObservationIntervalSecs: Int = DEFAULT_CONSTANT_REPORTING_INTERVAL

        lateinit var instance: CotDispatchService

        internal suspend fun prepareForCompanionHandoffIfRunning() {
            if (isRunning.get() && ::instance.isInitialized) instance.prepareForCompanionHandoff()
        }
        val dateTimeFormatter: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        var esriGeojsonMsg = ""
        var tabletCommandJsonMsg = ""
    }
}
