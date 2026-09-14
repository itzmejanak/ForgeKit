package com.forgekit.ui.design

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * The one confirmation dialog for consequential actions (remove, clear, repair, plugin
 * actions that declare `confirm`). [destructive] colors the confirm action in the error tone.
 */
@Composable
public fun ForgeConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = ForgeShapes.card,
        containerColor = ForgePalette.surfaceElevated,
        titleContentColor = ForgePalette.textPrimary,
        textContentColor = ForgePalette.textSecondary,
        title = { Text(title, style = ForgeTypography.titleLarge) },
        text = { Text(message, style = ForgeTypography.body) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) {
                Text(
                    confirmLabel,
                    style = ForgeTypography.labelLarge,
                    color = if (destructive) ForgePalette.error else ForgePalette.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", style = ForgeTypography.labelLarge, color = ForgePalette.textSecondary)
            }
        },
    )
}

/**
 * Wraps an action behind [ForgeConfirmDialog]: call the returned lambda instead of the
 * action; the dialog renders from [ConfirmGate.Dialog] wherever the caller places it.
 */
public class ConfirmGate internal constructor(private val open: MutableState<Boolean>) {
    /** Asks for confirmation (shows the dialog). */
    public fun request() {
        open.value = true
    }

    @Composable
    public fun Dialog(
        title: String,
        message: String,
        confirmLabel: String,
        onConfirm: () -> Unit,
        destructive: Boolean = false,
    ) {
        if (!open.value) return
        ForgeConfirmDialog(
            title = title,
            message = message,
            confirmLabel = confirmLabel,
            onConfirm = onConfirm,
            onDismiss = { open.value = false },
            destructive = destructive,
        )
    }
}

@Composable
public fun rememberConfirmGate(): ConfirmGate = remember { ConfirmGate(mutableStateOf(false)) }
