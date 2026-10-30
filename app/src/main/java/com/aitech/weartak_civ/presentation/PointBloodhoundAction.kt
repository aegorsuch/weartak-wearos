package com.aitech.weartak_civ.presentation

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.aitech.weartak_civ.R

@Composable
fun PointBloodhoundAction(
    isActive: Boolean,
    onBloodhound: () -> Unit,
    onInPosition: () -> Unit,
    onTarget: () -> Unit,
    enabled: Boolean = true,
    onDelete: (() -> Unit)? = null
) {
    MapTargetActionRow(
        onBloodhound = if (isActive) onInPosition else onBloodhound,
        onTarget = onTarget,
        enabled = enabled,
        bloodhoundDescription = if (isActive) R.string.npos else R.string.point_bloodhound,
        onDelete = onDelete
    )
}

@Composable
internal fun MapTargetActionRow(
    onBloodhound: () -> Unit,
    onTarget: () -> Unit,
    enabled: Boolean = true,
    bloodhoundDescription: Int = R.string.bloodhound_start,
    onDelete: (() -> Unit)? = null
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        MapActionIconButton(R.drawable.bloodhound, bloodhoundDescription, enabled, onBloodhound)
        MapActionIconButton(R.drawable.lock_on_self, R.string.map_focus_target, enabled, onTarget)
        if (onDelete != null) {
            Button(
                onClick = onDelete,
                modifier = Modifier.size(48.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = Color.Red, contentColor = Color.White)
            ) {
                Icon(Icons.Outlined.Delete, stringResource(R.string.delete), modifier = Modifier.size(22.dp))
            }
        }
    }

}

internal fun focusPointOnMap(context: Context, latitude: Double, longitude: Double) {
    context.startActivity(Intent(context, MapActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(MapActivity.EXTRA_FOCUS_LAT, latitude)
        .putExtra(MapActivity.EXTRA_FOCUS_LON, longitude))
}

@Composable
internal fun MapActionIconButton(
    icon: Int,
    description: Int,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = Color.DarkGray, contentColor = Color.White)
    ) {
        Icon(painterResource(icon), stringResource(description), modifier = Modifier.size(22.dp))
    }
}
