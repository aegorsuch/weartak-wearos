package com.aitech.weartak_civ.presentation

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.takserver.TakServerCaTrust
import com.aitech.weartak_civ.data.takserver.TakConnectionFailure
import java.security.cert.X509Certificate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ServerCaTrustSettings(
    address: String,
    port: String,
    certificate: String?,
    enabled: Boolean = true,
    failure: TakConnectionFailure? = null,
    importOnOpen: Boolean = false,
    onChange: (String?) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(importOnOpen) }
    var reviewRequest by rememberSaveable { mutableStateOf(if (importOnOpen) 1 else 0) }
    Column(Modifier.fillMaxWidth()) {
        if (failure?.untrustedServerCa == true) {
            Text(stringResource(R.string.ca_trust_untrusted))
            ServerCaImportAction(enabled) {
                expanded = true
                reviewRequest++
            }
        }
        Chip(
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(if (expanded) R.string.ca_trust_advanced_hide else R.string.ca_trust_advanced)) },
            onClick = { expanded = !expanded },
        )
        if (expanded) {
            ServerCaTrustEditor(address, port, certificate, enabled, reviewRequest,
                onReviewHandled = { reviewRequest = 0 }, onChange = onChange)
        }
    }
}

@Composable
internal fun ServerCaImportAction(enabled: Boolean = true, onClick: () -> Unit) {
    Chip(
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(stringResource(R.string.ca_trust_import)) },
        onClick = onClick,
    )
}

@Composable
internal fun ServerCaTrustEditor(
    address: String,
    port: String,
    certificate: String?,
    enabled: Boolean = true,
    reviewRequest: Int = 0,
    onReviewHandled: () -> Unit = {},
    onChange: (String?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var candidate by remember(address, port) { mutableStateOf<X509Certificate?>(null) }
    var error by remember(address, port) { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    // Discard results if the user edits the endpoint while the file is being read.
    val endpoint = remember(address, port) { Any() }
    val currentEndpoint = androidx.compose.runtime.rememberUpdatedState(endpoint)
    val canReview = enabled && !loading && address.isNotBlank() && port.toIntOrNull() in 1..65535
    val review: () -> Unit = {
        loading = true
        error = false
        candidate = null
        scope.launch {
            try {
                val read = withContext(Dispatchers.IO) {
                    TakServerCaTrust.readCandidate(requireNotNull(context.getExternalFilesDir(null)))
                }
                if (currentEndpoint.value === endpoint) candidate = read
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("ServerCaTrust", "Unable to read CA import", e)
                if (currentEndpoint.value === endpoint) error = true
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(reviewRequest, canReview) {
        if (reviewRequest > 0 && canReview) {
            onReviewHandled()
            review()
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(stringResource(if (certificate == null) R.string.ca_trust_default else R.string.ca_trust_selected))
        Text(stringResource(R.string.ca_trust_stage_hint))
        Chip(
            modifier = Modifier.fillMaxWidth(),
            enabled = canReview,
            label = { Text(stringResource(if (loading) R.string.ca_trust_loading else R.string.ca_trust_review)) },
            onClick = review,
        )
        if (error) Text(stringResource(R.string.ca_trust_import_error))
        candidate?.let { ca ->
            Dialog(
                onDismissRequest = { candidate = null },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colors.background)
                        .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.ca_trust_endpoint, address, port))
                    Text(ca.subjectX500Principal.name)
                    Text(stringResource(R.string.ca_trust_expires,
                        java.text.DateFormat.getDateInstance().format(ca.notAfter)))
                    Text(stringResource(R.string.ca_trust_fingerprint))
                    Text(TakServerCaTrust.fingerprint(ca))
                    Text(stringResource(R.string.ca_trust_warning))
                    Chip(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled,
                        label = { Text(stringResource(R.string.ca_trust_approve)) },
                        onClick = {
                            onChange(TakServerCaTrust.encode(ca))
                            candidate = null
                        },
                    )
                    Chip(
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(android.R.string.cancel)) },
                        onClick = { candidate = null },
                    )
                }
            }
        }
        if (certificate != null) {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                label = { Text(stringResource(R.string.ca_trust_remove)) },
                onClick = {
                    onChange(null)
                    candidate = null
                },
            )
        }
    }
}
