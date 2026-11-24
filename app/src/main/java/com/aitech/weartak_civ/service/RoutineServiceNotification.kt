package com.aitech.weartak_civ.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.presentation.MainActivity
import java.util.IdentityHashMap

/**
 * Each service still enters the foreground itself, with its manifest service type.
 * Only the notification is shared; Android supports multiple foreground owners of one ID.
 */
internal object RoutineServiceNotification {
    const val ID = 64_002
    const val SIMULATION_ALERT_ID = 64_003
    const val CHANNEL_ID = "weartak_active_services"
    private const val TAG = "WearTAK-ServiceNotification"

    private data class Capability(val legacyId: Int, @StringRes val label: Int)

    private val capabilities = mapOf(
        "ReactiveServiceManager" to Capability(77, R.string.notification_service_management),
        "CotDispatchService" to Capability(100, R.string.notification_reporting),
        "TAKConnectService" to Capability(101, R.string.notification_tak_transport),
        "ChatService" to Capability(101, R.string.notification_chat),
        "BatteryService" to Capability(102, R.string.notification_battery),
        "OffBodyDetectService" to Capability(103, R.string.notification_wear_detection),
        "PressureService" to Capability(104, R.string.notification_pressure),
        "HeartrateService" to Capability(105, R.string.notification_heart_rate),
        "MovementDetectionService" to Capability(112, R.string.notification_movement),
        "SkinTempService" to Capability(177, R.string.notification_skin_temperature),
        "GeofenceService" to Capability(178, R.string.notification_geofences),
        "SitxTakService" to Capability(179, R.string.notification_sitx),
        "ActivityRecognitionService" to Capability(192, R.string.notification_activity),
        "PowerManagerService" to Capability(555, R.string.notification_power),
        "BloodhoundService" to Capability(848, R.string.notification_bloodhound),
        "UasOnnxService" to Capability(920, R.string.notification_uas),
        "CompanionAppService" to Capability(999, R.string.notification_companion),
        "ReportingIntervalStrategyService" to Capability(1123, R.string.notification_reporting_policy),
        "WeatherService" to Capability(1137, R.string.notification_weather),
        "BleBeaconService" to Capability(1166, R.string.notification_beacons),
        "LocationService" to Capability(1234, R.string.notification_location),
        "EnvSensorService" to Capability(2701, R.string.notification_environment),
        "DittoService" to Capability(3333, R.string.notification_ditto)
    )
    private val owners = IdentityHashMap<Service, Int>()

    @Synchronized
    fun startIfRoutine(service: Service): Boolean? {
        val capability = capabilities[service.javaClass.simpleName] ?: return null
        return start(service, capability.label)
    }

    /** Returns false when Android refuses foreground promotion (app not in an FGS-allowed state). */
    @Synchronized
    internal fun start(service: Service, @StringRes label: Int): Boolean {
        val previous = owners.put(service, label)
        try {
            val notification = build(service)
            service.startForeground(ID, notification)
            return true
        } catch (error: RuntimeException) {
            if (previous == null) owners.remove(service) else owners[service] = previous
            if (owners.isNotEmpty()) publish(service) else manager(service).cancel(ID)
            if (isForegroundStartRefusal(error)) {
                Log.w(TAG, "Foreground start refused for ${service.javaClass.simpleName}", error)
                return false
            }
            Log.e(TAG, "Failed to promote routine foreground service", error)
            throw error
        }
    }

    fun isForegroundStartRefusal(error: Throwable): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && error is ForegroundServiceStartNotAllowedException

    @Synchronized
    fun stopIfRoutine(service: Service): Boolean {
        if (!capabilities.containsKey(service.javaClass.simpleName) && !owners.containsKey(service)) return false
        stop(service)
        return true
    }

    @Synchronized
    internal fun stop(service: Service) {
        if (!owners.containsKey(service)) return
        // REMOVE is shared-ID-aware in Android: another foreground owner keeps the card.
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        owners.remove(service)
        if (owners.isEmpty()) manager(service).cancel(ID) else publish(service)
    }

    /** Robolectric shares this object across test classes; drop owners leaked by other tests. */
    @Synchronized
    internal fun resetForTest() = owners.clear()

    @Synchronized
    fun reconcileProcessStart(context: Context) {
        if (owners.isNotEmpty()) return
        val manager = manager(context)
        manager.cancel(ID)
        // Migrate only known routine cards. In particular, do not delete an old test alert
        // that collided with Companion's legacy ID 999.
        val legacyChannels = capabilities.map { (name, capability) ->
            (if (name == "ReactiveServiceManager") "ServiceManager_channel"
            else if (name == "EnvSensorService") "env_sensor_channel"
            else "${name}_channel") to capability.legacyId
        }.toMap() + ("GunshotOnnxService_channel" to 919)
        manager.activeNotifications.forEach { entry ->
            if (legacyChannels[entry.notification.channelId] == entry.id) {
                manager.cancel(entry.tag, entry.id)
            }
        }
    }

    private fun publish(context: Context) {
        manager(context).notify(ID, build(context))
    }

    private fun build(context: Context): Notification {
        manager(context).createNotificationChannel(NotificationChannel(
            CHANNEL_ID, context.getString(R.string.notification_background_services),
            NotificationManager.IMPORTANCE_LOW
        ))
        val labels = owners.values.map(context::getString).distinct().sorted()
        val text = context.resources.getQuantityString(
            R.plurals.notification_services_running, owners.size, owners.size
        )
        val open = PendingIntent.getActivity(context, ID,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.weartak_logo)
            .setContentTitle(context.getString(R.string.notification_weartak_active))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                text + "\n" + labels.joinToString(", ")
            ))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
    }

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)
}
