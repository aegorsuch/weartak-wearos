package com.aitech.weartak_civ.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.R

internal fun compactTakAttribution(text: String): AnnotatedString =
    AnnotatedString.Builder(text).apply {
        addStyle(SpanStyle(fontSize = 6.sp), 0, text.length)
    }.toAnnotatedString()

@Composable
internal fun MapCreditsSection(source: MapSource, onOpenLink: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    var failedUrl by remember(source) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().testTag("map-credits")) {
        OverlayManagerCategory(
            label = stringResource(R.string.map_credits),
            tag = "map-credits-disclosure",
            initiallyExpanded = false
        ) {
            mapCredits(source).forEach { credit ->
                Text(
                    credit.label, color = Color.White, fontSize = 10.sp,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp)
                        .clickable(role = Role.Button) {
                            if (onOpenLink != null) onOpenLink(credit.url)
                            else try {
                                context.startActivity(android.content.Intent(
                                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(credit.url)
                                ))
                                failedUrl = null
                            } catch (error: android.content.ActivityNotFoundException) {
                                android.util.Log.w("MapCredits", "No browser for ${credit.url}", error)
                                failedUrl = credit.url
                            } catch (error: SecurityException) {
                                android.util.Log.w("MapCredits", "Browser access denied for ${credit.url}", error)
                                failedUrl = credit.url
                            }
                        }.padding(vertical = 4.dp)
                )
            }
            failedUrl?.let {
                Text(stringResource(R.string.map_credit_link_unavailable, it),
                    color = Color(0xFFFFB4AB), fontSize = 10.sp)
            }
        }
    }
}

@Composable
internal fun MapSourcePicker(
    source: MapSource,
    onSelect: (MapSource) -> Unit,
    error: String?,
    onRetry: () -> Unit
) {
    OverlayManagerCategory(
        label = stringResource(R.string.map_sources),
        tag = "overlay-manager-maps"
    ) {
        val viewConfiguration = LocalViewConfiguration.current
        CompositionLocalProvider(LocalViewConfiguration provides object : ViewConfiguration by viewConfiguration {
            override val minimumTouchTargetSize = DpSize(28.dp, 28.dp)
        }) {
            MapSource.selectable.forEach { option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp)
                        .testTag("map-source-${option.preferenceValue}")
                        .selectable(source == option, role = Role.RadioButton, onClick = { onSelect(option) })
                ) {
                    val markColor = if (source == option) MaterialTheme.colors.secondary else Color.White
                    Canvas(Modifier.size(14.dp).testTag("map-source-mark-${option.preferenceValue}")) {
                        val stroke = 1.5.dp.toPx()
                        drawCircle(markColor, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
                        if (source == option) drawCircle(markColor, radius = 3.5.dp.toPx())
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(option.title, color = Color.White, fontSize = 10.sp)
                }
            }
        }
    }
    if (error != null) {
        Column {
            Text(error, color = Color(0xFFFFB4AB), fontSize = 11.sp)
            MapRecoveryAction(stringResource(R.string.map_retry), onRetry)
            if (source != MapSource.DEFAULT) {
                MapRecoveryAction(stringResource(R.string.map_restore_original)) { onSelect(MapSource.DEFAULT) }
            }
        }
    }
}

@Composable
private fun MapRecoveryAction(label: String, onClick: () -> Unit) {
    androidx.wear.compose.material.Chip(
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    )
}
