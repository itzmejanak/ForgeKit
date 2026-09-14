package com.forgekit.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forgekit.app.screens.component.tone
import com.forgekit.runtime.api.RuntimeHealth
import com.forgekit.runtime.api.RuntimeState
import com.forgekit.ui.design.ForgeActionBar
import com.forgekit.ui.design.ForgeBackground
import com.forgekit.ui.design.ForgeCard
import com.forgekit.ui.design.ForgeDivider
import com.forgekit.ui.design.ForgeNoticeBox
import com.forgekit.ui.design.ForgePageHeader
import com.forgekit.ui.design.ForgePalette
import com.forgekit.ui.design.ForgePrimaryButton
import com.forgekit.ui.design.ForgeProgressBar
import com.forgekit.ui.design.ForgeSecondaryButton
import com.forgekit.ui.design.ForgeSpacing
import com.forgekit.ui.design.ForgeStepList
import com.forgekit.ui.design.ForgeStepState
import com.forgekit.ui.design.ForgeStepVisual
import com.forgekit.ui.design.ForgeTheme
import com.forgekit.ui.design.ForgeTypography
import com.forgekit.ui.design.MonoText
import com.forgekit.ui.design.SectionHeader
import com.forgekit.ui.design.StatusDot
import com.forgekit.ui.design.StatusTone
import com.forgekit.ui.design.toneColor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * App shell entry (M1 scope): renders the §70 runtime state machine as the user
 * sees it on first run. The full home/plugins/jobs/terminal UI arrives with the
 * ui modules; this screen is already 100% backend-driven — no synthetic
 * state, every value comes from the live [com.forgekit.runtime.api.ForgeRuntime].
 */
class MainActivity : ComponentActivity() {

    /** Storage grant lands here; the output tree is created as soon as it is allowed. */
    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { (application as ForgeKitApplication).ensureOutputDirectory() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge so Compose sees real IME / system-bar insets. Without it, adjustResize
        // shrinks the whole window and WindowInsets.ime reads 0 — the terminal could neither
        // detect the keyboard nor keep the nav bar from being shoved up.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val app = application as ForgeKitApplication
        requestStoragePermission(app)
        setContent {
            ForgeTheme {
                ForgeApp(
                    bootContent = {
                        RuntimeBootScreen(
                            stateFlow = app.runtime.stateChanges,
                            initError = { app.lastInitError },
                            onRetry = { app.retryInitialization() },
                            onRunDiagnostics = {
                                app.appScope.launch {
                                    val health = runCatching { app.runtime.healthCheck() }.getOrNull()
                                    lastHealth = health
                                }
                            },
                            lastHealth = { lastHealth },
                        )
                    },
                )
            }
        }
    }

    /**
     * Asks for storage on startup so job/plugin results can be written to
     * /sdcard/ForgeKit. Denial is not fatal: output falls back to app-private storage,
     * so the request is made once per launch and never blocks the UI.
     */
    private fun requestStoragePermission(app: ForgeKitApplication) {
        if (app.environment.canWriteOutputDirectory()) {
            app.ensureOutputDirectory()
            return
        }
        storagePermission.launch(
            arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ),
        )
    }

    companion object {
        @Volatile
        private var lastHealth: RuntimeHealth? = null
    }
}

@Composable
private fun RuntimeBootScreen(
    stateFlow: Flow<RuntimeState>,
    initError: () -> String?,
    onRetry: () -> Unit,
    onRunDiagnostics: () -> Unit,
    lastHealth: () -> RuntimeHealth?,
) {
    val state by stateFlow.collectAsStateWithLifecycle(initialValue = RuntimeState.NOT_INSTALLED)
    val error = initError()
    val health = lastHealth()
    val busy = state == RuntimeState.INSTALLING || state == RuntimeState.INITIALIZING || state == RuntimeState.REPAIRING

    ForgeBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding() // edge-to-edge: keep the boot UI out from under the status/nav bars
                .padding(horizontal = ForgeSpacing.gutter),
            verticalArrangement = Arrangement.spacedBy(ForgeSpacing.rowGap, Alignment.CenterVertically),
        ) {
            ForgePageHeader(title = "ForgeKit", subtitle = "plugin runtime · android", leadingIcon = Icons.Filled.Bolt)

            SectionHeader("embedded runtime", Modifier.padding(top = ForgeSpacing.gutter))

            ForgeCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val pulse by animateFloatAsState(
                        targetValue = if (busy) 1f else 0f,
                        animationSpec = tween(600),
                        label = "pulse",
                    )
                    StatusDot(
                        color = if (busy) ForgePalette.primary.copy(alpha = 0.35f + 0.65f * pulse) else toneColor(state.tone()),
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(ForgeSpacing.rowGap))
                    Text(state.name, style = ForgeTypography.stateValue, color = ForgePalette.textPrimary, modifier = Modifier.weight(1f))
                    MonoText(stateMachineStep(state))
                }
                Text(state.describe(), style = ForgeTypography.caption, color = ForgePalette.textSecondary)
                if (busy) ForgeProgressBar(fraction = null)
                error?.let { ForgeNoticeBox(text = it, tone = StatusTone.ERROR) }
                health?.let { h ->
                    ForgeDivider()
                    ForgeStepList(
                        h.checks.map { check ->
                            ForgeStepVisual(
                                key = check.name,
                                label = check.name,
                                state = if (check.passed) ForgeStepState.DONE else ForgeStepState.FAILED,
                                status = if (check.passed) "pass" else "fail",
                            )
                        },
                    )
                }
            }

            ForgeActionBar {
                ForgePrimaryButton(text = "Run health diagnostics", onClick = onRunDiagnostics)
                if (state == RuntimeState.FAILED || state == RuntimeState.DEGRADED) {
                    ForgeSecondaryButton(text = "Repair runtime", onClick = onRetry, icon = Icons.Filled.Refresh)
                }
            }

            if (state == RuntimeState.READY) {
                MonoText(
                    "runtime ready · plugin platform loads next",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun stateMachineStep(state: RuntimeState): String = when (state) {
    RuntimeState.NOT_INSTALLED -> "step 0/6"
    RuntimeState.INSTALLING -> "step 1/6 · verify+extract"
    RuntimeState.INITIALIZING -> "step 2/6 · pkg manager"
    RuntimeState.READY -> "complete"
    RuntimeState.DEGRADED -> "health check failed"
    RuntimeState.REPAIRING -> "wipe+re-extract"
    RuntimeState.FAILED -> "error"
}

private fun RuntimeState.describe(): String = when (this) {
    RuntimeState.NOT_INSTALLED -> "Bootstrap archive not yet installed. Initialization starts automatically on first launch."
    RuntimeState.INSTALLING -> "Verifying the bundled Termux bootstrap (SHA-256 + deep zip integrity), then extracting ~3400 files into the runtime prefix."
    RuntimeState.INITIALIZING -> "Initializing the package manager (dpkg) inside the extracted prefix and preparing the Termux environment."
    RuntimeState.READY -> "Embedded runtime is live: real fork/exec via PTY, dpkg-ready, health checks green."
    RuntimeState.DEGRADED -> "Runtime is present but one or more health checks failed. Repair is available."
    RuntimeState.REPAIRING -> "Wiping the runtime area and re-extracting the bootstrap. No app reinstall required."
    RuntimeState.FAILED -> "Runtime bootstrap failed. The error below carries the exact reason; repair re-attempts from the bundled archive."
}
