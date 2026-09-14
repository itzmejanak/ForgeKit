package com.forgekit.app.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.forgekit.plugin.api.PluginDescriptor
import com.forgekit.plugin.manifest.PluginManifest
import com.forgekit.ui.design.ForgeCard
import com.forgekit.ui.design.ForgeChoiceChip
import com.forgekit.ui.design.ForgeLazyPage
import com.forgekit.ui.design.ForgePageHeader
import com.forgekit.ui.design.ForgePalette
import com.forgekit.ui.design.ForgePrimaryButton
import com.forgekit.ui.design.ForgeSecondaryButton
import com.forgekit.ui.design.ForgeSpacing
import com.forgekit.ui.design.ForgeTextField
import com.forgekit.ui.design.ForgeTypography
import com.forgekit.ui.design.MetadataLine
import com.forgekit.ui.design.SectionHeader
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Run draft (reference screen 8): status chips, INPUTS card, OPTIONS cards
 * rendered as chip groups, dependency facts, and the final Run button.
 * Everything is driven by the manifest's ActionDeclaration — nothing is
 * hardcoded. Required-input validation gates the Run button.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
public fun ActionRunScreen(
    descriptor: PluginDescriptor,
    action: PluginManifest.ActionDeclaration,
    onRun: (Map<String, String>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // input + option values keyed by control/option id (option defaults preselected)
    val values = remember(action.id) {
        mutableStateMapOf<String, String>().apply {
            action.options.forEach { option ->
                option.default?.let { put(option.id, it) }
            }
        }
    }

    // which file input a picker result belongs to (set before launching)
    var pendingFileInput by remember(action.id) { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        val inputId = pendingFileInput
        pendingFileInput = null // every result (and every cancel) clears the pending slot
        if (uri != null && inputId != null) {
            // content URIs are not seekable paths — copy into app cache and hand the
            // plugin a real file path (same gate as import). The copy keeps the file's
            // REAL display name inside a per-run subdir (avoids collisions) so the
            // plugin — and any output it names after its input — uses the original name.
            val name = displayNameOf(context, uri)
            runCatching {
                val dir = context.cacheDir.toPath().resolve("run-${System.currentTimeMillis()}")
                Files.createDirectories(dir)
                val target = dir.resolve(name)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                }
                target
            }.onSuccess { target ->
                values[inputId] = target.toString()
            }
        }
    }

    val requiredMissing = action.inputs
        .filter { it.required }
        .filter { values[it.id].isNullOrBlank() }
        .map { it.id }

    ForgeLazyPage(
        modifier = modifier,
        header = {
            ForgePageHeader(
                title = action.title,
                subtitle = "${descriptor.id.raw} · ${descriptor.version.raw}",
                onBack = onBack,
            )
        },
    ) {
        action.description?.let {
            item { Text(it, style = ForgeTypography.caption, color = ForgePalette.textSecondary) }
        }

        if (action.inputs.isNotEmpty()) {
            item { SectionHeader("inputs") }
            items(action.inputs.size) { index ->
                val input = action.inputs[index]
                val value = values[input.id].orEmpty()
                ForgeCard {
                    MetadataLine("input", "${input.id} · ${input.type}${if (input.required) " · required" else ""}")
                    ForgeTextField(
                        value = value,
                        onValueChange = { values[input.id] = it },
                        label = input.label,
                        placeholder = if (input.type == "file") "/path/to/file" else null,
                        password = input.type == "password",
                    )
                    if (input.type == "file") {
                        ForgeSecondaryButton(
                            text = "Browse",
                            onClick = {
                                pendingFileInput = input.id
                                filePicker.launch("*/*")
                            },
                        )
                    }
                }
            }
        }

        if (action.options.isNotEmpty()) {
            item { SectionHeader("options") }
            items(action.options.size) { index ->
                val option = action.options[index]
                val selected = values[option.id] ?: option.default ?: option.choices.firstOrNull() ?: ""
                ForgeCard {
                    SectionHeader(option.label)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(ForgeSpacing.compact),
                        verticalArrangement = Arrangement.spacedBy(ForgeSpacing.compact),
                    ) {
                        option.choices.forEach { choice ->
                            ForgeChoiceChip(
                                label = choice,
                                selected = choice == selected,
                                onClick = { values[option.id] = choice },
                            )
                        }
                    }
                }
            }
        }

        if (action.outputs.isNotEmpty()) {
            item { SectionHeader("outputs") }
            item {
                ForgeCard {
                    action.outputs.forEach { output ->
                        MetadataLine(output.id, "${output.label ?: output.id} · ${output.type}")
                    }
                }
            }
        }

        item {
            ForgePrimaryButton(
                text = if (requiredMissing.isEmpty()) "Run ${action.title}" else "Fill required inputs (${requiredMissing.size})",
                enabled = requiredMissing.isEmpty(),
                onClick = { onRun(values.toMap()) },
            )
        }
    }
}

/**
 * The file's real display name for a content URI (OpenableColumns), so a copied input
 * keeps its original name. Falls back to the last path segment, then "input".
 */
private fun displayNameOf(context: Context, uri: Uri): String {
    val fromProvider = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        }
    }.getOrNull()
    val raw = fromProvider ?: uri.lastPathSegment?.substringAfterLast('/')
    return raw?.takeIf { it.isNotBlank() }?.substringAfterLast('/') ?: "input"
}
