package com.forgekit.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Swipe right (start → end) to delete a card-shaped row. The error-tinted layer with
 * the delete affordance is revealed behind the card as it slides. Pass `enabled = false`
 * for rows that must not be removed (e.g. a job that is still running).
 *
 * [onDelete] should remove the row from the list; the row leaves composition with it.
 */
@Composable
public fun ForgeSwipeToDelete(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String = "Delete",
    content: @Composable () -> Unit,
) {
    val currentOnDelete by rememberUpdatedState(onDelete)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) {
                currentOnDelete()
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = enabled,
        enableDismissFromEndToStart = false,
        gesturesEnabled = enabled,
        backgroundContent = {
            if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(ForgeShapes.card)
                        .background(ForgePalette.errorContainer)
                        .padding(horizontal = ForgeSpacing.gutter),
                    horizontalArrangement = Arrangement.spacedBy(ForgeSpacing.compact),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = ForgePalette.error, modifier = Modifier.size(22.dp))
                    Text(label, style = ForgeTypography.labelLarge, color = ForgePalette.error)
                }
            }
        },
    ) {
        content()
    }
}
