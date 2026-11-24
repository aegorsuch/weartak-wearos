package com.aitech.weartak_civ.integration.plugin

import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cep.engine.config.ParserException
import com.aitech.weartak_civ.cep.plugin.PluginTrust
import com.weartak.plugin.sdk.WearTakPluginContract
import com.weartak.plugin.sdk.host.v1.HostServicesContract
import com.weartak.plugin.sdk.point.v1.PointContractV1
import com.weartak.plugin.sdk.aidl.IWearTakPlugin
import com.weartak.plugin.sdk.model.PluginInitParcel
import com.weartak.plugin.sdk.model.PluginInfoParcel
import com.weartak.plugin.sdk.mopp.MoppPpeContract
import com.weartak.plugin.sdk.routestatus.RouteStatusContract
import com.weartak.plugin.sdk.transport.TransportCloseReason
import com.weartak.plugin.sdk.transport.TransportContract
import com.weartak.plugin.sdk.v2.WearTakPluginV2Contract
import com.weartak.plugin.sdk.v3.WearTakPluginV3Contract
import com.weartak.plugin.sdk.openvpn.v1.OpenVpnHostContract
import com.weartak.plugin.sdk.ppg.v1.PpgContractV1
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

/**
 * Owns one bound plugin service: binder, death recipient, host stubs, and teardown.
 */
class PluginConnection(
    private val app: WearTakApplication,
    private val ingress: PluginIngressPort,
    private val component: ComponentName,
    private val pluginUid: Int,
    private val manager: PluginManager,
    private val pluginTrust: PluginTrust,
    private val pluginOnlyEnabled: () -> Boolean = { false },
) : ServiceConnection {

    private val pluginPackage: String = component.packageName
    private val dittoTrafficGeneration = if (pluginPackage == TrustedCommunityPlugin.PACKAGE) {
        manager.dittoTrafficFence.currentGeneration()
    } else null
    private val dittoTrafficAdmission: (() -> AutoCloseable?)? =
        if (pluginPackage == TrustedCommunityPlugin.PACKAGE) {
            {
                if (!closed && manager.dittoTrafficAllowed()) {
                    dittoTrafficGeneration?.let(manager.dittoTrafficFence::enter)
                } else null
            }
        } else null

    /** Package key for diagnostics when the connection is not yet in [PluginManager]'s map. */
    internal val connectionPackageName: String get() = pluginPackage
    internal val connectionComponent: ComponentName get() = component
    internal fun hasCurrentDittoTrafficGeneration(): Boolean =
        pluginPackage != TrustedCommunityPlugin.PACKAGE ||
            dittoTrafficGeneration != null &&
            manager.dittoTrafficFence.currentGeneration() == dittoTrafficGeneration

    @Volatile private var pluginBinder: IBinder? = null
    private var plugin: IWearTakPlugin? = null
    @Volatile
    private var pluginInfoSnapshot: PluginInfoParcel? = null
    @Volatile
    private var apiVersionSnapshot: Int = -1
    private var hostControl: HostControlImpl? = null
    private var hostCallback: HostCallbackImpl? = null
    private var hostPcmControlV3: HostPcmControlV3Impl? = null
    @Volatile private var hostTransportV1: PluginTransportHostV1Impl? = null
    @Volatile private var hostPointEndpoint: HostPointEndpointV1? = null
    @Volatile private var hostMoppControl: HostMoppPpeControl? = null
    @Volatile private var hostRouteStatusControl: HostRouteStatusControl? = null
    @Volatile private var hostPpgV1: PpgHostV1Impl? = null
    @Volatile private var pluginOnlyPliProducer: PluginOnlyPliProducer? = null
    private var hostServicesV1: HostServicesV1Impl? = null
    @Volatile private var hostOpenVpnServicesV1: HostOpenVpnServicesV1Impl? = null
    private val stateLock = Any()
    @Volatile private var closed = false
    @Volatile private var trustVerified = false
    @Volatile private var ready = false
    internal val hasVerifiedTrust: Boolean get() = trustVerified && !closed
    private val dittoPdlModeState = DittoPdlModeState()
    internal val dittoPdlMode = dittoPdlModeState.mode
    internal val dittoMeshStatus = dittoPdlModeState.mesh

    internal fun dittoCotAvailable(): Boolean = synchronized(stateLock) {
        ready && hasVerifiedTrust && manager.dittoTrafficAllowed() && dittoRuntimeAllowed &&
            pluginPackage == TrustedCommunityPlugin.PACKAGE &&
            pluginBinder?.isBinderAlive == true && manager.connectionFor(pluginPackage) === this &&
            hostTransportV1?.isActiveGeneralDittoTransport() == true &&
            manager.settingsCoordinator.pluginHasSettingEnabled(pluginPackage, PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT)
    }

    internal suspend fun submitDittoCoT(
        xml: String, senderUid: String, senderCallsign: String?, messageId: String,
    ): PluginTransportHostV1Impl.Submission {
        val token = manager.settingsCoordinator.dittoForwardingToken()
            ?: return PluginTransportHostV1Impl.Submission.NOT_ADMITTED
        val transport = synchronized(stateLock) {
            hostTransportV1.takeIf { dittoCotAvailable() }
        } ?: return PluginTransportHostV1Impl.Submission.NOT_ADMITTED
        return transport.offerTrackedDittoCoT(xml, senderUid, senderCallsign, messageId,
            eligible = { manager.settingsCoordinator.isDittoForwardingTokenCurrent(token) })()
    }

    internal fun offerTrackedDittoCoT(
        xml: String, senderUid: String, senderCallsign: String?, messageId: String,
        eligible: () -> Boolean,
        expectedSessionId: String,
    ): suspend () -> PluginTransportHostV1Impl.Submission {
        val token = manager.settingsCoordinator.dittoForwardingToken()
            ?: return { PluginTransportHostV1Impl.Submission.NOT_ADMITTED }
        val transport = synchronized(stateLock) {
            hostTransportV1.takeIf { dittoCotAvailable() }
        } ?: return { PluginTransportHostV1Impl.Submission.NOT_ADMITTED }
        return transport.offerTrackedDittoCoT(xml, senderUid, senderCallsign, messageId,
            expectedSessionId, {
                manager.settingsCoordinator.isDittoForwardingTokenCurrent(token) && eligible()
            })
    }

    internal fun quiescentReplacementSession(): String? = synchronized(stateLock) {
        if (!dittoCotAvailable() ||
            pluginInfoSnapshot?.versionName != TrustedCommunityPlugin.GATE_VERSION_NAME ||
            !TrustedCommunityPlugin.supportsQuiescentReplacement(app, pluginUid)) return@synchronized null
        hostTransportV1?.activeGeneralDittoSession()
    }

    internal fun resumeDittoForAlert() {
        if (ready && hasVerifiedTrust && manager.dittoTrafficAllowed() &&
            pluginPackage == TrustedCommunityPlugin.PACKAGE &&
            pluginBinder?.isBinderAlive == true && manager.connectionFor(pluginPackage) === this &&
            manager.settingsCoordinator.pluginHasSettingEnabled(pluginPackage, PluginCotEgress.SETTING_FORWARD_OUTBOUND_COT)) {
            pluginOnlyPliProducer?.resumeForAlert()
        }
    }
    private var initializing = false
    private var dittoLifecycleInProgress = false
    private var dittoLifecycleRetryScheduled = false
    @Volatile private var dittoUsesPluginOnlyLifecycle = false
    @Volatile private var dittoRuntimeAllowed = false
    private var dittoStartRequested = false
    private var pendingRemoteCleanup: (() -> Unit)? = null
    private var remoteCleanupFinished = false
    private val afterRemoteCleanupActions = mutableListOf<() -> Unit>()
    private val localUnbindClaimed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** True exactly once, for the caller that releases this connection's Android binding. */
    internal fun claimLocalUnbind(): Boolean = localUnbindClaimed.compareAndSet(false, true)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val handshakeTimeout = Runnable {
        if (!closed) failBind("lifecycle_timeout")
    }
    private val audioHealthTimeout = Runnable {
        if (!closed) {
            Log.w(TAG, "Audio detector health heartbeat timed out pkg=$pluginPackage")
            manager.updateAudioDetectionHealth(this, false)
        }
    }
    @Volatile private var certificateImport: HostPkcs12ImportControl? = null
    @Volatile private var serverPackages: HostServerPackageControl? = null
    private val wearerLaunchUntil = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var sessionActive = false

    /**
     * Set when [onServiceConnected] fails before [PluginManager.bind] registers this connection.
     * Prevents a synchronous callback from evicting and then [bind] re-adding the same dead connection.
     */
    internal var suppressPostBindRegistration: Boolean = false
        private set

    private fun failBind(reason: String) {
        if (closed) return
        suppressPostBindRegistration = true
        manager.onConnectionFailed(this@PluginConnection, reason)
    }

    private val deathRecipient = IBinder.DeathRecipient {
        ready = false
        if (pluginPackage == CRM_PLUGIN_PACKAGE) manager.crmPluginStatus.clear(this)
        dittoPdlModeState.close()
        pluginOnlyPliProducer?.close()
        hostPointEndpoint?.revoke()
        hostTransportV1?.closeBinding(TransportCloseReason.DISCONNECTED)
        hostOpenVpnServicesV1?.close()
        hostRouteStatusControl?.close()
        hostPpgV1?.close()
        hostMoppControl?.close()
        sessionActive = false
        certificateImport?.close()
        serverPackages?.close()
        appScope.launch {
            onBinderDiedInternal()
        }
    }

    private fun onBinderDiedInternal() {
        if (pluginPackage == CRM_PLUGIN_PACKAGE) manager.crmPluginStatus.clear(this)
        hostPointEndpoint?.revoke()
        hostTransportV1?.closeBinding(TransportCloseReason.DISCONNECTED)
        hostOpenVpnServicesV1?.close()
        revokeCertificateSessions()
        hostRouteStatusControl?.close()
        hostPpgV1?.close()
        hostMoppControl?.close()
        PluginDiagnostics.recordBinderDeath(pluginPackage)
        manager.onConnectionDied(this)
    }

    /** Test seam: synchronous binder-death path (production uses [binderDeathRecipientForTest]). */
    internal fun triggerBinderDeathSyncForTest() {
        onBinderDiedInternal()
    }

    internal val binderDeathRecipientForTest: IBinder.DeathRecipient get() = deathRecipient

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    internal fun startBindingTimeout() {
        mainHandler.postDelayed(handshakeTimeout, HANDSHAKE_TIMEOUT_MS)
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        connect(service)
    }

    private fun connect(service: IBinder?) {
        if (closed) return
        if (service == null) {
            PluginDiagnostics.recordBindFailure(component.flattenToString(), null, includeBinderProtocolCategory = false)
            failBind("null_binder")
            return
        }
        synchronized(stateLock) {
            if (closed || initializing || pluginBinder != null) return
            initializing = true
        }
        mainHandler.removeCallbacks(handshakeTimeout)
        startBindingTimeout()
        if (!PluginLifecycleWork.execute(pluginPackage) {
            try {
                initialize(service)
            } catch (error: RemoteException) {
                Log.e(TAG, "plugin lifecycle failed pkg=$pluginPackage", error)
                postFailure("remote_exception")
            } catch (error: RuntimeException) {
                Log.e(TAG, "plugin initialization failed pkg=$pluginPackage", error)
                postFailure("initialization_failed")
            } finally {
                val cleanup = synchronized(stateLock) {
                    initializing = false
                    pendingRemoteCleanup.also { pendingRemoteCleanup = null }
                }
                cleanup?.invoke()
            }
        }) {
            val cleanup = synchronized(stateLock) {
                initializing = false
                pendingRemoteCleanup.also { pendingRemoteCleanup = null }
            }
            if (cleanup != null) scheduleRemoteCleanup(cleanup)
            failBind("lifecycle_busy")
        }
    }

    private fun postFailure(reason: String) {
        mainHandler.post { if (!closed) failBind(reason) }
    }

    private fun postAudioHealth(healthy: Boolean) {
        if (pluginPackage != PluginDetectionEventBridge.DART_PACKAGE &&
            pluginPackage != PluginDetectionEventBridge.FALCON_PACKAGE
        ) return
        val receivedAt = SystemClock.elapsedRealtime()
        mainHandler.post {
            if (closed) return@post
            mainHandler.removeCallbacks(audioHealthTimeout)
            val remaining = AUDIO_HEALTH_TIMEOUT_MS - (SystemClock.elapsedRealtime() - receivedAt)
            val fresh = healthy && remaining > 0
            manager.updateAudioDetectionHealth(this, fresh)
            if (fresh) mainHandler.postDelayed(audioHealthTimeout, remaining)
        }
    }

    private fun initialize(service: IBinder) {
        if (closed) return
        val iface = runCatching { IWearTakPlugin.Stub.asInterface(service) }.getOrElse { t ->
            PluginDiagnostics.recordBindFailure(component.flattenToString(), t, includeBinderProtocolCategory = false)
            postFailure("asInterface")
            return
        }
        synchronized(stateLock) {
            if (closed) return
            pluginBinder = service
            plugin = iface
        }
        val deathLinked = runCatching { service.linkToDeath(deathRecipient, 0) }
            .onFailure { Log.w(TAG, "linkToDeath failed pkg=$pluginPackage", it) }.isSuccess

        val info = runCatching { iface.pluginInfo }.getOrElse { t ->
            Log.e(TAG, "getPluginInfo failed", t)
            postFailure("get_plugin_info")
            return
        }
        if (closed) return
        synchronized(stateLock) {
            if (closed) return
            pluginInfoSnapshot = info
        }
        if (info.packageName != pluginPackage || info.pluginId != pluginPackage) {
            Log.w(
                TAG,
                "plugin info mismatch pkg=$pluginPackage info.pkg=${info.packageName} info.id=${info.pluginId}"
            )
            postFailure("plugin_info_mismatch")
            return
        }

        val senderPackages = ingress.packagesForUid(app, pluginUid)
        if (pluginPackage !in senderPackages) {
            postFailure("uid_package_mismatch")
            return
        }

        runCatching {
            runBlocking {
                pluginTrust.enforce(pluginPackage, pluginUid, senderPackages)
            }
        }.onFailure { e ->
            val code = (e as? ParserException)?.reasonCode ?: e.javaClass.simpleName
            postFailure("trust_failure:$code")
            return
        }
        trustVerified = true

        val pluginOnlyProfile = pluginPackage == TrustedCommunityPlugin.PACKAGE && pluginOnlyEnabled()
        dittoUsesPluginOnlyLifecycle = pluginOnlyProfile
        if (pluginOnlyProfile && (!deathLinked ||
                info.versionName != TrustedCommunityPlugin.GATE_VERSION_NAME ||
                !TrustedCommunityPlugin.supportsQuiescentReplacement(app, pluginUid))) {
            Log.e(TAG, "Ditto plugin lacks verified runtime exclusion pkg=$pluginPackage")
            postFailure("ditto_runtime_version_unverified")
            return
        }

        val api = runCatching { iface.apiVersion }.getOrDefault(-1)
        if (closed) return
        synchronized(stateLock) {
            if (closed) return
            apiVersionSnapshot = api
        }
        if (
            api != WearTakPluginContract.CONTRACT_API_VERSION &&
            api != WearTakPluginV2Contract.CONTRACT_API_VERSION &&
            api != WearTakPluginV3Contract.CONTRACT_API_VERSION
        ) {
            Log.w(TAG, "plugin apiVersion=$api unsupported pkg=$pluginPackage")
            postFailure("api_version_mismatch:$api")
            return
        }

        val dittoBindingAuthorized = {
            pluginOnlyProfile && pluginOnlyEnabled() &&
                manager.dittoTrafficAllowed() &&
                dittoTrafficGeneration != null &&
                manager.dittoTrafficFence.currentGeneration() == dittoTrafficGeneration &&
                !closed && trustVerified && pluginBinder === service && service.isBinderAlive &&
                manager.connectionFor(pluginPackage) === this &&
                TrustedCommunityPlugin.supportsQuiescentReplacement(app, pluginUid)
        }
        val control = HostControlImpl(
            app, ingress, pluginPackage, pluginUid, isActive = { !closed },
            trafficAdmission = dittoTrafficAdmission,
        )
        val controlV2 = if (api >= WearTakPluginV2Contract.CONTRACT_API_VERSION) {
            HostControlV2Impl(
                app,
                ingress,
                pluginPackage,
                pluginUid,
                isActive = { !closed },
                detectionEventBridge = PluginDetectionEventBridge.forPlugin(app, pluginPackage),
            )
        } else {
            null
        }
        val controlV3 = if (api == WearTakPluginV3Contract.CONTRACT_API_VERSION) {
            sessionActive = true
            val authenticate = {
                    if (closed || !sessionActive || pluginBinder?.isBinderAlive != true) false
                    else try {
                        runBlocking {
                            pluginTrust.enforce(pluginPackage, pluginUid, ingress.packagesForUid(app, pluginUid))
                        }
                        true
                    } catch (_: com.aitech.weartak_civ.cep.engine.config.ParserException) {
                        Log.w(TAG, "Certificate import session authentication rejected")
                        false
                    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                        Log.w(TAG, "Certificate import plugin package unavailable")
                        false
                    } catch (_: Exception) {
                        Log.e(TAG, "Certificate import session verification unavailable")
                        false
                    }
            }
            val launch: (String) -> Unit = { handle ->
                    app.startActivity(android.content.Intent(app,
                        com.aitech.weartak_civ.presentation.CertificateImportActivity::class.java)
                        .putExtra("review_handle", handle).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            val consumeLaunch = { wearerLaunchUntil.getAndSet(0) > android.os.SystemClock.elapsedRealtime() }
            val importer = HostPkcs12ImportControl(pluginUid, authenticate, launch, consumeWearerLaunch = consumeLaunch)
            certificateImport = importer
            val packages = HostServerPackageControl(pluginUid, authenticate, launch, launchExport = { handle ->
                app.startActivity(android.content.Intent(app,
                    com.aitech.weartak_civ.presentation.ServerPackageExportActivity::class.java)
                    .putExtra("export_handle", handle).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }, consumeWearerLaunch = consumeLaunch)
            serverPackages = packages
            HostPcmControlV3Impl(app.audioRepo, pluginPackage, pluginUid,
                pkcs12Import = importer, serverPackages = packages)
        } else {
            null
        }
        val callback = HostCallbackImpl(
            ingress = ingress,
            settingsCoordinator = manager.settingsCoordinator,
            pluginId = pluginPackage,
            pluginUid = pluginUid,
            isActive = { !closed },
            onStatusChanged = { status ->
                if (android.os.Binder.getCallingUid() == pluginUid && isCurrentDittoConnection()) {
                    dittoPdlModeState.update(status)
                }
                val healthy = status?.state == com.weartak.plugin.sdk.model.PluginLifecycleState.RUNNING &&
                    status.extras.getBoolean(PluginDetectionEventBridge.DETECTOR_READY_EXTRA, false)
                postAudioHealth(healthy)
            },
            onError = {
                if (pluginPackage == CRM_PLUGIN_PACKAGE &&
                    android.os.Binder.getCallingUid() == pluginUid
                ) manager.crmPluginStatus.invalidate(this)
                postAudioHealth(false)
            },
            interceptSensorEvent = { event ->
                if (pluginPackage != CRM_PLUGIN_PACKAGE || event.sensorKey != CrmPluginStatusStore.SENSOR_KEY) {
                    false
                } else {
                    if (android.os.Binder.getCallingUid() == pluginUid && ready && hasVerifiedTrust &&
                        pluginBinder?.isBinderAlive == true && manager.connectionFor(pluginPackage) === this
                    ) {
                        try {
                            runBlocking {
                                pluginTrust.enforce(pluginPackage, pluginUid, ingress.packagesForUid(app, pluginUid))
                            }
                            manager.crmPluginStatus.publish(this, event, System.currentTimeMillis())
                        } catch (error: ParserException) {
                            Log.w(TAG, "CRM status trust rejected: ${error.reasonCode}")
                        } catch (error: PackageManager.NameNotFoundException) {
                            Log.w(TAG, "CRM status plugin package unavailable")
                        } catch (error: SecurityException) {
                            Log.w(TAG, "CRM status plugin package access denied")
                        } catch (error: android.os.BadParcelableException) {
                            manager.crmPluginStatus.invalidate(this)
                            Log.w(TAG, "CRM status parcel invalid")
                        }
                    }
                    true
                }
            },
        )
        val moppControl = if (pluginPackage == MoppPluginAvailability.PACKAGE) {
            HostMoppPpeControl(app.repo.moppPpeStore, pluginUid, authorize = {
                if (closed || pluginBinder?.isBinderAlive != true) {
                    false
                } else {
                    try {
                        runBlocking {
                            pluginTrust.enforce(pluginPackage, pluginUid, ingress.packagesForUid(app, pluginUid))
                        }
                        true
                    } catch (error: ParserException) {
                        Log.w(TAG, "MOPP trust rejected: ${error.reasonCode}")
                        false
                    } catch (error: android.content.pm.PackageManager.NameNotFoundException) {
                        Log.w(TAG, "MOPP package unavailable", error)
                        false
                    } catch (error: SecurityException) {
                        Log.w(TAG, "MOPP package access denied", error)
                        false
                    }
                }
            })
        } else null
        val services = HostServicesV1Impl(app, pluginPackage, pluginUid)
        val openVpnServices = if (pluginPackage == OpenVpnHostContract.PLUGIN_PACKAGE) {
            HostOpenVpnServicesV1Impl(
                app,
                pluginPackage,
                pluginUid,
                app.openVpnProviderRegistry,
                com.aitech.weartak_civ.service.TAKConnectService::onOpenVpnProviderChanged,
                com.aitech.weartak_civ.service.TAKConnectService::onOpenVpnRouteChanged,
            )
        } else null
        val routeStatusControl = HostRouteStatusControl(
            manager.settingsCoordinator.routeStatuses, pluginPackage, pluginUid,
            isBindingActive = { !closed && pluginBinder?.isBinderAlive == true }
        )
        val transportV1 = PluginTransportHostV1Impl(
            app = app,
            ingress = ingress,
            pluginId = pluginPackage,
            expectedPluginUid = pluginUid,
            pluginOnlyPli = pluginOnlyProfile,
            restartablePluginOnly = pluginOnlyProfile,
            trustedDittoPlugin = dittoBindingAuthorized,
            trafficAdmission = dittoTrafficAdmission,
            onPliUnavailable = { reason ->
                mainHandler.post { if (!closed) manager.onConnectionFailed(this, reason) }
            },
        )
        val pointEndpoint = if (pluginOnlyProfile) HostPointEndpointV1(
            expectedUid = pluginUid,
            authorized = dittoBindingAuthorized,
            sessionActive = transportV1::isActiveDittoSession,
            repository = app.repo,
            bloodhounds = app.bhRepo,
            trafficAdmission = dittoTrafficAdmission,
        ) else null
        val ppgHost = if (pluginPackage == CRM_PLUGIN_PACKAGE &&
            api >= WearTakPluginV2Contract.CONTRACT_API_VERSION
        ) PpgHostV1Impl(
            app = app,
            expectedUid = pluginUid,
            isSessionActive = {
                !closed && ready && trustVerified && pluginBinder === service && service.isBinderAlive &&
                    manager.connectionFor(pluginPackage) === this
            },
            authorize = {
                try {
                    runBlocking {
                        pluginTrust.enforce(pluginPackage, pluginUid, ingress.packagesForUid(app, pluginUid))
                    }
                    true
                } catch (error: Exception) {
                    Log.w(TAG, "PPG session authentication rejected pkg=$pluginPackage", error)
                    false
                }
            },
        ) else null
        synchronized(stateLock) {
            if (closed) {
                ppgHost?.close()
                pointEndpoint?.revoke()
                revokeCertificateSessions()
                routeStatusControl.close()
                moppControl?.close()
                controlV3?.close()
                services.close()
                openVpnServices?.close()
                transportV1.closeBinding(TransportCloseReason.STOPPED)
                return
            }
            hostControl = control
            hostCallback = callback
            hostPcmControlV3 = controlV3
            hostServicesV1 = services
            hostOpenVpnServicesV1 = openVpnServices
            hostMoppControl = moppControl
            hostRouteStatusControl = routeStatusControl
            hostTransportV1 = transportV1
            hostPointEndpoint = pointEndpoint
            hostPpgV1 = ppgHost
            manager.settingsCoordinator.registerTransportHost(pluginPackage, transportV1)
        }

        val init = PluginInitParcel(
            hostPackageName = app.packageName,
            hostUid = android.os.Process.myUid(),
            pluginIdExpected = pluginPackage,
            hostExtras = android.os.Bundle().apply {
                putInt(com.weartak.plugin.sdk.MainUiActionContract.HOST_PRESENTATION_VERSION_KEY,
                    com.weartak.plugin.sdk.MainUiActionContract.PRESENTATION_VERSION)
                putInt(HostServicesContract.EXTRA_VERSION, HostServicesContract.VERSION)
                putBinder(HostServicesContract.EXTRA_BINDER, services.asBinder())
                if (openVpnServices != null) {
                    putInt(OpenVpnHostContract.EXTRA_VERSION, OpenVpnHostContract.API_VERSION)
                    putBinder(OpenVpnHostContract.EXTRA_BINDER, openVpnServices.asBinder())
                }
                putBoolean(HostControlImpl.HOST_EXTRA_COT_ROUTING_V1, true)
                putBoolean(HostControlImpl.HOST_EXTRA_CHAT_DISPATCH_V1, true)
                putBoolean(RouteStatusContract.EXTRA_CAPABILITY, true)
                putBinder(RouteStatusContract.EXTRA_CONTROL, routeStatusControl)
                putInt(TransportContract.EXTRA_VERSION, TransportContract.VERSION)
                putBinder(TransportContract.EXTRA_CONTROL, transportV1.asBinderForInit())
                if (ppgHost != null) {
                    putInt(PpgContractV1.EXTRA_VERSION, PpgContractV1.VERSION)
                    putBinder(PpgContractV1.EXTRA_CONTROL, ppgHost.asBinder())
                }
                if (pointEndpoint != null) {
                    putInt(PointContractV1.EXTRA_VERSION, PointContractV1.VERSION)
                    putBinder(PointContractV1.EXTRA_CONTROL, pointEndpoint.asBinder())
                }
                if (pluginOnlyProfile) putInt(DITTO_PLUGIN_ONLY_KEY, 1)
                if (moppControl != null) {
                    putBoolean(MoppPpeContract.EXTRA_CAPABILITY, true)
                    putBinder(MoppPpeContract.EXTRA_CONTROL, moppControl)
                }
            },
        )
        try {
            if (controlV3 != null) {
                iface.initializePluginV3(init, control, controlV2, controlV3)
            } else {
                iface.initializePluginV2(init, control, controlV2)
            }
            if (closed) return
            manager.settingsCoordinator.onPluginConnected(pluginPackage, iface) { !closed }
            if (closed) return
            if (pluginOnlyProfile && manager.isDittoNetworkPolicyMigrationPending()) {
                val legacyEnabled = runCatching {
                    manager.settingsCoordinator.legacyDittoNetworkEnabled()
                }.getOrNull()
                if (legacyEnabled == null) {
                    Log.e(TAG, "Ditto network policy migration unavailable: legacy forwarding setting unresolved")
                    runBlocking { manager.failDittoNetworkPolicyMigration() }
                    postFailure("ditto_network_policy_migration_failed")
                    return
                }
                try {
                    runBlocking { manager.migrateLegacyDittoNetworkPolicy(legacyEnabled) }
                } catch (error: Exception) {
                    Log.e(TAG, "Ditto network policy migration failed", error)
                    runBlocking { manager.failDittoNetworkPolicyMigration() }
                    postFailure("ditto_network_policy_migration_failed")
                    return
                }
            }
            if (closed) return
            iface.registerHostCallback(callback)
            if (closed) return
            if (pluginOnlyProfile) {
                transportV1.activateBinding()
            }
            if (!pluginOnlyProfile) iface.startPlugin()
            if (closed) return
            transportV1.activateBinding()
        } catch (e: RemoteException) {
            Log.e(TAG, "plugin lifecycle failed pkg=$pluginPackage", e)
            postFailure("remote_exception")
            return
        }
        mainHandler.post {
            if (!closed && manager.onConnectionReady(this)) {
                ready = true
                if (pluginPackage == CRM_PLUGIN_PACKAGE) manager.crmPluginStatus.activate(this)
                mainHandler.removeCallbacks(handshakeTimeout)
                PluginDiagnostics.markConnected(pluginPackage)
                if (pluginPackage == TrustedCommunityPlugin.PACKAGE) manager.onConnectionInitialized(this)
                moppControl?.ready()
                routeStatusControl.ready()
                if (pluginOnlyProfile) {
                    val producer = PluginOnlyPliProducer(
                        app, transportV1, dittoBindingAuthorized,
                        onUnavailable = { transportV1.invalidatePluginPli() },
                        onRuntimeAllowed = { requestDittoRuntime(iface, it) },
                    )
                    synchronized(stateLock) {
                        if (closed) producer.close() else pluginOnlyPliProducer = producer
                    }
                    producer.start()
                }
                Log.i(TAG, "initialized plugin pkg=$pluginPackage api=$api")
            }
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        ready = false
        if (pluginPackage == CRM_PLUGIN_PACKAGE) manager.crmPluginStatus.clear(this)
        dittoPdlModeState.close()
        pluginOnlyPliProducer?.close()
        hostPointEndpoint?.revoke()
        hostTransportV1?.closeBinding(TransportCloseReason.DISCONNECTED)
        hostOpenVpnServicesV1?.close()
        revokeCertificateSessions()
        hostRouteStatusControl?.close()
        hostPpgV1?.close()
        hostMoppControl?.close()
        Log.w(TAG, "onServiceDisconnected pkg=$pluginPackage")
        manager.onConnectionLost(this@PluginConnection)
    }

    override fun onBindingDied(name: ComponentName?) {
        ready = false
        if (pluginPackage == CRM_PLUGIN_PACKAGE) manager.crmPluginStatus.clear(this)
        dittoPdlModeState.close()
        pluginOnlyPliProducer?.close()
        hostPointEndpoint?.revoke()
        hostTransportV1?.closeBinding(TransportCloseReason.DISCONNECTED)
        hostOpenVpnServicesV1?.close()
        revokeCertificateSessions()
        hostRouteStatusControl?.close()
        hostPpgV1?.close()
        hostMoppControl?.close()
        Log.w(TAG, "onBindingDied pkg=$pluginPackage")
        manager.onConnectionLost(this)
    }

    override fun onNullBinding(name: ComponentName?) {
        Log.w(TAG, "onNullBinding pkg=$pluginPackage")
        failBind("null_binder")
    }

    private fun requestDittoRuntime(iface: IWearTakPlugin, allowed: Boolean) {
        synchronized(stateLock) {
            if (closed) return
            dittoRuntimeAllowed = allowed && manager.dittoTrafficAllowed()
        }
        scheduleDittoLifecycle(iface)
    }

    private fun scheduleDittoLifecycle(iface: IWearTakPlugin) {
        synchronized(stateLock) {
            if (closed || dittoLifecycleInProgress || dittoRuntimeAllowed == dittoStartRequested) return
            dittoLifecycleInProgress = true
        }
        if (PluginLifecycleWork.execute(pluginPackage) {
            try {
                while (true) {
                    val desired = synchronized(stateLock) {
                        if (closed || dittoRuntimeAllowed == dittoStartRequested) null else dittoRuntimeAllowed
                    } ?: break
                    if (desired) iface.startPlugin() else iface.stopPlugin()
                    synchronized(stateLock) { dittoStartRequested = desired }
                }
            } catch (error: RemoteException) {
                dittoRuntimeAllowed = false
                Log.e(TAG, "Ditto plugin lifecycle failed", error)
                postFailure("remote_exception")
            } catch (error: RuntimeException) {
                dittoRuntimeAllowed = false
                Log.e(TAG, "Ditto plugin lifecycle failed", error)
                postFailure("remote_exception")
            } finally {
                val cleanup = synchronized(stateLock) {
                    dittoLifecycleInProgress = false
                    pendingRemoteCleanup.also { pendingRemoteCleanup = null }
                }
                cleanup?.invoke()
                mainHandler.post { scheduleDittoLifecycle(iface) }
            }
        }) return
        val cleanup = synchronized(stateLock) {
            dittoLifecycleInProgress = false
            if (closed) pendingRemoteCleanup.also { pendingRemoteCleanup = null }
            else {
                if (!dittoLifecycleRetryScheduled) {
                    dittoLifecycleRetryScheduled = true
                    mainHandler.postDelayed({
                        synchronized(stateLock) { dittoLifecycleRetryScheduled = false }
                        scheduleDittoLifecycle(iface)
                    }, 100L)
                }
                null
            }
        }
        if (cleanup != null) scheduleRemoteCleanup(cleanup)
    }

    private fun scheduleRemoteCleanup(cleanup: () -> Unit) {
        if (!PluginLifecycleWork.execute(pluginPackage, cleanup)) {
            mainHandler.postDelayed({ scheduleRemoteCleanup(cleanup) }, 1_000L)
        }
    }

    private fun revokeCertificateSessions() {
        sessionActive = false
        wearerLaunchUntil.set(0)
        certificateImport?.close()
        serverPackages?.close()
    }

    /**
     * Revokes local state immediately and schedules remote cleanup. [afterRemoteCleanup] runs
     * once remote cleanup has finished (or immediately when none is needed), so callers can keep
     * the Android binding — and therefore the plugin's binder — open until stop/unregister ran.
     */
    fun tearDownBinder(
        reason: String,
        shutdownRemotePlugin: Boolean = true,
        afterRemoteCleanup: (() -> Unit)? = null,
    ) {
        ready = false
        if (pluginPackage == CRM_PLUGIN_PACKAGE) manager.crmPluginStatus.clear(this)
        dittoPdlModeState.close()
        pluginOnlyPliProducer?.close()
        hostPointEndpoint?.revoke()
        revokeCertificateSessions()
        Log.i(TAG, "tearDownBinder pkg=$pluginPackage reason=$reason")
        var pointToDrain: HostPointEndpointV1? = null
        var transportToClose: PluginTransportHostV1Impl? = null
        val transportCloseReason = if (reason.contains("disconnect", ignoreCase = true) ||
            reason.contains("death", ignoreCase = true)
        ) {
            TransportCloseReason.DISCONNECTED
        } else {
            TransportCloseReason.STOPPED
        }
        var alreadyClosed = false
        var runAfterCleanupNow = false
        var cleanupDeferred = false
        val cleanup = synchronized(stateLock) {
            if (closed) {
                alreadyClosed = true
                if (afterRemoteCleanup != null) {
                    if (remoteCleanupFinished) runAfterCleanupNow = true
                    else afterRemoteCleanupActions += afterRemoteCleanup
                }
                return@synchronized null
            }
            closed = true
            afterRemoteCleanup?.let { afterRemoteCleanupActions += it }
            pointToDrain = hostPointEndpoint
            pointToDrain?.revoke()
            hostPointEndpoint = null
            revokeCertificateSessions()
            certificateImport = null
            serverPackages = null
            hostRouteStatusControl?.close()
            hostRouteStatusControl = null
            hostPpgV1?.close()
            hostPpgV1 = null
            hostMoppControl?.close()
            hostMoppControl = null
            mainHandler.removeCallbacks(handshakeTimeout)
            mainHandler.removeCallbacks(audioHealthTimeout)
            hostServicesV1?.close()
            hostOpenVpnServicesV1?.close()
            hostPcmControlV3?.close()
            transportToClose = hostTransportV1
            transportToClose?.prepareForPluginStop()
            transportToClose?.let {
                manager.settingsCoordinator.unregisterTransportHost(pluginPackage, it)
            }
            val iface = plugin
            val cb = hostCallback
            val binder = pluginBinder
            // Local revocation must not wait for a remote lifecycle transaction.
            hostCallback?.close()
            manager.settingsCoordinator.unregisterPlugin(pluginPackage)
            plugin = null
            pluginInfoSnapshot = null
            apiVersionSnapshot = -1
            hostControl = null
            hostCallback = null
            hostPcmControlV3 = null
            hostServicesV1 = null
            hostOpenVpnServicesV1 = null
            hostTransportV1 = null
            pluginBinder = null
            val cleanupReservation = if (pluginPackage == TrustedCommunityPlugin.PACKAGE &&
                (iface != null || binder != null)) PluginLifecycleWork.reserveCleanup(pluginPackage) else null
            val remoteCleanup: (() -> Unit)? = if (iface == null && binder == null) {
                transportToClose?.let { transport -> {
                    try {
                        transport.closeBinding(transportCloseReason)
                    } finally {
                        finishRemoteCleanup()
                    }
                } }
            } else ({
                var cleanupSucceeded = true
                fun attempt(method: String, block: () -> Unit) {
                    runCatching(block).onFailure {
                        cleanupSucceeded = false
                        Log.w(TAG, "$method failed pkg=$pluginPackage", it)
                    }
                }
                attempt("unregisterHostCallback") { if (iface != null && cb != null) iface.unregisterHostCallback(cb) }
                attempt("stopPlugin") { iface?.stopPlugin() }
                if (shutdownRemotePlugin) attempt("shutdownPlugin") { iface?.shutdownPlugin() }
                attempt("closeTransport") { transportToClose?.closeBinding(transportCloseReason) }
                runCatching { binder?.unlinkToDeath(deathRecipient, 0) }.onFailure {
                    Log.w(TAG, "unlinkToDeath failed pkg=$pluginPackage", it)
                }
                // A dead process holds no plugin runtime; a frozen-but-alive one stays fenced.
                val binderProvenDead = binder != null && !binder.isBinderAlive
                // Release the binding only after stop/unregister ran, then the replacement fence.
                finishRemoteCleanup()
                if (cleanupReservation != null) {
                    if (cleanupSucceeded || binderProvenDead) {
                        if (!cleanupSucceeded) Log.w(TAG, "Ditto cleanup incomplete but binder dead; releasing fence")
                        PluginLifecycleWork.finishCleanup(pluginPackage, cleanupReservation)
                    } else Log.e(TAG, "Ditto cleanup uncertain; replacement binding remains fenced")
                }
            })
            if (initializing || dittoLifecycleInProgress) {
                pendingRemoteCleanup = remoteCleanup ?: { finishRemoteCleanup() }
                cleanupDeferred = true
                null
            } else remoteCleanup
        }
        if (alreadyClosed) {
            if (runAfterCleanupNow) runAfterRemoteCleanup(listOfNotNull(afterRemoteCleanup))
            return
        }
        pointToDrain?.let { endpoint ->
            appScope.launch(Dispatchers.IO) {
                if (!endpoint.closeAndDrain(5_000)) Log.e(TAG, "Point endpoint drain incomplete")
            }
        }
        when {
            cleanup != null -> scheduleRemoteCleanup(cleanup)
            !cleanupDeferred -> finishRemoteCleanup()
        }
    }

    private fun finishRemoteCleanup() {
        val actions = synchronized(stateLock) {
            remoteCleanupFinished = true
            afterRemoteCleanupActions.toList().also { afterRemoteCleanupActions.clear() }
        }
        runAfterRemoteCleanup(actions)
    }

    private fun runAfterRemoteCleanup(actions: List<() -> Unit>) {
        actions.forEach { action ->
            runCatching(action).onFailure { Log.w(TAG, "post-cleanup action failed pkg=$pluginPackage", it) }
        }
    }

    internal fun pluginInfoSnapshot(): PluginInfoParcel? = pluginInfoSnapshot

    internal fun handlerStatus(): com.weartak.plugin.sdk.model.PluginStatusParcel? = trustedStatus()

    internal fun isCurrentDittoConnection(): Boolean =
        pluginPackage == TrustedCommunityPlugin.PACKAGE && ready && hasVerifiedTrust &&
            manager.dittoTrafficAllowed() &&
            pluginBinder?.isBinderAlive == true && manager.connectionFor(pluginPackage) === this

    internal fun mayRequestDittoMeshPermissions(): Boolean =
        isCurrentDittoConnection() && manager.dittoTrafficAllowed() &&
            (!dittoUsesPluginOnlyLifecycle || (pluginOnlyEnabled() && dittoRuntimeAllowed))

    internal fun readFreshDittoMeshStatus(callback: (DittoMeshStatus?) -> Unit): Boolean {
        if (!isCurrentDittoConnection()) return false
        val revision = dittoPdlModeState.readRevision()
        return DittoPdlStatusWork.execute {
            val status = try {
                trustedStatus()
            } catch (error: RemoteException) {
                Log.w(TAG, "Ditto mesh status unavailable", error)
                null
            } catch (error: RuntimeException) {
                Log.w(TAG, "Ditto mesh status invalid", error)
                null
            }
            if (isCurrentDittoConnection()) dittoPdlModeState.completeRead(revision, status)
            mainHandler.post {
                callback(if (isCurrentDittoConnection() && dittoPdlModeState.isReadCurrent(revision)) {
                    DittoMeshStatus.parse(status)
                } else null)
            }
        }
    }

    internal fun refreshDittoPdlMode() {
        if (!isCurrentDittoConnection()) return
        val revision = dittoPdlModeState.readRevision()
        val started = DittoPdlStatusWork.execute {
            try {
                val status = trustedStatus()
                if (isCurrentDittoConnection()) dittoPdlModeState.completeRead(revision, status)
            } catch (error: RemoteException) {
                Log.w(TAG, "Ditto PDL status unavailable", error)
                dittoPdlModeState.completeRead(revision, null)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Ditto PDL status invalid", error)
                dittoPdlModeState.completeRead(revision, null)
            }
        }
        if (!started) dittoPdlModeState.completeRead(revision, null)
    }

    private fun trustedStatus(): com.weartak.plugin.sdk.model.PluginStatusParcel? {
        val iface = synchronized(stateLock) {
            if (!ready || !hasVerifiedTrust || pluginBinder?.isBinderAlive != true) null else plugin
        } ?: return null
        val status = iface.status
        return synchronized(stateLock) {
            status.takeIf {
                ready && hasVerifiedTrust && plugin === iface && pluginBinder?.isBinderAlive == true &&
                    manager.connectionFor(pluginPackage) === this
            }
        }
    }

    internal fun apiVersionSnapshot(): Int = apiVersionSnapshot

    internal fun allowCertificateReviewFromWearerLaunch() {
        wearerLaunchUntil.set(android.os.SystemClock.elapsedRealtime() + 300_000L)
        certificateImport?.allowReviewFromWearerLaunch()
        serverPackages?.allowFromWearerLaunch()
    }

    companion object {
        private const val TAG = "CEP-PluginConnection"
        private const val CRM_PLUGIN_PACKAGE = "com.weartak.plugin.crm"
        private const val HANDSHAKE_TIMEOUT_MS = 10_000L
        private const val AUDIO_HEALTH_TIMEOUT_MS = 15_000L
        internal const val DITTO_PLUGIN_ONLY_KEY = "com.weartak.plugin.ditto.pluginOnly.version"
    }
}
