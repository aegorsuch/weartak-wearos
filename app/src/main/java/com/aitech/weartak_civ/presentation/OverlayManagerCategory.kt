package com.aitech.weartak_civ.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.R

@Composable
internal fun OverlayManagerCategory(
    label: String,
    tag: String,
    color: Color = Color.White,
    initiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val action = stringResource(
        if (expanded) R.string.map_category_collapse else R.string.map_category_expand, label)
    val state = stringResource(
        if (expanded) R.string.map_credits_expanded else R.string.map_credits_collapsed)
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp)
                .testTag(tag)
                .clickable(role = Role.Button, onClickLabel = action) { expanded = !expanded }
                .semantics {
                    contentDescription = action
                    stateDescription = state
                }
        ) {
            Text(
                label, color = color, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null, tint = color,
                modifier = Modifier.size(18.dp).rotate(if (expanded) 0f else -90f)
                    .testTag("$tag-arrow-${if (expanded) "down" else "right"}")
            )
        }
        if (expanded) content()
    }
}
