package com.forgekit.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.forgekit.app.ForgeViewModel
import com.forgekit.app.screens.component.tone
import com.forgekit.ui.design.ForgeCard
import com.forgekit.ui.design.ForgeDivider
import com.forgekit.ui.design.ForgeLazyPage
import com.forgekit.ui.design.ForgeNoticeBox
import com.forgekit.ui.design.ForgePageHeader
import com.forgekit.ui.design.ForgePalette
import com.forgekit.ui.design.ForgeSecondaryButton
import com.forgekit.ui.design.ForgeSpacing
import com.forgekit.ui.design.MetadataLine
import com.forgekit.ui.design.SectionHeader
import com.forgekit.ui.design.StatusChip
import com.forgekit.ui.design.StatusTone
import com.forgekit.ui.design.rememberConfirmGate

/**
 * Settings (§77 user mode, §78 advanced runtime): runtime status facts from
 * the live runtime, repair/diagnostics actions, and the app build identity.
 */
@Composable
public fun SettingsScreen(
    viewModel: ForgeViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val facts by remember { mutableStateOf(viewModel.runtimeFacts()) }
    val runtimeState = state.runtimeState
    val error = state.runtimeError
    val repairGate = rememberConfirmGate()

    ForgeLazyPage(
        modifier = modifier,
        header = { ForgePageHeader(title = "Settings", subtitle = "runtime · storage · about") },
    ) {
        item { SectionHeader("runtime") }
        item {
            ForgeCard {
                StatusChip(label = runtimeState?.name ?: "UNKNOWN", tone = runtimeState?.tone() ?: StatusTone.INFO)
                ForgeDivider()
                MetadataLine("architecture", facts.architecture ?: "unknown")
                MetadataLine("prefix", facts.prefix ?: "—")
                MetadataLine("home", facts.home ?: "—")
                MetadataLine("term", "xterm-256color")
                error?.let { ForgeNoticeBox(text = it, tone = StatusTone.ERROR) }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(ForgeSpacing.compact)) {
                ForgeSecondaryButton(text = "Run health diagnostics", onClick = { viewModel.runDiagnostics() })
                ForgeSecondaryButton(text = "Repair runtime", onClick = repairGate::request)
            }
        }

        item { SectionHeader("storage") }
        item {
            ForgeCard {
                MetadataLine("plugins", facts.pluginsRoot ?: "—")
                MetadataLine("output", facts.outputRoot ?: "—")
                MetadataLine("jobs", facts.jobsRoot ?: "—")
            }
        }

        item { SectionHeader("observed") }
        item {
            ForgeCard {
                MetadataLine("plugins", "${facts.pluginCount}")
                MetadataLine("running jobs", "${facts.activeJobCount}")
                MetadataLine("pending imports", "${facts.pendingImportCount}")
            }
        }

        item { SectionHeader("about") }
        item {
            ForgeCard {
                MetadataLine("package", facts.packageName)
                MetadataLine("version", facts.versionName)
                MetadataLine("runtime", "embedded termux (§7)")
                ForgeDivider()
                Text(
                    text = "ForgeKit is free software under GNU GPLv3, without warranty. " +
                        "Source, license and third-party notices: github.com/itzmejanak/ForgeKit",
                    style = MaterialTheme.typography.bodySmall,
                    color = ForgePalette.textSecondary,
                )
            }
        }
    }

    repairGate.Dialog(
        title = "Repair runtime?",
        message = "ForgeKit checks the embedded runtime and restores it from the bundled bootstrap " +
            "if anything is broken. The app shows the runtime screen until repair finishes, " +
            "so running jobs and the terminal can be interrupted.",
        confirmLabel = "Repair",
        onConfirm = { viewModel.repairRuntime() },
    )
}
