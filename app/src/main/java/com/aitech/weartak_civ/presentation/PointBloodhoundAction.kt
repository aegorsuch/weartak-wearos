package com.aitech.weartak_civ.presentation

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aitech.weartak_civ.R

@Composable
fun PointBloodhoundAction(
    isActive: Boolean,
    onBloodhound: () -> Unit,
    onStop: () -> Unit,
    onTarget: () -> Unit,
    enabled: Boolean = true,
    onDelete: (() -> Unit)? = null,
    targetName: String = ""
) {
    MapTargetActionRow(
        onBloodhound = if (isActive) onStop else onBloodhound,
        onTarget = onTarget,
        enabled = enabled,
        bloodhoundDescription = R.string.point_bloodhound,
        onDelete = onDelete,
        isActive = isActive,
        targetName = targetName
    )
}

@Composable
internal fun MapTargetActionRow(
    onBloodhound: () -> Unit,
    onTarget: () -> Unit,
    enabled: Boolean = true,
    bloodhoundDescription: Int = R.string.bloodhound_start,
    onDelete: (() -> Unit)? = null,
    isActive: Boolean = false,
    targetName: String = ""
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        MapBloodhoundActionButton(isActive, targetName, bloodhoundDescription, enabled, onBloodhound)
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

@Composable
internal fun MapBloodhoundActionButton(
    isActive: Boolean,
    targetName: String,
    startDescription: Int,
    enabled: Boolean,
    onClick: () -> Unit
) {
    if (isActive) {
        val stopDescription = stringResource(R.string.bloodhound_picker_stop, targetName)
        Button(
            onClick = onClick,
            modifier = Modifier.size(48.dp).semantics { contentDescription = stopDescription },
            colors = ButtonDefaults.buttonColors(
                backgroundColor = Color(0xFFB3261E), contentColor = Color.White)
        ) {
            Box(Modifier.size(10.dp).background(Color.White))
        }
    } else {
        MapActionIconButton(R.drawable.bloodhound, startDescription, enabled, onClick)
    }
}

internal fun returnToWearTakHome(context: Context) {
    context.startActivity(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
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
    MapActionIconButton(icon, stringResource(description), enabled, onClick)
}

@Composable
internal fun MapActionIconButton(
    icon: Int,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    MapStyleActionButton(description, enabled, onClick) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
    }
}

@Composable
internal fun MapStyleActionButton(
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    backgroundColor: Color = Color.DarkGray,
    icon: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp).semantics { contentDescription = description },
        colors = ButtonDefaults.buttonColors(
            backgroundColor = backgroundColor, contentColor = Color.White)
    ) {
        icon()
    }
}
