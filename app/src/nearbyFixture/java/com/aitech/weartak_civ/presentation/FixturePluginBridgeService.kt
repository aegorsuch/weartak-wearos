package com.aitech.weartak_civ.presentation

import android.app.Service
import android.content.*
import android.os.*
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.integration.plugin.HostServerPackageControl
import com.aitech.weartak_civ.integration.plugin.ReviewRegistry
import com.aitech.weartak_civ.integration.plugin.ServerExportRegistry
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.weartak.plugin.sdk.aidl.*
import com.weartak.plugin.sdk.model.*
import com.weartak.plugin.sdk.v3.aidl.*
import com.weartak.plugin.sdk.v3.model.*
import com.weartak.plugin.sdk.serverpackage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Debug fixture only: signature-bound real plugin Binder, synthetic data, no TAK/network startup. */
class FixturePluginBridgeService : Service(), ServiceConnection {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var plugin: IWearTakPlugin? = null
    private var bound = false
    private var binder: IBinder? = null
    private val death = IBinder.DeathRecipient { control?.close(); ready.value = false }
    override fun onCreate() {
        super.onCreate()
        val channel = "nearby-offline-fixture"
        getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(
            android.app.NotificationChannel(channel, "Offline Nearby fixture",
                android.app.NotificationManager.IMPORTANCE_LOW))
        startForeground(9731, android.app.Notification.Builder(this, channel)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Synthetic Nearby fixture")
            .setContentText("Offline Binder integration; no Nearby radio")
            .setOngoing(true).build())
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!bound) {
            bound = bindService(Intent("com.weartak.plugin.action.PLUGIN_SERVICE")
                .setComponent(ComponentName(PLUGIN, "$PLUGIN.NearbyDropService")), this, BIND_AUTO_CREATE)
            if (!bound) Log.e("NearbyFixture", "Plugin service binding failed")
        }
        return START_NOT_STICKY
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        val remote = requireNotNull(service)
        binder = remote
        remote.linkToDeath(death, 0)
        val uid = packageManager.getApplicationInfo(PLUGIN, 0).uid
        val next = HostServerPackageControl(uid, {
            remote.isBinderAlive && packageManager.getPackagesForUid(uid)?.toSet() == setOf(PLUGIN) &&
                packageManager.checkSignatures(Process.myUid(), uid) == android.content.pm.PackageManager.SIGNATURE_MATCH
        }, { handle -> startActivity(Intent(this, FixturePluginReviewActivity::class.java)
            .putExtra("handle", handle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            { handle -> startActivity(Intent(this, FixturePluginExportActivity::class.java)
                .putExtra("handle", handle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
        control?.close()
        control = next
        backend = SyntheticPluginStore()
        val v3 = object : IWearTakPluginHostControlV3.Stub() {
            override fun getHostCapabilities() = HostCapabilitiesV3Parcel(contractApiVersion = 3, pcmStreaming = false)
            override fun subscribePcm(request: PcmSubscriptionRequestParcel?, callback: IWearTakPcmCallback?) =
                PcmSubscriptionResultParcel(accepted = false, status = PcmSubscriptionStatus.INVALID_REQUEST)
            override fun unsubscribePcm(subscriptionId: String?) =
                PcmSubscriptionResultParcel(accepted = false, status = PcmSubscriptionStatus.NOT_ACTIVE)
            override fun getPkcs12ImportCapability() = com.weartak.plugin.sdk.nearbyimport.model.Pkcs12ImportCapabilityParcel()
            override fun getPkcs12ImportControlV1(): com.weartak.plugin.sdk.nearbyimport.aidl.IWearTakPkcs12ImportControlV1? = null
            override fun getServerPackageCapability() = next.capability()
            override fun getServerPackageControlV1() = next.authenticatedBinder()
        }
        val v1 = object : IWearTakPluginControl.Stub() {
            override fun requestCotDispatch(request: CotRequestParcel?) = unsupported()
            override fun requestAlert(request: AlertRequestParcel?) = unsupported()
            override fun requestToast(request: ToastRequestParcel?) = unsupported()
            override fun requestMarkerPublish(marker: MarkerParcel?) = unsupported()
            override fun requestMarkerUpdate(marker: MarkerParcel?) = unsupported()
            override fun requestMarkerClear(marker: MarkerParcel?) = unsupported()
            override fun getDeviceStatus(): DeviceStatusParcel = throw UnsupportedOperationException("Offline fixture")
            override fun registerCepRules(pluginId: String?, sensorKey: String?, rulesJson: String?) = unsupported()
            override fun unregisterCepRules(pluginId: String?, sensorKey: String?) = unsupported()
            override fun applyCepRuleEvent(event: CepRuleEventParcel?) = unsupported()
            override fun isCotConnected() = false
            override fun getHostCapabilities() = HostCapabilitiesParcel()
            override fun getMapLayerControl(): IWearTakMapLayerControl? = null
            private fun unsupported(): Unit = throw UnsupportedOperationException("Offline fixture")
        }
        val callback = object : IWearTakHostCallback.Stub() {
            override fun onSensorEvent(event: SensorEventParcel?) {}
            override fun onSensorBatchEvent(events: MutableList<SensorEventParcel>?) {}
            override fun onCotReadyEvent(event: CotReadyEventParcel?) {}
            override fun onPluginSettingsSchemaChanged() {}
            override fun onPluginSettingsValuesChanged() {}
            override fun onPluginMainUiSchemaChanged() {}
            override fun onPluginMainUiStateChanged() {}
            override fun onPluginStatusChanged(status: PluginStatusParcel?) {}
            override fun onPluginError(error: PluginErrorParcel?) { Log.e("NearbyFixture", "Plugin reported fixture error") }
            override fun onSharedBufferAvailable(buffer: SharedBufferParcel?) {}
        }
        scope.launch {
            try {
                val endpoint = IWearTakPlugin.Stub.asInterface(remote)
                endpoint.registerHostCallback(callback)
                endpoint.initializePluginV3(PluginInitParcel(packageName, Process.myUid(), PLUGIN), v1, null, v3)
                endpoint.startPlugin()
                plugin = endpoint
                ready.value = true
            } catch (_: Exception) {
                Log.e("NearbyFixture", "Plugin V3 fixture initialization failed")
                next.close()
            }
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) { control?.close(); ready.value = false }
    override fun onDestroy() {
        ready.value = false
        control?.close(); control = null
        scope.cancel()
        binder?.unlinkToDeath(death, 0)
        if (bound) unbindService(this)
        super.onDestroy()
    }
    companion object {
        const val PLUGIN = "com.weartak.plugin.nearbydrop"
        val ready = MutableStateFlow(false)
        var control: HostServerPackageControl? = null
        internal var backend = SyntheticPluginStore()
    }
}

internal class SyntheticPluginStore : CertificateReviewBackend, ServerExporter {
    private var lastExport: ByteArray? = null
    private var exportAt = 0L
    @Synchronized fun takeSyntheticReceive(): ByteArray? {
        val bytes = lastExport
        lastExport = null
        if (SystemClock.elapsedRealtime() - exportAt > 300_000) { bytes?.fill(0); return null }
        return bytes
    }
    private var server = DataRepository.TakServerItem(true, "Synthetic event", "event.example", 8089,
        false, "", "", "", "", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
    override suspend fun allowed() = true
    override suspend fun destinations() = listOf(server)
    override suspend fun snapshot(server: DataRepository.TakServerItem) = CertificateDestination(server.copy(), null)
    override suspend fun export(destination: CertificateDestination, identity: Boolean, credentials: Boolean): ExportedServerPackage {
        val bundle = ServerPackage("Synthetic event", "event.example", 8089, ServerPackageTrustMode.PUBLIC_CA,
            if (identity) ServerPackageIdentityMode.SHARED_IDENTITY else ServerPackageIdentityMode.RECEIVER_ENROLLMENT,
            clientPkcs12 = if (identity) fixturePkcs12(false) else null,
            clientPkcs12Password = if (identity) "fixture-only".toByteArray() else null,
            username = if (credentials) "synthetic" else null,
            password = if (credentials) "fixture-only".toByteArray() else null)
        return try {
            val bytes = ServerPackageCodec.encode(bundle)
            synchronized(this) {
                lastExport?.fill(0); lastExport = bytes.copyOf(); exportAt = SystemClock.elapsedRealtime()
            }
            ExportedServerPackage(bytes) { true }
        } finally { bundle.wipe() }
    }
    override suspend fun apply(destination: CertificateDestination?, proposed: DataRepository.TakServerItem, ca: String?,
        authorized: () -> Boolean, complete: () -> Boolean): Boolean {
        check(authorized())
        val before = server
        server = proposed
        if (!complete()) { server = before; return false }
        return true
    }
}

class FixturePluginReviewActivity : ComponentActivity() {
    private var handle: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = intent.getStringExtra("handle") ?: return finish()
        val control = ReviewRegistry.get(token) ?: return finish()
        if (!control.claimReview(token)) return finish()
        handle = token
        setContent { WearTAKCIVTheme {
            ServerPackageReviewPage(control, token, FixturePluginBridgeService.backend, ::finish)
        } }
    }
    override fun onStop() {
        handle?.let { ReviewRegistry.get(it)?.dismiss(it) }
        super.onStop(); finish()
    }
}

class FixturePluginExportActivity : ComponentActivity() {
    private var handle: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = intent.getStringExtra("handle") ?: return finish()
        val control = ServerExportRegistry.get(token) ?: return finish()
        if (!control.claimExport(token)) return finish()
        handle = token
        setContent { WearTAKCIVTheme {
            ServerPackageExportPage(control, token, FixturePluginBridgeService.backend, ::finish)
        } }
    }
    override fun onStop() {
        handle?.let { ServerExportRegistry.get(it)?.dismissPendingExport(it) }
        super.onStop(); finish()
    }
}
