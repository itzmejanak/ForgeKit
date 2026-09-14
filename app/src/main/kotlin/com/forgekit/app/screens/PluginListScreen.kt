package com.forgekit.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.forgekit.app.ForgeViewModel
import com.forgekit.app.screens.component.PluginSummaryCard
import com.forgekit.plugin.api.PluginDescriptor
import com.forgekit.plugin.api.PluginStatus
import com.forgekit.runtime.api.RuntimeState
import com.forgekit.ui.design.ForgeChoiceChip
import com.forgekit.ui.design.ForgeEmptyState
import com.forgekit.ui.design.ForgeLazyPage
import com.forgekit.ui.design.ForgePageHeader
import com.forgekit.ui.design.ForgePrimaryButton
import com.forgekit.ui.design.ForgeSpacing
import com.forgekit.ui.design.ForgeTextField

/**
 * The plugin registry as its own destination: searchable, status-filtered,
 * unmistakably NOT the home dashboard. Home stays a dashboard (§77-78);
 * this screen is the full browse surface.
 */
@Composable
public fun PluginListScreen(
    plugins: List<PluginDescriptor>,
    pendingImports: Int,
    runtimeState: RuntimeState?,
    provisioning: Map<String, ForgeViewModel.ProvisioningState> = emptyMap(),
    onImport: () -> Unit,
    onOpenPlugin: (PluginDescriptor) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf<PluginStatus?>(null) }

    val shown = plugins.filter { plugin ->
        val matchesQuery = query.isBlank() ||
            plugin.name.contains(query, ignoreCase = true) ||
            plugin.id.raw.contains(query, ignoreCase = true)
        matchesQuery && (statusFilter == null || plugin.status == statusFilter)
    }

    ForgeLazyPage(
        modifier = modifier,
        header = {
            ForgePageHeader(
                title = "Plugins",
                subtitle = "${plugins.size} installed${if (pendingImports > 0) " · $pendingImports pending" else ""}",
            )
        },
    ) {
        item {
            ForgeTextField(
                value = query,
                onValueChange = { query = it },
                label = "Search name or id",
                leadingIcon = Icons.Filled.Search,
                mono = false,
            )
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ForgeSpacing.compact)) {
                ForgeChoiceChip(label = "all", selected = statusFilter == null, onClick = { statusFilter = null })
                ForgeChoiceChip(label = "ready", selected = statusFilter == PluginStatus.READY, onClick = { statusFilter = PluginStatus.READY })
                ForgeChoiceChip(label = "unresolved", selected = statusFilter == PluginStatus.UNRESOLVED, onClick = { statusFilter = PluginStatus.UNRESOLVED })
            }
        }

        item {
            ForgePrimaryButton(
                text = "Import .forge package",
                enabled = runtimeState == RuntimeState.READY,
                icon = Icons.Filled.Add,
                onClick = onImport,
            )
        }

        if (shown.isEmpty()) {
            item {
                if (plugins.isEmpty()) {
                    ForgeEmptyState(
                        icon = Icons.Outlined.Storage,
                        title = "No plugins installed",
                        message = "Import a .forge archive to begin. Packages are quarantined and validated before anything runs.",
                    )
                } else {
                    ForgeEmptyState(
                        icon = Icons.Filled.Search,
                        title = "Nothing matches",
                        message = "Try a different search or clear the status filter.",
                    )
                }
            }
        } else {
            items(shown, key = { it.id.raw + it.version.raw }) { plugin ->
                PluginSummaryCard(
                    plugin = plugin,
                    provisioning = provisioning[plugin.id.raw],
                    onClick = { onOpenPlugin(plugin) },
                    showDetails = true,
                )
            }
        }
    }
}
