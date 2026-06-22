package com.jeeves.desktop.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * CompositionLocal that indicates whether the window is in compact mode.
 * When true, buttons should show only icons with tooltips.
 */
val LocalCompactMode = compositionLocalOf { false }

/**
 * Width threshold (dp) below which the app switches to compact mode.
 * Buttons show only icons with tooltip on hover.
 */
const val COMPACT_WIDTH_THRESHOLD = 700

/**
 * A button that adapts to window size:
 * - Normal: shows icon + text
 * - Compact (small window): shows only icon with text as tooltip on hover
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ResponsiveButton(
    onClick: () -> Unit,
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    outlined: Boolean = false
) {
    val isCompact = LocalCompactMode.current

    if (isCompact) {
        TooltipArea(
            tooltip = {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    tonalElevation = 4.dp
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            },
            tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp))
        ) {
            if (outlined) {
                OutlinedIconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
                    Icon(icon, contentDescription = text, modifier = Modifier.size(18.dp))
                }
            } else {
                IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
                    Icon(icon, contentDescription = text, modifier = Modifier.size(18.dp))
                }
            }
        }
    } else {
        if (outlined) {
            OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text)
            }
        } else {
            Button(onClick = onClick, enabled = enabled, modifier = modifier) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text)
            }
        }
    }
}
