package com.forgekit.ui.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.forgekit.ui.design.ForgeCard
import com.forgekit.ui.design.ForgeChoiceChip
import com.forgekit.ui.design.ForgeLogConsole
import com.forgekit.ui.design.ForgeLogLineVisual
import com.forgekit.ui.design.ForgePalette
import com.forgekit.ui.design.ForgePrimaryButton
import com.forgekit.ui.design.ForgeProgressBar
import com.forgekit.ui.design.ForgeSpacing
import com.forgekit.ui.design.ForgeTextField
import com.forgekit.ui.design.ForgeTypography
import com.forgekit.ui.design.SectionHeader
import com.forgekit.ui.design.rememberConfirmGate

/**
 * The reusable rendering engine (§8: "build the actual reusable rendering
 * engine" — no screen is written for one demo plugin).
 *
 * Contract: the renderer NEVER executes anything itself. Action blocks
 * report intent through [ForgeUiHost.onInvoke] and the host (app layer)
 * routes through the job system. Rule 1: UI never executes shell commands.
 */
public interface ForgeUiHost {
    /** The user asked to invoke [actionId]; payload assembly is the renderer's job. */
    public fun onInvoke(actionId: String)

    /** A file/directory input needs a system picker; host resolves the URI. */
    public fun onPickFile(blockId: String, inputId: String, kind: String)

    /** Live protocol events to render into progress/log blocks. */
    public fun progressOf(blockId: String): Float?
    public fun logLinesOf(blockId: String): List<String>
}

/**
 * Renders a validated [UiDocument] against [state]. Every block kind maps
 * to exactly one composable; unknown block kinds are impossible because
 * the parser rejected them at import time.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
public fun ForgeUiRenderer(
    document: UiDocument,
    state: UiState,
    host: ForgeUiHost,
    modifier: Modifier = Modifier,
) {
    val manifest = state.manifest
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ForgeSpacing.rowGap)) {
        document.title?.let { Text(it, style = ForgeTypography.titleLarge, color = ForgePalette.textPrimary) }
        document.description?.let {
            Text(it, style = ForgeTypography.caption, color = ForgePalette.textSecondary)
        }
        for (block in document.blocks) {
            when (block) {
                is UiBlock.StaticBlock -> StaticBlockView(block)
                is UiBlock.InputBlock -> InputBlockView(block, manifest, state, host)
                is UiBlock.OptionBlock -> OptionBlockView(block, manifest, state)
                is UiBlock.ActionBlock -> ActionBlockView(block, state, host)
                is UiBlock.ProgressBlock -> ProgressBlockView(block, host)
                is UiBlock.LogBlock -> LogBlockView(block, host)
            }
        }
    }
}

// ---- block renderers -------------------------------------------------------

@Composable
private fun StaticBlockView(block: UiBlock.StaticBlock) {
    ForgeCard {
        val body = block.text ?: block.markdown.orEmpty()
        Text(body, style = ForgeTypography.body, color = ForgePalette.textPrimary)
    }
}

@Composable
private fun InputBlockView(
    block: UiBlock.InputBlock,
    manifest: com.forgekit.plugin.manifest.PluginManifest,
    state: UiState,
    host: ForgeUiHost,
) {
    val action = remember(block) { manifest.actions.firstOrNull { it.id == block.action } } ?: return
    val control = remember(block) { action.inputs.firstOrNull { it.id == block.inputId } } ?: return
    ForgeCard {
        SectionHeader(block.label ?: control.label)
        control.description?.let {
            Text(it, style = ForgeTypography.caption, color = ForgePalette.textSecondary)
        }
        when (control.type) {
            "text", "password", "number" -> ForgeTextField(
                value = state.valueOf(block.id).orEmpty(),
                onValueChange = { state.setValue(block.id, it) },
                singleLine = control.type != "text",
                password = control.type == "password",
                mono = control.type != "text",
                keyboardType = if (control.type == "number") KeyboardType.Number else KeyboardType.Text,
            )
            "file", "directory" -> {
                val picked = state.valueOf(block.id)
                ForgePrimaryButton(
                    text = if (picked.isNullOrBlank()) "Select ${control.type}" else "Replace ${control.type}",
                    onClick = { host.onPickFile(block.id, control.id, control.type) },
                )
                picked?.let { pickedPath ->
                    Text(pickedPath, style = ForgeTypography.monoValue, color = ForgePalette.textSecondary)
                }
            }
            "select" -> {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(ForgeSpacing.compact),
                    verticalArrangement = Arrangement.spacedBy(ForgeSpacing.compact),
                ) {
                    for (choice in control.choices) {
                        ForgeChoiceChip(
                            label = choice,
                            selected = state.valueOf(block.id) == choice,
                            onClick = { state.setValue(block.id, choice) },
                        )
                    }
                }
            }
            "checkbox", "switch" -> {
                val on = state.valueOf(block.id) == "true"
                androidx.compose.material3.Switch(
                    checked = on,
                    onCheckedChange = { state.setValue(block.id, if (it) "true" else "false") },
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedTrackColor = ForgePalette.primary,
                        uncheckedTrackColor = ForgePalette.surfaceElevated,
                    ),
                )
            }
            "slider" -> {
                val raw = state.valueOf(block.id) ?: "50"
                val value = (raw.toFloatOrNull() ?: 50f).coerceIn(0f, 100f)
                androidx.compose.material3.Slider(
                    value = value,
                    onValueChange = { state.setValue(block.id, it.toInt().toString()) },
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = ForgePalette.primary,
                        activeTrackColor = ForgePalette.primary,
                        inactiveTrackColor = ForgePalette.outline,
                    ),
                )
            }
            "date", "time" -> ForgeTextField(
                // date/time render as text input with format hint for v1
                value = state.valueOf(block.id).orEmpty(),
                onValueChange = { state.setValue(block.id, it) },
                placeholder = if (control.type == "date") "YYYY-MM-DD" else "HH:MM",
            )
            else -> Unit // parser already rejected unrenderable types
        }
    }
}

@Composable
private fun OptionBlockView(
    block: UiBlock.OptionBlock,
    manifest: com.forgekit.plugin.manifest.PluginManifest,
    state: UiState,
) {
    val action = remember(block) { manifest.actions.firstOrNull { it.id == block.action } } ?: return
    val option = remember(block) { action.options.firstOrNull { it.id == block.optionId } } ?: return
    ForgeCard {
        SectionHeader(block.label ?: option.label)
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ForgeSpacing.compact),
            verticalArrangement = Arrangement.spacedBy(ForgeSpacing.compact),
        ) {
            val current = state.valueOf(block.id) ?: option.default ?: option.choices.firstOrNull()
            for (choice in option.choices) {
                ForgeChoiceChip(
                    label = choice,
                    selected = current == choice,
                    onClick = { state.setValue(block.id, choice) },
                )
            }
        }
    }
}

@Composable
private fun ActionBlockView(
    block: UiBlock.ActionBlock,
    state: UiState,
    host: ForgeUiHost,
) {
    val problems = state.invocationProblems(block.invoke)
    val enabled = problems.isEmpty()
    val label = block.label ?: "Run"
    // `confirm: true` in the document asks before the action starts.
    val confirmGate = rememberConfirmGate()
    Column {
        ForgePrimaryButton(
            text = label,
            enabled = enabled,
            onClick = { if (block.confirm) confirmGate.request() else host.onInvoke(block.invoke) },
        )
        if (!enabled) {
            Text(
                text = problems.first(),
                style = ForgeTypography.caption,
                color = ForgePalette.warning,
                modifier = Modifier.padding(top = ForgeSpacing.micro),
            )
        }
    }
    confirmGate.Dialog(
        title = "$label?",
        message = "This starts the plugin action \"${block.invoke}\" with the values entered above.",
        confirmLabel = label,
        onConfirm = { host.onInvoke(block.invoke) },
    )
}

@Composable
private fun ProgressBlockView(block: UiBlock.ProgressBlock, host: ForgeUiHost) {
    val progress = host.progressOf(block.id) ?: return
    ForgeCard {
        SectionHeader("Progress")
        ForgeProgressBar(fraction = progress)
    }
}

@Composable
private fun LogBlockView(block: UiBlock.LogBlock, host: ForgeUiHost) {
    val lines = host.logLinesOf(block.id)
    if (lines.isEmpty()) return
    val visuals = remember(lines) {
        lines.mapIndexed { index, line -> ForgeLogLineVisual(id = index.toLong(), text = line) }
    }
    ForgeLogConsole(
        lines = visuals,
        title = "Log",
        modifier = Modifier.height(ForgeSpacing.consoleEmbeddedHeight),
    )
}
