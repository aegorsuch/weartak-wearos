package com.aitech.weartak_civ.integration.plugin

import android.util.Log
import com.aitech.weartak_civ.cep.engine.config.ReasonCodes
import com.weartak.plugin.sdk.model.CotReadyEventParcel
import com.weartak.plugin.sdk.model.PluginErrorParcel
import com.weartak.plugin.sdk.model.PluginStatusParcel
import com.weartak.plugin.sdk.model.SharedBufferParcel
import com.weartak.plugin.sdk.model.HostSettingsParcel
import com.weartak.plugin.sdk.model.SettingsApplyResultParcel
import com.weartak.plugin.sdk.model.ToastRequestParcel
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Lightweight plugin integration diagnostics (discover / bind / reject / death).
 * In-memory counters + [integrationState] for tests and host-side debugging (no UI, no persistence).
 */
object PluginDiagnostics {
    private const val TAG = "CEP-PluginDiag"

    /** Stable category keys for counters, [snapshot], and [integrationState]. */
    object AdmissionCategory {
        const val PLUGIN_INFO_MISMATCH = "plugin_info_mismatch"
        const val TRUST_FAILURE = "trust_failure"
        const val UID_PACKAGE_MISMATCH = "uid_package_mismatch"
        const val BINDER_PROTOCOL = "binder_protocol"
        const val LIFECYCLE_DISCONNECT = "lifecycle_disconnect"
        const val BINDER_DEATH = "binder_death"
        /** [PluginManager.unbindPackage] / shutdown-driven unbind. */
        const val EXPLICIT_UNBIND = "explicit_unbind"
        /** [PluginIngressPort.onPackageFullyRemoved] (package uninstall). */
        const val PACKAGE_REMOVED = "package_removed"
        /** [markRejected] without structured admission (legacy / tests). */
        const val LEGACY_REJECTION = "legacy_rejection"
    }

    private val lock = Any()
    private val discovered = Collections.synchronizedSet(mutableSetOf<String>())
    private val bound = Collections.synchronizedSet(mutableSetOf<String>())
    private val connected = Collections.synchronizedSet(mutableSetOf<String>())
    private val rejected = Collections.synchronizedSet(mutableSetOf<String>())
    private val callbackState = MutableStateFlow(HostCallbackState())

    private val categoryCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val lastAdmissionDetailByPackage = ConcurrentHashMap<String, String>()

    val bindFailures = AtomicInteger(0)
    val deniedAdmission = AtomicInteger(0)
    val binderDeaths = AtomicInteger(0)
    val unbindEvents = AtomicInteger(0)
    private val discoveryRefreshes = AtomicInteger(0)
    private val packageRemovals = AtomicInteger(0)
    private val unsupportedV2CallCountByPlugin = ConcurrentHashMap<String, AtomicInteger>()

    /** All [AdmissionCategory] values plus any extra keys that ever appear in [categoryCounts]. */
    fun stableCategoryKeys(): List<String> = listOf(
        AdmissionCategory.PLUGIN_INFO_MISMATCH,
        AdmissionCategory.TRUST_FAILURE,
        AdmissionCategory.UID_PACKAGE_MISMATCH,
        AdmissionCategory.BINDER_PROTOCOL,
        AdmissionCategory.LIFECYCLE_DISCONNECT,
        AdmissionCategory.BINDER_DEATH,
        AdmissionCategory.EXPLICIT_UNBIND,
        AdmissionCategory.PACKAGE_REMOVED,
        AdmissionCategory.LEGACY_REJECTION,
    )

    /**
     * Maps [PluginConnection.failBind] / [PluginManager.onConnectionFailed] reason strings
     * to a stable admission category for counters and logs.
     */
    fun admissionCategoryForAidlBindFailure(reason: String): String {
        if (reason.startsWith("trust_failure:")) {
            val code = reason.removePrefix("trust_failure:")
            return if (code == ReasonCodes.PLUGIN_ID_PACKAGE_MISMATCH) {
                AdmissionCategory.UID_PACKAGE_MISMATCH
            } else {
                AdmissionCategory.TRUST_FAILURE
            }
        }
        return when (reason) {
            "plugin_info_mismatch" -> AdmissionCategory.PLUGIN_INFO_MISMATCH
            "uid_package_mismatch" -> AdmissionCategory.UID_PACKAGE_MISMATCH
            "null_binder", "asInterface", "get_plugin_info", "remote_exception" -> AdmissionCategory.BINDER_PROTOCOL
            else -> AdmissionCategory.BINDER_PROTOCOL
        }
    }

    /**
     * Queryable view of current plugin integration diagnostics (same data as [snapshot], plus
     * stable per-category map entries and a debug string). Does not persist; suitable for logging
     * or dev settings hooks later.
     */
    fun integrationState(): PluginIntegrationState {
        synchronized(lock) {
            val snap = snapshotUnlocked()
            val cats = stableCategoryKeys().associateWith { snap.categoryCounts[it] ?: 0 }
            return PluginIntegrationState(snapshot = snap, stableCategories = cats)
        }
    }

    val hostCallbackState: StateFlow<HostCallbackState> get() = callbackState

    fun snapshot(): Snapshot {
        synchronized(lock) {
            return snapshotUnlocked()
        }
    }

    private fun snapshotUnlocked(): Snapshot {
        return Snapshot(
            discovered = discovered.toSet(),
            bound = bound.toSet(),
            connected = connected.toSet(),
            rejected = rejected.toSet(),
            bindFailures = bindFailures.get(),
            deniedAdmission = deniedAdmission.get(),
            binderDeaths = binderDeaths.get(),
            unbindEvents = unbindEvents.get(),
            discoveryRefreshCount = discoveryRefreshes.get(),
            packageRemovals = packageRemovals.get(),
            categoryCounts = categoryCounts.mapValues { it.value.get() },
            lastAdmissionDetailByPackage = lastAdmissionDetailByPackage.toMap(),
        )
    }

    fun markDiscovered(packageNames: Collection<String>) {
        synchronized(lock) {
            discovered.clear()
            discovered.addAll(packageNames)
            discoveryRefreshes.incrementAndGet()
        }
        Log.i(TAG, "discovered plugins: ${packageNames.joinToString()}")
    }

    fun markBound(pkg: String) {
        bound.add(pkg)
        rejected.remove(pkg)
        lastAdmissionDetailByPackage.remove(pkg)
        Log.i(TAG, "bound: $pkg")
    }

    fun markUnbound(pkg: String) {
        bound.remove(pkg)
        connected.remove(pkg)
        unbindEvents.incrementAndGet()
        bumpCategory(AdmissionCategory.EXPLICIT_UNBIND)
        Log.i(TAG, "unbound: $pkg")
    }

    fun markConnected(pkg: String) {
        connected.add(pkg)
        rejected.remove(pkg)
        lastAdmissionDetailByPackage.remove(pkg)
        Log.i(TAG, "connected: $pkg")
    }

    fun markDisconnected(pkg: String) {
        bound.remove(pkg)
        connected.remove(pkg)
        bumpCategory(AdmissionCategory.LIFECYCLE_DISCONNECT)
        Log.i(TAG, "disconnected: $pkg")
    }

    fun markRejected(pkg: String, reason: String) {
        bound.remove(pkg)
        connected.remove(pkg)
        rejected.add(pkg)
        deniedAdmission.incrementAndGet()
        bumpCategory(AdmissionCategory.LEGACY_REJECTION)
        lastAdmissionDetailByPackage[pkg] = reason
        Log.w(TAG, "rejected $pkg: $reason")
    }

    /**
     * Structured admission denial (AIDL bind path and connection teardown).
     * Increments [deniedAdmission], records [pkg] in [rejected], bumps per-category counters.
     */
    fun recordAdmissionRejected(pkg: String, category: String, detail: String?) {
        synchronized(lock) {
            bound.remove(pkg)
            connected.remove(pkg)
            rejected.add(pkg)
        }
        deniedAdmission.incrementAndGet()
        bumpCategory(category)
        if (!detail.isNullOrBlank()) {
            lastAdmissionDetailByPackage[pkg] = detail
        }
        val suffix = if (detail.isNullOrBlank()) "" else " detail=$detail"
        Log.w(TAG, "admission rejected pkg=$pkg category=$category$suffix")
    }

    private fun bumpCategory(category: String) {
        categoryCounts.getOrPut(category) { AtomicInteger(0) }.incrementAndGet()
    }

    /**
     * Transport / discovery bind failure (cannot connect to service).
     * @param includeBinderProtocolCategory when false, only [bindFailures] increments — use when
     *   [PluginManager.onConnectionFailed] will record a structured admission row for the same
     *   failure (avoids double-counting [AdmissionCategory.BINDER_PROTOCOL]).
     */
    fun recordBindFailure(component: String, t: Throwable?, includeBinderProtocolCategory: Boolean = true) {
        bindFailures.incrementAndGet()
        if (includeBinderProtocolCategory) {
            bumpCategory(AdmissionCategory.BINDER_PROTOCOL)
        }
        Log.w(TAG, "bind failure for $component", t)
    }

    fun recordBinderDeath(pkg: String) {
        binderDeaths.incrementAndGet()
        bumpCategory(AdmissionCategory.BINDER_DEATH)
        Log.w(TAG, "binder death: $pkg")
    }

    /** Host received package-removed; CEP eviction is async — this counts the integration event only. */
    fun recordPackageRemoved(packageName: String) {
        packageRemovals.incrementAndGet()
        bumpCategory(AdmissionCategory.PACKAGE_REMOVED)
        Log.i(TAG, "package removed: $packageName")
    }

    fun recordCotReady(pluginId: String, event: CotReadyEventParcel?) {
        synchronized(lock) {
            callbackState.value = callbackState.value.copy(
                lastCotReadyEventByPlugin = callbackState.value.lastCotReadyEventByPlugin + (pluginId to event),
                cotReadyEventCountByPlugin = bumpCount(callbackState.value.cotReadyEventCountByPlugin, pluginId),
            )
        }
        Log.i(TAG, "cot ready pluginId=$pluginId event=$event")
    }

    fun recordPluginStatus(pluginId: String, status: PluginStatusParcel?) {
        synchronized(lock) {
            callbackState.value = callbackState.value.copy(
                lastPluginStatusByPlugin = callbackState.value.lastPluginStatusByPlugin + (pluginId to status),
                pluginStatusCountByPlugin = bumpCount(callbackState.value.pluginStatusCountByPlugin, pluginId),
            )
        }
        Log.i(TAG, "plugin status pluginId=$pluginId status=$status")
    }

    fun recordPluginError(pluginId: String, error: PluginErrorParcel?) {
        synchronized(lock) {
            callbackState.value = callbackState.value.copy(
                lastPluginErrorByPlugin = callbackState.value.lastPluginErrorByPlugin + (pluginId to error),
                pluginErrorCountByPlugin = bumpCount(callbackState.value.pluginErrorCountByPlugin, pluginId),
            )
        }
        Log.w(TAG, "plugin error pluginId=$pluginId error=$error")
    }

    fun recordSharedBuffer(pluginId: String, buffer: SharedBufferParcel?) {
        synchronized(lock) {
            callbackState.value = callbackState.value.copy(
                lastSharedBufferByPlugin = callbackState.value.lastSharedBufferByPlugin + (pluginId to buffer),
                sharedBufferCountByPlugin = bumpCount(callbackState.value.sharedBufferCountByPlugin, pluginId),
            )
        }
        Log.i(TAG, "shared buffer pluginId=$pluginId buffer=$buffer")
    }

    fun recordUnsupportedV2Call(pluginId: String, method: String, detail: String) {
        unsupportedV2CallCountByPlugin
            .computeIfAbsent(pluginId) { AtomicInteger(0) }
            .incrementAndGet()
        Log.i(TAG, "unsupported v2 call pluginId=$pluginId method=$method detail=$detail")
    }

    fun unsupportedV2CallCount(pluginId: String): Int =
        unsupportedV2CallCountByPlugin[pluginId]?.get() ?: 0

    fun recordToast(pluginId: String, toast: ToastRequestParcel?) {
        synchronized(lock) {
            callbackState.value = callbackState.value.copy(
                lastToastByPlugin = callbackState.value.lastToastByPlugin + (pluginId to toast),
                toastCountByPlugin = bumpCount(callbackState.value.toastCountByPlugin, pluginId),
            )
        }
        Log.i(TAG, "toast pluginId=$pluginId toast=$toast")
    }

    fun recordSettingsCache(pluginId: String, state: PluginSettingsState?) {
        synchronized(lock) {
            callbackState.value = callbackState.value.copy(
                lastSettingsStateByPlugin = callbackState.value.lastSettingsStateByPlugin + (pluginId to state),
            )
        }
        Log.i(TAG, "settings cache pluginId=$pluginId supported=${state?.settingsSupported}")
    }

    fun recordHostSettingsPush(pluginId: String, snapshot: HostSettingsParcel?) {
        synchronized(lock) {
            callbackState.value = callbackState.value.copy(
                lastHostSettingsByPlugin = callbackState.value.lastHostSettingsByPlugin + (pluginId to snapshot),
                hostSettingsPushCountByPlugin = bumpCount(callbackState.value.hostSettingsPushCountByPlugin, pluginId),
            )
        }
        Log.i(TAG, "host settings push pluginId=$pluginId revision=${snapshot?.revision}")
    }

    fun recordSettingsApply(pluginId: String, result: SettingsApplyResultParcel?) {
        synchronized(lock) {
            callbackState.value = callbackState.value.copy(
                lastSettingsApplyByPlugin = callbackState.value.lastSettingsApplyByPlugin + (pluginId to result),
            )
        }
        Log.i(TAG, "settings apply pluginId=$pluginId success=${result?.success}")
    }

    fun recordSettingsSchemaChanged(pluginId: String) {
        synchronized(lock) {
            callbackState.value = callbackState.value.copy(
                settingsSchemaChangeCountByPlugin = bumpCount(callbackState.value.settingsSchemaChangeCountByPlugin, pluginId),
            )
        }
        Log.i(TAG, "settings schema changed pluginId=$pluginId")
    }

    fun recordSettingsValuesChanged(pluginId: String) {
        synchronized(lock) {
            callbackState.value = callbackState.value.copy(
                settingsValuesChangeCountByPlugin = bumpCount(callbackState.value.settingsValuesChangeCountByPlugin, pluginId),
            )
        }
        Log.i(TAG, "settings values changed pluginId=$pluginId")
    }

    fun recordMainUiSchemaChanged(pluginId: String) {
        synchronized(lock) {
            callbackState.value = callbackState.value.copy(
                mainUiSchemaChangeCountByPlugin = bumpCount(callbackState.value.mainUiSchemaChangeCountByPlugin, pluginId),
            )
        }
        Log.i(TAG, "main ui schema changed pluginId=$pluginId")
    }

    fun recordMainUiStateChanged(pluginId: String) {
        synchronized(lock) {
            callbackState.value = callbackState.value.copy(
                mainUiStateChangeCountByPlugin = bumpCount(callbackState.value.mainUiStateChangeCountByPlugin, pluginId),
            )
        }
        Log.i(TAG, "main ui state changed pluginId=$pluginId")
    }

    /** Clears counters and sets for deterministic unit/Robolectric tests. */
    fun resetForTest() {
        synchronized(lock) {
            discovered.clear()
            bound.clear()
            connected.clear()
            rejected.clear()
            lastAdmissionDetailByPackage.clear()
            callbackState.value = HostCallbackState()
        }
        categoryCounts.clear()
        bindFailures.set(0)
        deniedAdmission.set(0)
        binderDeaths.set(0)
        unbindEvents.set(0)
        discoveryRefreshes.set(0)
        packageRemovals.set(0)
        unsupportedV2CallCountByPlugin.clear()
    }

    private fun bumpCount(map: Map<String, Int>, pluginId: String): Map<String, Int> {
        val next = (map[pluginId] ?: 0) + 1
        return map + (pluginId to next)
    }

    data class Snapshot(
        val discovered: Set<String>,
        val bound: Set<String>,
        val connected: Set<String>,
        val rejected: Set<String>,
        val bindFailures: Int,
        val deniedAdmission: Int,
        val binderDeaths: Int,
        val unbindEvents: Int,
        /** Number of times [markDiscovered] ran (discovery refresh cycles). */
        val discoveryRefreshCount: Int = 0,
        /** Number of package-removal integration events recorded. */
        val packageRemovals: Int = 0,
        /** Per-category event counts (admission, bind protocol, lifecycle, package removal, …). */
        val categoryCounts: Map<String, Int> = emptyMap(),
        val lastAdmissionDetailByPackage: Map<String, String> = emptyMap(),
    )

    /**
     * Stable, queryable view built from [snapshot] with every [stableCategoryKeys] entry present
     * in [stableCategories] (value 0 if no events yet).
     */
    data class PluginIntegrationState(
        val snapshot: Snapshot,
        val stableCategories: Map<String, Int>,
    ) {
        fun toDebugString(): String = buildString {
            val s = snapshot
            appendLine("PluginIntegrationState")
            appendLine("  discovered: count=${s.discovered.size} pkgs=${s.discovered}")
            appendLine("  bound: count=${s.bound.size} pkgs=${s.bound}")
            appendLine("  connected: count=${s.connected.size} pkgs=${s.connected}")
            appendLine("  rejected: count=${s.rejected.size} pkgs=${s.rejected}")
            appendLine("  bindFailures=${s.bindFailures} deniedAdmission=${s.deniedAdmission}")
            appendLine("  binderDeaths=${s.binderDeaths} unbindEvents=${s.unbindEvents}")
            appendLine("  discoveryRefreshCount=${s.discoveryRefreshCount} packageRemovals=${s.packageRemovals}")
            appendLine("  categories:")
            stableCategories.keys.sorted().forEach { k ->
                appendLine("    $k=${stableCategories[k]}")
            }
        }
    }

    data class HostCallbackState(
        val lastCotReadyEventByPlugin: Map<String, CotReadyEventParcel?> = emptyMap(),
        val lastPluginStatusByPlugin: Map<String, PluginStatusParcel?> = emptyMap(),
        val lastPluginErrorByPlugin: Map<String, PluginErrorParcel?> = emptyMap(),
        val lastSharedBufferByPlugin: Map<String, SharedBufferParcel?> = emptyMap(),
        val lastToastByPlugin: Map<String, ToastRequestParcel?> = emptyMap(),
        val cotReadyEventCountByPlugin: Map<String, Int> = emptyMap(),
        val pluginStatusCountByPlugin: Map<String, Int> = emptyMap(),
        val pluginErrorCountByPlugin: Map<String, Int> = emptyMap(),
        val sharedBufferCountByPlugin: Map<String, Int> = emptyMap(),
        val toastCountByPlugin: Map<String, Int> = emptyMap(),
        val lastSettingsStateByPlugin: Map<String, PluginSettingsState?> = emptyMap(),
        val lastHostSettingsByPlugin: Map<String, HostSettingsParcel?> = emptyMap(),
        val lastSettingsApplyByPlugin: Map<String, SettingsApplyResultParcel?> = emptyMap(),
        val hostSettingsPushCountByPlugin: Map<String, Int> = emptyMap(),
        val settingsSchemaChangeCountByPlugin: Map<String, Int> = emptyMap(),
        val settingsValuesChangeCountByPlugin: Map<String, Int> = emptyMap(),
        val mainUiSchemaChangeCountByPlugin: Map<String, Int> = emptyMap(),
        val mainUiStateChangeCountByPlugin: Map<String, Int> = emptyMap(),
    )
}
