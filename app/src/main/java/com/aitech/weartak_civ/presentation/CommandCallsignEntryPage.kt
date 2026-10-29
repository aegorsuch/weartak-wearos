package com.aitech.weartak_civ.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.command.ChatCommandAuthorizer

@Composable
internal fun CommandCallsignEntryPage(
    navController: NavController,
    callsigns: List<String>,
    originalCallsign: String? = null,
    onSave: (List<String>) -> Unit
) {
    val context = LocalContext.current
    val otherCallsigns = callsigns.filterNot {
        originalCallsign != null &&
            ChatCommandAuthorizer.normalize(it) == ChatCommandAuthorizer.normalize(originalCallsign)
    }
    WearTAKStringEntryPage(
        navController = navController,
        item = originalCallsign.orEmpty(),
        label = stringResource(R.string.callsign),
        validate = { input ->
            val callsign = ChatCommandAuthorizer.validatedCallsign(input)
            when {
                callsign == null -> context.getString(R.string.invalid_chat_command_callsign)
                otherCallsigns.any { ChatCommandAuthorizer.normalize(it) == ChatCommandAuthorizer.normalize(callsign) } ->
                    context.getString(R.string.duplicate_chat_command_callsign)
                else -> null
            }
        },
        confirmFunction = { input ->
            val callsign = requireNotNull(ChatCommandAuthorizer.validatedCallsign(input))
            val savedCallsign = if (originalCallsign != null &&
                ChatCommandAuthorizer.normalize(input) == ChatCommandAuthorizer.normalize(originalCallsign)
            ) originalCallsign else callsign
            onSave(ChatCommandAuthorizer.sortedAllowList(otherCallsigns + savedCallsign))
        },
        confirmOnImeDone = true,
        autoFocus = originalCallsign != null
    )
}

@Composable
internal fun CommandCallsignRow(callsign: String, onEdit: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.DarkGray, RoundedCornerShape(24.dp))
            .clickable(onClick = onEdit)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = callsign,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.remove_chat_command_callsign, callsign)
            )
        }
    }
}
