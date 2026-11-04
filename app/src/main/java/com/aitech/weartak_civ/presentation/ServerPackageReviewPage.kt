package com.aitech.weartak_civ.presentation

import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.navigation.compose.rememberNavController
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.takserver.Pkcs12CertificateImport
import com.aitech.weartak_civ.data.takserver.TakServerCaTrust
import com.aitech.weartak_civ.integration.plugin.HostPkcs12ImportControl
import com.weartak.plugin.sdk.nearbyimport.Pkcs12ImportState
import com.weartak.plugin.sdk.serverpackage.*
import java.security.cert.X509Certificate
import java.text.DateFormat
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Only server fields are imported, never device/CoT identity. */
internal fun packageServer(bundle: ServerPackage, destination: CertificateDestination?,
    identity: Pkcs12CertificateImport.Opened.Client?, username: String, password: String): DataRepository.TakServerItem {
    val enrollment = bundle.identityMode == ServerPackageIdentityMode.RECEIVER_ENROLLMENT
    require(!enrollment || (username.isNotBlank() && password.isNotEmpty()))
    require(username.length <= 128 && password.length <= 256)
    require((bundle.identityMode == ServerPackageIdentityMode.SHARED_IDENTITY) == (identity != null))
    return DataRepository.TakServerItem(
        isEnabled = bundle.identityMode != ServerPackageIdentityMode.ADMIN_PKCS12_SEPARATE,
        name = bundle.name, address = bundle.host, port = bundle.port,
        isP12Cert = identity != null, username = username, password = password,
        p12Cert = identity?.identity?.base64.orEmpty(), p12CertPassword = identity?.identity?.password.orEmpty(),
        serverId = destination?.server?.serverId,
        identityEpoch = if (enrollment) UUID.randomUUID().toString() else null,
        strictServerTrust = true,
    )
}

@Composable
internal fun ServerPackageReviewPage(control: HostPkcs12ImportControl, handle: String,
    backend: CertificateReviewBackend, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val flow = remember(control, handle) { requireNotNull(control.observe(handle)) }
    val status by flow.collectAsState()
    val terminal = HostPkcs12ImportControl.terminal(status.state)
    var bundle by remember { mutableStateOf<ServerPackage?>(null) }
    var client by remember { mutableStateOf<Pkcs12CertificateImport.Opened.Client?>(null) }
    var candidates by remember { mutableStateOf<List<X509Certificate>>(emptyList()) }
    var approved by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var reviewing by remember { mutableStateOf<Int?>(null) }
    var destination by remember { mutableStateOf<CertificateDestination?>(null) }
    var replacement by remember { mutableStateOf(false) }
    var sharedLogin by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var allowed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    var problem by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        var decoded: ServerPackage? = null
        try {
            allowed = backend.allowed()
            check(allowed)
            val loaded = withContext(Dispatchers.Default) {
                val next = control.openPackage(handle)
                decoded = next
                val identity = next.clientPkcs12?.let { bytes ->
                    Pkcs12CertificateImport.open(bytes, requireNotNull(next.clientPkcs12Password).toString(Charsets.UTF_8))
                        as? Pkcs12CertificateImport.Opened.Client ?: error("Client identity is required")
                }
                Triple(next, identity, next.caCertificates.map(TakServerCaTrust::parse))
            }
            val existing = backend.destinations().singleOrNull {
                okhttp3.HttpUrl.Builder().scheme("https").host(it.address).build().host == loaded.first.host &&
                    it.port == loaded.first.port
            }
            destination = existing?.let { backend.snapshot(it) }
            if (control.isLive(handle)) {
                bundle = loaded.first; client = loaded.second; candidates = loaded.third
                decoded = null
            }
        } catch (error: CancellationException) { throw error
        } catch (_: Exception) {
            Log.w("ServerPackageReview", "Server package review refused")
            problem = R.string.import_invalid
            control.reject(handle)
        } finally { decoded?.wipe(); busy = false }
    }
    LaunchedEffect(terminal) {
        if (terminal) {
            bundle?.wipe(); bundle = null; client = null; candidates = emptyList()
            username = ""; password = ""; reviewing = null
        }
    }
    DisposableEffect(Unit) {
        onDispose { bundle?.wipe(); bundle = null; password = ""; control.dismiss(handle) }
    }
    val current = bundle
    val enabled = allowed && !busy && !terminal
    val enrollment = current?.identityMode == ServerPackageIdentityMode.RECEIVER_ENROLLMENT
    WearTAKPageWithBackArrow(stringResource(R.string.package_review_title), {
        if (terminal) {
            item { Text(stringResource(when (status.state) {
                Pkcs12ImportState.APPLIED -> problem ?: R.string.import_applied
                Pkcs12ImportState.CANCELLED -> R.string.import_cancelled
                Pkcs12ImportState.EXPIRED -> R.string.import_expired
                else -> problem ?: R.string.import_rejected
            })) }
            item { ImportAction(R.string.import_done, true, onClose) }
        } else if (current != null) {
            item { Text(current.name) }
            item { Text(stringResource(R.string.import_endpoint, current.host, current.port.toString())) }
            item { Text(stringResource(when (current.identityMode) {
                ServerPackageIdentityMode.RECEIVER_ENROLLMENT -> R.string.package_fresh_identity
                ServerPackageIdentityMode.ADMIN_PKCS12_SEPARATE -> R.string.package_separate_identity
                else -> R.string.package_shared_identity
            })) }
            client?.let { identity ->
                item { Text(identity.certificate.subjectX500Principal.name) }
                item { Text(identity.certificate.issuerX500Principal.name) }
                item { Text(DateFormat.getDateInstance().format(identity.certificate.notAfter)) }
                item { Text(TakServerCaTrust.fingerprint(identity.certificate).chunked(24).joinToString("\n")) }
            }
            if (current.trustMode == ServerPackageTrustMode.PUBLIC_CA) {
                item { Text(stringResource(R.string.package_public_ca)) }
            } else {
                items(candidates.size) { index ->
                    ImportActionText(candidates[index].subjectX500Principal.name +
                        if (index in approved) "\n" + stringResource(R.string.import_ca_approved) else "", enabled) {
                        reviewing = index
                    }
                }
            }
            if (current.username != null) item {
                ExportOptIn(R.string.package_use_shared_login, sharedLogin, enabled) { sharedLogin = it }
            }
            if (enrollment && !sharedLogin) {
                item { username = WearTAKOutlinedTextField(username, stringResource(R.string.username), saveState = false).text }
                item { password = WearTAKOutlinedTextField(password, stringResource(R.string.import_login_password),
                    KeyboardType.Password, saveState = false).text }
            }
            if (destination != null) item {
                ExportOptIn(R.string.package_replace, replacement, enabled) { replacement = it }
            }
            item {
                ImportAction(R.string.import_apply, enabled && (destination == null || replacement) &&
                    (!enrollment || sharedLogin || (username.isNotBlank() && password.isNotEmpty())) &&
                    (candidates.isEmpty() || approved.isNotEmpty())) {
                    busy = true
                    val identity = client
                    val selected = destination
                    val loginName = if (sharedLogin) current.username.orEmpty() else username
                    val secret = if (sharedLogin) current.password?.toString(Charsets.UTF_8).orEmpty() else password
                    val ca = approved.sorted().map(candidates::get).takeIf { it.isNotEmpty() }?.let(TakServerCaTrust::encodeAll)
                    scope.launch {
                        try {
                            val proposed = packageServer(current, selected, identity, loginName, secret)
                            val saved = backend.apply(selected, proposed, ca, { control.isLive(handle) },
                                { control.completeApplied(handle) })
                            if (!saved) { problem = R.string.import_apply_error; control.reject(handle) }
                        } catch (error: CancellationException) { throw error
                        } catch (_: DataRepository.StaleCertificateDestinationException) {
                            problem = R.string.import_stale; control.reject(handle)
                        } catch (_: SavedServerStartException) {
                            problem = R.string.import_connection_error
                        } catch (_: DataRepository.CertificateRecoveryException) {
                            problem = R.string.import_recovery_error; control.reject(handle)
                        } catch (_: Exception) {
                            Log.e("ServerPackageReview", "Reviewed server apply refused")
                            problem = R.string.import_apply_error; control.reject(handle)
                        } finally { current.wipe(); password = ""; username = ""; busy = false }
                    }
                }
            }
            item { ImportAction(R.string.import_reject, !busy) { control.dismiss(handle) } }
        }
        if (busy) item { Text(stringResource(R.string.import_working)) }
    }, rememberNavController(), exitActivity = true, exitFunction = { control.dismiss(handle); onClose() })
    val index = reviewing
    if (index != null && current != null) {
        ServerCaCandidateReview(current.host, current.port.toString(), candidates[index], enabled,
            onApprove = { approved = approved + index; reviewing = null }, onDismiss = { reviewing = null })
    }
}
