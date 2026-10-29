package com.aitech.weartak_civ.presentation

import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.DittoPdlSource
import com.aitech.weartak_civ.service.DittoPowerSuppression
import java.text.Collator
import java.util.Locale

internal data class NetworkConnectivityRow(
    val key: String,
    val title: String,
    val subtitle: String,
    val checked: Boolean,
    val toggle: (Boolean) -> Unit,
    val statusDescription: String = subtitle,
    val settingsRoute: String? = networkSettingsRoute(key),
    @DrawableRes val iconRes: Int? = networkConnectivityIcon(key),
    val pluginIcon: Drawable? = null,
    val suppressed: Boolean = false,
    @StringRes val suppressionLabel: Int = R.string.connection_route_suppressed,
)

@DrawableRes
internal fun networkConnectivityIcon(key: String): Int? = when (key) {
    "tak" -> R.drawable.tak_server_connected
    "companion" -> R.drawable.weartak_companion_plugin
    "multicast" -> R.drawable.multicast_connected
    "sitx" -> R.drawable.ic_connection_sitx_logo
    "ditto" -> R.drawable.ic_integration_ditto
    else -> null
}

internal fun networkSettingsRoute(key: String): String? = when (key) {
    "tak" -> "tak_servers"
    "companion" -> COMPANION_DETAILS_ROUTE
    "multicast" -> "tak_sa_multicast"
    "sitx" -> "sitx_tak_screen"
    "ditto" -> "ditto_pdl"
    else -> null
}

internal fun List<NetworkConnectivityRow>.alphabetizedConnections(locale: Locale): List<NetworkConnectivityRow> {
    val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
    return sortedWith { a, b ->
        collator.compare(a.title, b.title).takeIf { it != 0 } ?: a.key.compareTo(b.key)
    }
}

internal fun toggleStateAnnouncement(
    checked: Boolean,
    onLabel: String,
    offLabel: String,
    status: String,
    visibleDescription: String
): String? {
    if (status.isEmpty() || status == visibleDescription) return null
    return "${if (checked) onLabel else offLabel}, $status"
}
@StringRes
internal fun dittoPdlSubtitle(source: DittoPdlSource, hasCustomPdl: Boolean): Int = when (source) {
    DittoPdlSource.COMMUNITY -> R.string.ditto_community_subtitle
    DittoPdlSource.CUSTOM -> if (hasCustomPdl) R.string.ditto_custom_subtitle else R.string.ditto_no_pdl_subtitle
}

@StringRes
internal fun dittoSuppressionLabel(enabled: Boolean, reason: DittoPowerSuppression?): Int? = when {
    !enabled || reason == null -> null
    reason == DittoPowerSuppression.OFF_WRIST -> R.string.physio_not_worn
    else -> R.string.connection_route_suppressed
}
