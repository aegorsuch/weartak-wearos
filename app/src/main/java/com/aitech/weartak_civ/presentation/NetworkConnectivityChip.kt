package com.aitech.weartak_civ.presentation

import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChipDefaults
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import com.aitech.weartak_civ.R
import androidx.navigation.NavController

@Composable
internal fun MulticastNetworkPreference(
    enabled: Boolean,
    phoneManagedSettings: Boolean,
    navController: NavController,
    showPhoneManagedNotice: () -> Unit
) {
    val title = stringResource(R.string.tak_sa_multicast)
    if (phoneManagedSettings) {
        WearTAKLockedTitleChip(title, stringResource(R.string.network_control_bonded), showPhoneManagedNotice)
    } else {
        WearTAKTitleChipWithState(
            route = "tak_sa_multicast", title = title, state = enabled, navController = navController,
            formatState = { stringResource(if (enabled) R.string.enabled else R.string.disabled) }
        )
    }
}

@Composable
internal fun MulticastNetworkToggle(
    enabled: Boolean,
    phoneManagedSettings: Boolean,
    networkPreferencesLocked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    WearTAKToggleChip(
        checkedLive = enabled,
        onCheckedChange = onCheckedChange,
        title = stringResource(R.string.tak_sa_multicast),
        description = networkControlSubtitle(stringResource(R.string.service_on_off), phoneManagedSettings),
        enabled = !networkPreferencesLocked && !phoneManagedSettings
    )
}

internal fun ScalingLazyListScope.networkPreferencesChecklist(
    enabled: Boolean,
    rows: List<NetworkConnectivityRow>,
    content: @Composable (NetworkConnectivityRow) -> Unit
) {
    if (!enabled) return
    items(rows, key = { it.key }) { content(it) }
    item { HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp)) }
}

@Composable
internal fun networkControlSubtitle(subtitle: String, phoneManagedSettings: Boolean): String =
    if (phoneManagedSettings) stringResource(R.string.network_control_bonded) else subtitle

@Composable
internal fun NetworkConnectivityChip(
    row: NetworkConnectivityRow,
    networkPreferencesLocked: Boolean,
    phoneManagedSettings: Boolean,
    navigate: (String) -> Unit,
    showPhoneManagedNotice: () -> Unit,
    showNetworkLockedNotice: () -> Unit,
    appIcon: @Composable () -> Unit = { NetworkConnectivityIcon(row) }
) {
    val bondLocked = phoneManagedSettings && row.key != "companion"
    val toggleEnabled = !networkPreferencesLocked && !bondLocked
    val subtitle = networkControlSubtitle(row.subtitle, bondLocked)
    val statusDescription = networkControlSubtitle(row.statusDescription, bondLocked)
    val route = row.settingsRoute
    if (route == null) {
        WearTAKToggleChip(
            checkedLive = row.checked,
            onCheckedChange = row.toggle,
            title = row.title,
            description = subtitle,
            statusDescription = statusDescription,
            appIcon = appIcon,
            enabled = toggleEnabled
        )
        return
    }

    val openSettings = stringResource(R.string.open_transport_settings, row.title)
    val state = stringResource(if (row.checked) R.string.toggle_state_on else R.string.toggle_state_off)
    val colors = ToggleChipDefaults.splitToggleChipColors()
    // Wear Compose 1.4 SplitToggleChip has one enabled flag for both actions.
    // Keep the same split pattern, but disable only the switch when settings are locked.
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
            .clip(MaterialTheme.shapes.large)
            .background(colors.backgroundColor(enabled = true).value),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = openSettings) {
                    when {
                        row.key == "tak" && phoneManagedSettings -> showPhoneManagedNotice()
                        row.key == "tak" && networkPreferencesLocked -> showNetworkLockedNotice()
                        else -> navigate(route)
                    }
                }
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            appIcon()
            Column(Modifier.weight(1f)) {
                Text(row.title, style = MaterialTheme.typography.button,
                    color = colors.contentColor(enabled = true).value,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.caption2,
                        color = colors.secondaryContentColor(enabled = true).value,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Box(
            modifier = Modifier.width(52.dp).fillMaxHeight().heightIn(min = 48.dp)
                .background(colors.splitBackgroundOverlay(toggleEnabled, row.checked).value)
                .semantics {
                    contentDescription = row.title
                    stateDescription = if (statusDescription.isEmpty()) state else "$state, $statusDescription"
                }
                .toggleable(value = row.checked, enabled = toggleEnabled, role = Role.Switch,
                    onValueChange = row.toggle),
            contentAlignment = Alignment.Center
        ) {
            Switch(checked = row.checked, onCheckedChange = null, enabled = toggleEnabled,
                modifier = Modifier.size(25.dp))
        }
    }
}

@Composable
internal fun NetworkConnectivityIcon(row: NetworkConnectivityRow) {
    val modifier = Modifier.padding(end = 6.dp).size(20.dp)
    val pluginIcon = row.pluginIcon
    if (pluginIcon != null) {
        AndroidView(
            modifier = modifier,
            factory = { context -> ImageView(context) },
            update = {
                it.setImageDrawable(pluginIcon)
                it.contentDescription = row.title
            }
        )
    } else {
        row.iconRes?.let { resource ->
            if (resource == R.drawable.ic_connection_sitx_logo) {
                SitxBrandIcon(row.title, Modifier.padding(end = 6.dp))
            } else {
                val suppressed = row.checked && row.suppressed
                val suppressedLabel = stringResource(row.suppressionLabel)
                Box(modifier) {
                    Image(painterResource(resource), contentDescription = row.title,
                        colorFilter = if (suppressed) ColorFilter.tint(Color.Gray) else null,
                        modifier = Modifier.fillMaxSize().semantics {
                            if (suppressed) stateDescription = suppressedLabel
                        })
                    if (suppressed) Canvas(Modifier.align(Alignment.BottomEnd).size(6.dp)) {
                        drawCircle(Color.Black)
                        drawLine(Color.Gray, Offset(0f, size.height), Offset(size.width, 0f), 1.dp.toPx())
                    }
                }
            }
        }
    }
}

@Composable
internal fun SitxBrandIcon(contentDescription: String?, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_connection_sitx_logo),
        contentDescription = contentDescription,
        modifier = modifier.size(width = 32.dp, height = 20.dp)
    )
}
