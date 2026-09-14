package com.forgekit.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.forgekit.app.ForgeViewModel.UiState
import com.forgekit.app.screens.component.PluginSummaryCard
import com.forgekit.plugin.api.PluginDescriptor
import com.forgekit.runtime.api.RuntimeState
import com.forgekit.ui.design.ForgeCard
import com.forgekit.ui.design.ForgeEmptyState
import com.forgekit.ui.design.ForgeLazyPage
import com.forgekit.ui.design.ForgePageHeader
import com.forgekit.ui.design.ForgePalette
import com.forgekit.ui.design.ForgePrimaryButton
import com.forgekit.ui.design.ForgeSecondaryButton
import com.forgekit.ui.design.ForgeSpacing
import com.forgekit.ui.design.ForgeTypography
import com.forgekit.ui.design.MonoText
import com.forgekit.ui.design.SectionHeader

/**
 * Home dashboard (reference screen 1): runtime state, three stat cards,
 * the primary import affordance, and the REGISTRY list — every value from
 * live state, nothing decorative.
 */
@Composable
public fun HomeScreen(
    state: UiState,
    onImport: () -> Unit,
    onOpenJobs: () -> Unit,
    onOpenPlugin: (PluginDescriptor) -> Unit,
    onDownloadDocs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        ForgeLazyPage(
            bottomPadding = ForgeSpacing.fabClear,
            header = {
                ForgePageHeader(title = "ForgeKit", subtitle = "plugin runtime · android", leadingIcon = Icons.Filled.Bolt)
            },
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ForgeSpacing.rowGap)) {
                    StatCard("plugins", state.plugins.size, Modifier.weight(1f))
                    StatCard("jobs", state.jobs.size, Modifier.weight(1f))
                    StatCard("imports", state.pendingImports.size, Modifier.weight(1f))
                }
            }

            item {
                ForgePrimaryButton(
                    text = "Import .forge package",
                    onClick = onImport,
                    enabled = state.runtimeState == RuntimeState.READY,
                    icon = Icons.Filled.Add,
                )
                if (state.runtimeState != RuntimeState.READY) {
                    MonoText(
                        "runtime ${state.runtimeState?.name?.lowercase() ?: "starting"}" +
                            (state.runtimeError?.let { " — $it" } ?: ""),
                        Modifier.padding(top = ForgeSpacing.compact),
                    )
                }
            }

            item { ForgeSecondaryButton(text = "View job history", onClick = onOpenJobs) }

            item { SectionHeader("registry") }

            if (state.plugins.isEmpty()) {
                item {
                    ForgeEmptyState(
                        icon = Icons.Outlined.Storage,
                        title = "Durable registry ready",
                        message = "No plugins are installed. ForgeKit never creates demo registry records.",
                    )
                }
            } else {
                items(state.plugins, key = { it.id.raw + it.version.raw }) { plugin ->
                    PluginSummaryCard(
                        plugin = plugin,
                        provisioning = state.provisioning[plugin.id.raw],
                        onClick = { onOpenPlugin(plugin) },
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = onDownloadDocs,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = ForgeSpacing.gutter, bottom = ForgeSpacing.cardInner),
            containerColor = ForgePalette.primary,
            contentColor = ForgePalette.onPrimary,
        ) {
            if (state.documentation.exporting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = ForgePalette.onPrimary,
                    strokeWidth = ForgeSpacing.hairline,
                )
            } else {
                Icon(Icons.Outlined.Download, contentDescription = "Download developer guide as Docs.md")
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: Int, modifier: Modifier = Modifier) {
    ForgeCard(modifier) {
        Text("$value", style = ForgeTypography.displaySmall, color = ForgePalette.textPrimary)
        SectionHeader(label)
    }
}
