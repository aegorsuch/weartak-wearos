package com.aitech.weartak_civ.service

import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.DittoPdlSource
import com.aitech.weartak_civ.integration.plugin.PluginSettingsCoordinator
import com.aitech.weartak_civ.manager.MulticastWifiRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal enum class ConnectivityPath(val code: Long) {
    TAK(0), DITTO(1), SITX(2), MULTICAST(3), COMPANION(4), PLUGINS(5)
}

/** Numeric state only: never retain group names, plugin IDs, addresses or PDL contents. */
internal class ConnectivityPathDiagnostics(
    scope: CoroutineScope,
    enabled: StateFlow<Boolean>,
    repo: DataRepository,
    plugins: PluginSettingsCoordinator,
    private val emit: (Map<DiagnosticMetric, Long>) -> Unit,
    private val snapshotIntervalMs: Long = 300_000L,
    private val powerSnapshot: () -> Unit = {}
) {
    init {
        scope.launch {
            enabled.collectLatest { recording ->
                if (!recording) return@collectLatest
                coroutineScope {
                    fun watch(path: ConnectivityPath, vararg fields: Pair<DiagnosticMetric, Flow<Long>>) = launch {
                        val boundary = repo.chargingState.map { if (it) 1L else 0L }
                        val all = fields.toList() + (DiagnosticMetric.CHARGING to boundary)
                        val latest = MutableStateFlow<Map<DiagnosticMetric, Long>?>(null)
                        launch {
                            combine(all.map { it.second }) { values ->
                                all.mapIndexed { i, field -> field.first to values[i] }.toMap() +
                                    (DiagnosticMetric.KIND to path.code)
                            }.distinctUntilChanged().collect {
                                latest.value = it
                                emit(it)
                            }
                        }
                        while (isActive) {
                            delay(snapshotIntervalMs)
                            latest.value?.let(emit)
                            if (path == ConnectivityPath.TAK) powerSnapshot()
                        }
                    }
                    watch(ConnectivityPath.TAK,
                        DiagnosticMetric.ENABLED to repo.getEnableTakEndpoint.numeric(),
                        DiagnosticMetric.CONFIGURED_COUNT to repo.getTakServerListReady.map { it.size.toLong() },
                        DiagnosticMetric.CONNECTED_COUNT to repo.outboundReadyRoutes.map { routes ->
                            routes.values.count { it.endpoint == DataRepository.Endpoint.TAK_SERVER }.toLong()
                        })
                    watch(ConnectivityPath.SITX,
                        DiagnosticMetric.ENABLED to repo.getEnableSitxService.numeric(),
                        DiagnosticMetric.ACTIVE to repo.sitxStatus.map { if (it == DataRepository.SitxServerStatus.CONNECTED) 1L else 0L },
                        DiagnosticMetric.GROUP_SELECTED to repo.getSelectedSitxGroup.map { if (it != null) 1L else 0L })
                    watch(ConnectivityPath.MULTICAST,
                        DiagnosticMetric.ENABLED to repo.getEnableMulticast.numeric(),
                        DiagnosticMetric.ACTIVE to repo.multicastPublisherRunning.numeric(),
                        DiagnosticMetric.REQUESTED to MulticastWifiRequest.runtimeState.map { if (it.requested) 1L else 0L },
                        DiagnosticMetric.LOCK_HELD to MulticastWifiRequest.runtimeState.map { 0L },
                        DiagnosticMetric.IDLE_RELEASED to MulticastWifiRequest.runtimeState.map { if (it.idleReleased) 1L else 0L })
                    watch(ConnectivityPath.COMPANION,
                        DiagnosticMetric.ENABLED to repo.getEnableCompanionAppService.numeric(),
                        DiagnosticMetric.ACTIVE to repo.isCompanionConnected.numeric())
                    watch(ConnectivityPath.PLUGINS,
                        DiagnosticMetric.CONFIGURED_COUNT to plugins.connectivityToggles.map { it.size.toLong() },
                        DiagnosticMetric.ENABLED to plugins.connectivityToggles.map { values -> values.values.count { it.enabled }.toLong() },
                        DiagnosticMetric.ACTIVE to plugins.outboundCotReadyRoutes.map { it.size.toLong() })
                }
            }
        }
    }

    private fun Flow<Boolean>.numeric(): Flow<Long> = map { if (it) 1L else 0L }
}
