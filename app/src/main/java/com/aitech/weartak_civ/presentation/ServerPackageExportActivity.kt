package com.aitech.weartak_civ.presentation

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.rememberNavController
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.command.ChatP12Validator
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.takserver.TakServerCaTrust
import com.aitech.weartak_civ.integration.plugin.HostServerPackageControl
import com.aitech.weartak_civ.integration.plugin.ServerExportRegistry
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.service.TAKConnectService
import com.weartak.plugin.sdk.serverpackage.*
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class ServerPackageExportActivity : ComponentActivity() {
    private var control: HostServerPackageControl? = null
    private var handle: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val token = intent.getStringExtra("export_handle") ?: return finish()
        val session = ServerExportRegistry.get(token) ?: return finish()
        if (!session.claimExport(token)) return finish()
        control = session
        handle = token
        val repo = (application as WearTakApplication).repo
        setContent { WearTAKCIVTheme {
            ServerPackageExportPage(session, token, RepositoryServerExporter(this, repo), ::finish)
        } }
    }

    override fun onStop() {
        handle?.let { control?.dismissPendingExport(it) }
        super.onStop()
        finish()
    }
}

internal class ExportedServerPackage(val bytes: ByteArray, val authorized: () -> Boolean)
internal interface ServerExporter {
    suspend fun destinations(): List<DataRepository.TakServerItem>
    suspend fun snapshot(server: DataRepository.TakServerItem): CertificateDestination
    suspend fun export(destination: CertificateDestination, identity: Boolean, credentials: Boolean): ExportedServerPackage
}

internal class RepositoryServerExporter(private val context: Context, private val repo: DataRepository) : ServerExporter {
    override suspend fun destinations(): List<DataRepository.TakServerItem> {
        check(!repo.chatEnrollmentBlocked())
        return repo.awaitTakServerList().map { it.copy() }
    }
    override suspend fun snapshot(server: DataRepository.TakServerItem) =
        CertificateDestination(server.copy(), repo.getApprovedServerCa(requireNotNull(server.serverId), server.address, server.port))

    override suspend fun export(destination: CertificateDestination, identity: Boolean, credentials: Boolean): ExportedServerPackage {
        val expected = destination.server
        val bytes = repo.exportReviewedServer(expected, destination.ca) {
            encode(expected, destination.ca, identity, credentials)
        }
        return ExportedServerPackage(bytes) {
            runBlocking { repo.reviewedExportStillAllowed(expected, destination.ca) }
        }
    }

    private fun encode(server: DataRepository.TakServerItem, approvedCa: String?, identity: Boolean, credentials: Boolean): ByteArray {
        var source: ByteArray? = null
        var secret: String? = null
        var bundle: ServerPackage? = null
        try {
            if (identity || approvedCa == null) {
                if (server.isP12Cert) {
                    source = Base64.getDecoder().decode(server.p12Cert)
                    secret = server.p12CertPassword
                } else {
                    try {
                        val cached = TAKConnectService.exportCachedClientIdentity(context, server)
                        source = cached.first
                        secret = cached.second
                    } catch (error: java.io.FileNotFoundException) {
                        if (identity) throw error
                    } catch (error: IllegalArgumentException) {
                        if (identity) throw error
                    } catch (error: IllegalStateException) {
                        if (identity) throw error
                    }
                }
            }
            val cas = mutableListOf<X509Certificate>()
            if (approvedCa != null) cas += TakServerCaTrust.decodeAll(approvedCa)
            source?.let { payload ->
                val password = requireNotNull(secret)
                ChatP12Validator.validateBytes(payload, password, 32700, maxPasswordChars = 256)
                val chars = password.toCharArray()
                try {
                    val store = KeyStore.getInstance("PKCS12")
                    ByteArrayInputStream(payload).use { store.load(it, chars) }
                    val alias = store.aliases().toList().single(store::isKeyEntry)
                    val key = store.getKey(alias, chars) as? PrivateKey
                        ?: error("No exportable client key")
                    val encodedKey = key.encoded ?: error("Client key is not exportable")
                    encodedKey.fill(0)
                    if (approvedCa == null) {
                        val platform = TakServerCaTrust.platformTrustManager().acceptedIssuers.toSet()
                        store.getCertificateChain(alias).filterIsInstance<X509Certificate>()
                            .filter { it.basicConstraints >= 0 && it !in platform }
                            .forEach { cas += TakServerCaTrust.parse(it.encoded) }
                    }
                } finally { chars.fill('\u0000') }
            }
            if (identity) check(source != null) { "No exportable client identity" }
            if (credentials) check(server.username.isNotEmpty() && server.password.isNotEmpty()) { "No shareable credentials" }
            bundle = ServerPackage(
                server.name.trim(), okhttp3.HttpUrl.Builder().scheme("https").host(server.address).build().host,
                server.port, if (cas.isEmpty()) ServerPackageTrustMode.PUBLIC_CA else ServerPackageTrustMode.PRIVATE_CA,
                if (identity) ServerPackageIdentityMode.SHARED_IDENTITY else ServerPackageIdentityMode.RECEIVER_ENROLLMENT,
                cas.distinctBy(TakServerCaTrust::fingerprint).map { it.encoded },
                clientPkcs12 = if (identity) source?.copyOf() else null,
                clientPkcs12Password = if (identity) requireNotNull(secret).toByteArray(Charsets.UTF_8) else null,
                username = if (credentials) server.username else null,
                password = if (credentials) server.password.toByteArray(Charsets.UTF_8) else null,
            )
            return ServerPackageCodec.encode(bundle)
        } finally { source?.fill(0); bundle?.wipe() }
    }
}

@Composable
internal fun ServerPackageExportPage(control: HostServerPackageControl, handle: String,
    backend: ServerExporter, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val flow = remember { requireNotNull(control.observeExport(handle)) }
    val status by flow.collectAsState()
    var servers by remember { mutableStateOf<List<DataRepository.TakServerItem>>(emptyList()) }
    var selected by remember { mutableStateOf<CertificateDestination?>(null) }
    var identity by remember(selected) { mutableStateOf(false) }
    var credentials by remember(selected) { mutableStateOf(false) }
    var confirmed by remember(selected, identity, credentials) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        try {
            servers = backend.destinations()
            if (servers.isEmpty()) control.rejectExport(handle, ServerPackageExportReason.NO_ELIGIBLE_SERVER)
        }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { control.rejectExport(handle, ServerPackageExportReason.MANAGED_RESTRICTED) }
    }
    WearTAKPageWithBackArrow(stringResource(R.string.export_title), {
        if (status.state != ServerPackageExportState.PENDING) {
            item { Text(stringResource(if (status.state in listOf(ServerPackageExportState.READY, ServerPackageExportState.TAKEN))
                R.string.export_ready else problem ?: R.string.export_rejected)) }
            item { ImportAction(R.string.import_done, true, onClose) }
        } else if (selected == null) {
            items(servers.size) { index ->
                val server = servers[index]
                ImportActionText("${server.name}\n${server.address}:${server.port}", !busy) {
                    busy = true
                    scope.launch {
                        try { selected = backend.snapshot(server) }
                        catch (error: CancellationException) { throw error }
                        catch (_: Exception) { control.rejectExport(handle, ServerPackageExportReason.FAILED) }
                        finally { busy = false }
                    }
                }
            }
            if (servers.isEmpty()) item { Text(stringResource(R.string.export_no_servers)) }
        } else {
            val choice = requireNotNull(selected)
            item { Text(stringResource(R.string.import_endpoint, choice.server.address, choice.server.port.toString())) }
            item { ExportOptIn(R.string.export_identity, identity, !busy) { identity = it } }
            item { ExportOptIn(R.string.export_credentials, credentials, !busy) { credentials = it } }
            if (identity || credentials) {
                item { Text(stringResource(R.string.export_sensitive_warning)) }
                item { ExportOptIn(R.string.export_confirm_sensitive, confirmed, !busy) { confirmed = it } }
            }
            item {
                ImportAction(R.string.export_prepare, !busy && (!(identity || credentials) || confirmed)) {
                    busy = true
                    scope.launch {
                        try {
                            val exported = withContext(Dispatchers.IO) { backend.export(choice, identity, credentials) }
                            if (!control.approveExport(handle, exported.bytes, exported.authorized)) {
                                control.rejectExport(handle, ServerPackageExportReason.FAILED)
                            }
                        } catch (error: CancellationException) { throw error
                        } catch (_: DataRepository.ManagedServerExportException) {
                            problem = R.string.import_policy_error
                            control.rejectExport(handle, ServerPackageExportReason.MANAGED_RESTRICTED)
                        } catch (_: DataRepository.StaleCertificateDestinationException) {
                            problem = R.string.import_stale
                            control.rejectExport(handle, ServerPackageExportReason.FAILED)
                        } catch (error: ServerPackageException) {
                            problem = R.string.export_too_large
                            control.rejectExport(handle, if (error.error == ServerPackageError.TOO_LARGE)
                                ServerPackageExportReason.TOO_LARGE else ServerPackageExportReason.FAILED)
                        } catch (_: Exception) {
                            Log.w("ServerExport", "Selected server cannot be exported")
                            problem = R.string.export_unsupported
                            control.rejectExport(handle, ServerPackageExportReason.NONEXPORTABLE_KEY)
                        } finally { busy = false }
                    }
                }
            }
            item { ImportAction(R.string.export_cancel, !busy) {
                control.rejectExport(handle, ServerPackageExportReason.USER_DECLINED)
            } }
        }
    }, rememberNavController(), exitActivity = true, exitFunction = {
        control.rejectExport(handle, ServerPackageExportReason.USER_DECLINED)
        onClose()
    })
}

@Composable
internal fun ExportOptIn(label: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    ToggleChip(checked, onChange, label = { Text(stringResource(label)) },
        toggleControl = { Icon(ToggleChipDefaults.checkboxIcon(checked), null) },
        enabled = enabled, modifier = Modifier.fillMaxWidth())
}
