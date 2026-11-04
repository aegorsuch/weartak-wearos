package com.aitech.weartak_civ.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.takserver.TakServerCaTrust
import java.security.cert.X509Certificate
import java.text.DateFormat

@Composable
internal fun ServerCaCandidateReview(
    address: String,
    port: String,
    ca: X509Certificate,
    enabled: Boolean,
    onApprove: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colors.background)
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp)) {
            Text(stringResource(R.string.import_endpoint, address, port))
            Text(ca.subjectX500Principal.name)
            Text(ca.issuerX500Principal.name)
            Text(DateFormat.getDateInstance().format(ca.notAfter))
            Text(TakServerCaTrust.fingerprint(ca).chunked(24).joinToString("\n"))
            Text(stringResource(R.string.import_ca_warning))
            ImportAction(R.string.import_trust_ca, enabled, onApprove)
            ImportAction(android.R.string.cancel, true, onDismiss)
        }
    }
}
