package com.aitech.weartak_civ.presentation

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.navigation.compose.rememberNavController
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import androidx.wear.compose.material.Icon
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.takserver.Pkcs12CertificateImport
import com.aitech.weartak_civ.data.takserver.TakServerCaTrust
import com.aitech.weartak_civ.integration.plugin.HostPkcs12ImportControl
import com.aitech.weartak_civ.integration.plugin.ReviewRegistry
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.service.TAKConnectService
import com.weartak.plugin.sdk.nearbyimport.Pkcs12ImportState as State
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.cert.X509Certificate
import java.text.DateFormat

class CertificateImportActivity : ComponentActivity() {
    private var control: HostPkcs12ImportControl? = null
    private var handle: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val token = intent.getStringExtra("review_handle") ?: return finish()
        val session = ReviewRegistry.get(token) ?: return finish()
        if (!session.claimReview(token)) return finish()
        control = session
        handle = token
        val backend = RepositoryCertificateReview((application as WearTakApplication).repo)
        setContent { WearTAKCIVTheme {
            if (session.packageMode) ServerPackageReviewPage(session, token, backend, ::finish)
            else CertificateReviewPage(session, token, backend, ::finish)
        } }
    }

    override fun onStop() {
        handle?.let { control?.dismiss(it) }
        super.onStop()
        finish()
    }
}

internal data class CertificateDestination(val server: DataRepository.TakServerItem, val ca: String?)

internal interface CertificateReviewBackend {
    suspend fun allowed(): Boolean
    suspend fun destinations(): List<DataRepository.TakServerItem>
    suspend fun snapshot(server: DataRepository.TakServerItem): CertificateDestination
    suspend fun apply(
        destination: CertificateDestination?,
        proposed: DataRepository.TakServerItem,
        ca: String?,
        authorized: () -> Boolean,
        complete: () -> Boolean,
    ): Boolean
}

internal class RepositoryCertificateReview(
    private val repo: DataRepository,
    private val register: suspend (String) -> Unit = { TAKConnectService.getInstance().prepareReviewedServer(it) },
    private val restore: suspend () -> Unit = { TAKConnectService.getInstance().reloadProvisionedTakServers() },
    private val afterCommit: suspend (String) -> Unit = { TAKConnectService.getInstance().registerServer(it) },
) : CertificateReviewBackend {
    override suspend fun allowed() = !repo.chatEnrollmentBlocked()
    override suspend fun destinations() = repo.awaitTakServerList().map { it.copy() }
    override suspend fun snapshot(server: DataRepository.TakServerItem) =
        CertificateDestination(server.copy(), repo.getApprovedServerCa(requireNotNull(server.serverId), server.address, server.port))

    override suspend fun apply(
        destination: CertificateDestination?,
        proposed: DataRepository.TakServerItem,
        ca: String?,
        authorized: () -> Boolean,
        complete: () -> Boolean,
    ): Boolean {
        val gate = com.aitech.weartak_civ.data.takserver.ReviewedServerRuntimeGate
        gate.hold(proposed.address, proposed.port)
        var savedId: String? = null
        var saved = false
        try {
            saved = repo.applyReviewedServer(destination?.server, proposed, ca, destination?.ca, checkCaSnapshot = true,
                stillAuthorized = authorized, complete = complete,
                apply = { id -> register(id); savedId = id }, rollback = restore)
        } finally {
            gate.release(proposed.address, proposed.port)
            if (!saved) withContext(NonCancellable) {
                try { restore()
                } catch (error: Exception) { throw DataRepository.CertificateRecoveryException(error) }
            }
        }
        if (!saved) return false
        try {
            afterCommit(requireNotNull(savedId))
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) {
            Log.e("CertificateReview", "Saved server could not start its connection")
            throw SavedServerStartException(error)
        }
        return saved
    }
}

internal class SavedServerStartException(cause: Throwable) : IllegalStateException("Server connection could not start", cause)

@Composable
internal fun CertificateReviewPage(
    control: HostPkcs12ImportControl,
    handle: String,
    backend: CertificateReviewBackend,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val flow = remember(control, handle) { requireNotNull(control.observe(handle)) }
    val status by flow.collectAsState()
    var opened by remember { mutableStateOf<Pkcs12CertificateImport.Opened?>(null) }
    var password by remember { mutableStateOf("") }
    var passwordEpoch by remember { mutableIntStateOf(0) }
    var servers by remember { mutableStateOf<List<DataRepository.TakServerItem>>(emptyList()) }
    var destination by remember { mutableStateOf<CertificateDestination?>(null) }
    var newServer by remember { mutableStateOf(false) }
    var newConfirmed by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8089") }
    var approvedCa by remember(destination, address, port) { mutableStateOf<String?>(null) }
    var reviewing by remember(destination, address, port) { mutableStateOf<X509Certificate?>(null) }
    var replacementApproved by remember(destination, newServer) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Int?>(null) }
    var allowed by remember { mutableStateOf(false) }
    val terminal = HostPkcs12ImportControl.terminal(status.state)

    LaunchedEffect(Unit) {
        try {
            allowed = backend.allowed()
            servers = backend.destinations()
            if (!allowed) control.reject(handle)
        } catch (error: CancellationException) { throw error
        } catch (_: Exception) {
            Log.e("CertificateReview", "Unable to load destination policy")
            problem = R.string.import_policy_error
            control.reject(handle)
        }
    }
    LaunchedEffect(terminal) {
        if (terminal) {
            password = ""
            passwordEpoch++
            opened = null
            approvedCa = null
            reviewing = null
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            password = ""
            opened = null
            control.dismiss(handle)
        }
    }

    val client = opened as? Pkcs12CertificateImport.Opened.Client
    val trust = opened as? Pkcs12CertificateImport.Opened.Trust
    val host = destination?.server?.address ?: address.trim()
    val selectedPort = destination?.server?.port ?: port.toIntOrNull()
    val newEndpointValid = newServer && name.isNotBlank() && name.length <= 128 &&
        host.isNotBlank() && selectedPort in 1..65535
    val endpointValid = destination != null || (newConfirmed && newEndpointValid)
    val enabled = allowed && !busy && !terminal
    val identityApproved = client == null || destination == null || replacementApproved

    WearTAKPageWithBackArrow(stringResource(R.string.import_title), {
        if (terminal) {
            item {
                Text(stringResource(when (status.state) {
                    State.APPLIED -> problem ?: R.string.import_applied
                    State.CANCELLED -> R.string.import_cancelled
                    State.EXPIRED -> R.string.import_expired
                    else -> problem ?: R.string.import_rejected
                }))
            }
            item { ImportAction(R.string.import_done, true, onClose) }
        } else if (opened == null) {
            item {
                key(passwordEpoch) {
                    password = WearTAKOutlinedTextField(password, stringResource(R.string.import_password),
                        KeyboardType.Password, saveState = false).text
                }
            }
            item {
                ImportAction(R.string.import_open, enabled && password.length in 1..256) {
                    busy = true
                    val secret = password
                    password = ""
                    passwordEpoch++
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.Default) { control.open(handle, secret) }
                            if (control.isLive(handle)) opened = result
                        } catch (error: CancellationException) { throw error
                        } catch (_: Exception) {
                            Log.w("CertificateReview", "PKCS12 rejected")
                            problem = R.string.import_invalid
                            control.reject(handle)
                        } finally { busy = false }
                    }
                }
            }
        } else {
            if (!endpointValid) {
                item { Text(stringResource(R.string.import_destination)) }
                items(servers.size) { index ->
                    val server = servers[index]
                    ImportActionText("${server.name}\n${server.address}:${server.port}", enabled) {
                        busy = true
                        scope.launch {
                            try {
                                destination = backend.snapshot(server)
                                newServer = false
                            } catch (error: CancellationException) { throw error
                            } catch (_: Exception) {
                                problem = R.string.import_policy_error
                                control.reject(handle)
                            } finally { busy = false }
                        }
                    }
                }
                item {
                    ImportAction(R.string.new_server, enabled) {
                        destination = null
                        newServer = true
                    }
                }
                if (newServer) {
                    item { name = WearTAKOutlinedTextField(name, stringResource(R.string.connection_name), saveState = false).text }
                    item { address = WearTAKOutlinedTextField(address, stringResource(R.string.address_url), saveState = false).text }
                    item { port = WearTAKOutlinedTextField(port, stringResource(R.string.port), KeyboardType.Number, saveState = false).text }
                    item { ImportAction(R.string.import_choose, enabled && newEndpointValid) { newConfirmed = true } }
                }
            } else {
                item { Text(stringResource(R.string.import_endpoint, host, selectedPort.toString())) }
                item {
                    ImportAction(R.string.import_change_destination, enabled) {
                        destination = null
                        newServer = false
                        newConfirmed = false
                        approvedCa = null
                        replacementApproved = false
                    }
                }
                if (client != null) {
                    item { Text(stringResource(R.string.import_client_identity)) }
                    item { Text(client.certificate.subjectX500Principal.name) }
                    item { Text(client.certificate.issuerX500Principal.name) }
                    item { Text(DateFormat.getDateInstance().format(client.certificate.notAfter)) }
                    item { Text(TakServerCaTrust.fingerprint(client.certificate).chunked(24).joinToString("\n")) }
                    if (destination != null) item {
                        ToggleChip(checked = replacementApproved, enabled = enabled,
                            onCheckedChange = { replacementApproved = it },
                            label = { Text(stringResource(R.string.import_replace_identity)) },
                            toggleControl = { Icon(ToggleChipDefaults.checkboxIcon(replacementApproved), null) },
                            modifier = Modifier.fillMaxWidth())
                    }
                }
                if (trust != null) {
                    item { Text(stringResource(R.string.import_select_ca)) }
                    items(trust.certificates.size) { index ->
                        val ca = trust.certificates[index]
                        ImportActionText(ca.subjectX500Principal.name, enabled) { reviewing = ca }
                    }
                    if (approvedCa != null) item { Text(stringResource(R.string.import_ca_approved)) }
                }
                item {
                    ImportAction(R.string.import_apply,
                        enabled && identityApproved && (client != null || approvedCa != null)) {
                        busy = true
                        val selected = destination
                        val identity = client?.identity
                        val ca = approvedCa ?: destination?.ca
                        val proposed = selected?.server ?: DataRepository.TakServerItem(
                            true, name.trim(), host, requireNotNull(selectedPort), false, "", "", "", "")
                        val updated = if (identity == null) proposed else proposed.copy(
                            isP12Cert = true, p12Cert = identity.base64, p12CertPassword = identity.password,
                            strictServerTrust = true)
                        scope.launch {
                            try {
                                val saved = backend.apply(selected, updated, ca,
                                    authorized = { control.isLive(handle) },
                                    complete = { control.completeApplied(handle) })
                                if (!saved) { problem = R.string.import_apply_error; control.reject(handle) }
                            } catch (error: CancellationException) { throw error
                            } catch (_: DataRepository.StaleCertificateDestinationException) {
                                problem = R.string.import_stale
                                control.reject(handle)
                            } catch (_: SavedServerStartException) {
                                problem = R.string.import_connection_error
                            } catch (_: DataRepository.CertificateRecoveryException) {
                                problem = R.string.import_recovery_error
                                control.reject(handle)
                            } catch (_: Exception) {
                                Log.e("CertificateReview", "Certificate apply refused")
                                problem = R.string.import_apply_error
                                control.reject(handle)
                            } finally { busy = false; opened = null }
                        }
                    }
                }
            }
        }
        if (busy) item { Text(stringResource(R.string.import_working)) }
        if (!terminal) item {
            ImportAction(R.string.import_reject, !busy) { control.dismiss(handle) }
        }
    }, rememberNavController(), exitActivity = true, exitFunction = {
        control.dismiss(handle)
        onClose()
    })
    reviewing?.let { ca ->
        ServerCaCandidateReview(host, selectedPort.toString(), ca, enabled,
            onApprove = { approvedCa = TakServerCaTrust.encode(ca); reviewing = null },
            onDismiss = { reviewing = null })
    }
}

@Composable
internal fun ImportAction(resource: Int, enabled: Boolean, action: () -> Unit) =
    ImportActionText(stringResource(resource), enabled, action)

@Composable
internal fun ImportActionText(label: String, enabled: Boolean, action: () -> Unit) {
    Chip(modifier = Modifier.fillMaxWidth(), enabled = enabled, label = { Text(label) }, onClick = action)
}
