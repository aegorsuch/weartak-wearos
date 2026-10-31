package com.aitech.weartak_civ.presentation

import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.connectionPriority
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.graphics.drawable.toBitmap

internal data class HomeConnectionStatus(
    val key: String,
    @DrawableRes val icon: Int,
    @StringRes val title: Int,
    @StringRes val status: Int,
    val pluginTitle: String? = null,
    val pluginIcon: Drawable? = null
)

internal fun resolveHomeConnectionStatus(
    priority: List<String>, candidates: List<HomeConnectionStatus>
): HomeConnectionStatus {
    val byKey = candidates.associateBy { it.key }
    return connectionPriority(priority, byKey.keys.toList()).firstNotNullOfOrNull { byKey[it] }
        ?: HomeConnectionStatus("disconnected", R.drawable.tak_server_disconnected,
            R.string.network_preferences, R.string.server_disconnected)
}

@Composable
internal fun HomeConnectionIcon(status: HomeConnectionStatus, modifier: Modifier = Modifier) {
    val pluginIcon = status.pluginIcon
    val painter = if (pluginIcon != null) {
        remember(pluginIcon) { BitmapPainter(pluginIcon.toBitmap(64, 64).asImageBitmap()) }
    } else painterResource(status.icon)
    Image(
        painter = painter,
        contentDescription = stringResource(R.string.connection_status_description,
            status.pluginTitle ?: stringResource(status.title), stringResource(status.status)),
        modifier = modifier
    )
}
