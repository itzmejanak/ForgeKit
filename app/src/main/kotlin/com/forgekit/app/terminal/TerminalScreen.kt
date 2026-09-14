package com.forgekit.app.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.forgekit.app.ForgeViewModel
import com.forgekit.runtime.api.RuntimeState
import com.forgekit.ui.design.ForgeEmptyState
import com.forgekit.ui.design.ForgeSecondaryButton
import com.forgekit.ui.design.ForgeSpacing
import com.forgekit.ui.terminal.TerminalEmulator

/**
 * Terminal tab entry (ARCHITECTURE §17/§38, Mode B). Gates on runtime READY, opens a REAL
 * pty shell off the main thread, and hands a live [TerminalEmulator] to [TerminalSurface].
 * Restart bumps an epoch so the session is rebuilt. All chrome/interaction lives in the
 * split surface/extra-keys/input files — this file is only the host + session lifecycle.
 */
@Composable
public fun TerminalScreen(
    viewModel: ForgeViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.state.collectAsState()
    val runtimeState = uiState.runtimeState
    var epoch by remember { mutableIntStateOf(0) }

    // Open the session off-main (fork+exec is blocking). null = not attempted yet.
    val attempt by produceState<Result<TerminalEmulator>?>(null, runtimeState, epoch) {
        value = null
        if (runtimeState == RuntimeState.READY) {
            value = runCatching {
                val session = viewModel.openShellSession(columns = 80, rows = 24)
                TerminalEmulator(
                    session = session,
                    scope = viewModel.viewModelScope,
                    columns = 80,
                    rows = 24,
                )
            }
        }
    }

    val current = attempt
    when {
        runtimeState != RuntimeState.READY || current == null -> TerminalNotice(
            "runtime is ${runtimeState?.name?.lowercase() ?: "starting"} — the shell opens once it is ready",
            modifier,
        )
        current.isFailure -> TerminalNotice(
            "shell failed to start: ${current.exceptionOrNull()?.message ?: "unknown error"}",
            modifier,
            retryLabel = "Retry shell",
            onRetry = { epoch++ },
        )
        else -> TerminalSurface(current.getOrThrow(), modifier, onRestart = { epoch++ })
    }
}


@Composable
private fun TerminalNotice(
    message: String,
    modifier: Modifier = Modifier,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize().padding(horizontal = ForgeSpacing.gutter), contentAlignment = Alignment.Center) {
        ForgeEmptyState(
            icon = Icons.Outlined.Terminal,
            title = "Terminal",
            message = message,
            action = if (retryLabel != null && onRetry != null) {
                { ForgeSecondaryButton(text = retryLabel, icon = Icons.Filled.Refresh, onClick = onRetry) }
            } else {
                null
            },
        )
    }
}
