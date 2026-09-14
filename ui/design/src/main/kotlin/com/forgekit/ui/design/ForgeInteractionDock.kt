package com.forgekit.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

public enum class ForgePromptKind { CONFIRM, TEXT, PASSWORD, SELECT }

/** Presentation-only prompt; job/protocol ownership stays outside the design module. */
public data class ForgePromptVisual(
    public val key: String,
    public val tabLabel: String,
    public val title: String,
    public val context: String,
    public val kind: ForgePromptKind,
    public val message: String? = null,
    public val required: Boolean = true,
    public val choices: List<String> = emptyList(),
    public val default: String? = null,
    public val placeholder: String? = null,
)

/**
 * Full-width bottom interaction dock. Concurrent jobs become tabs and every
 * response is routed by the caller using the selected prompt's stable key.
 */
@Composable
public fun ForgeInteractionDock(
    prompts: List<ForgePromptVisual>,
    onSubmit: (ForgePromptVisual, String?) -> Unit,
    onCancel: (ForgePromptVisual) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (prompts.isEmpty()) return
    var selectedKey by remember(prompts.map { it.key }) {
        mutableStateOf(prompts.first().key)
    }
    val selected = prompts.firstOrNull { it.key == selectedKey } ?: prompts.first().also {
        selectedKey = it.key
    }
    var value by remember(selected.key) {
        mutableStateOf(
            when (selected.kind) {
                ForgePromptKind.CONFIRM, ForgePromptKind.SELECT -> selected.default
                ForgePromptKind.TEXT, ForgePromptKind.PASSWORD -> selected.default.orEmpty()
            },
        )
    }
    val valid = when (selected.kind) {
        ForgePromptKind.CONFIRM -> value == "true" || value == "false"
        ForgePromptKind.SELECT -> value in selected.choices
        ForgePromptKind.TEXT, ForgePromptKind.PASSWORD -> !selected.required || !value.isNullOrBlank()
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RectangleShape,
        color = ForgePalette.surfaceElevated,
        shadowElevation = 10.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                prompts.forEach { prompt ->
                    val active = prompt.key == selected.key
                    Text(
                        text = prompt.tabLabel,
                        style = ForgeTypography.badge,
                        color = if (active) ForgePalette.onPrimary else ForgePalette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .background(if (active) ForgePalette.primary else ForgePalette.surfaceElevated)
                            .clickable { selectedKey = prompt.key }
                            .padding(horizontal = ForgeSpacing.cardInner, vertical = ForgeSpacing.compact),
                    )
                }
            }
            ForgeDivider()
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(ForgeSpacing.cardInner),
                verticalArrangement = Arrangement.spacedBy(ForgeSpacing.compact),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(selected.title, style = ForgeTypography.labelLarge, color = ForgePalette.textPrimary)
                        Text(selected.context, style = ForgeTypography.monoValue, color = ForgePalette.textMuted)
                    }
                    StatusChip("INPUT REQUIRED", StatusTone.WARNING)
                }
                selected.message?.takeIf(String::isNotBlank)?.let { message ->
                    Text(message, style = ForgeTypography.body, color = ForgePalette.textSecondary)
                }
                when (selected.kind) {
                    ForgePromptKind.CONFIRM -> Row(horizontalArrangement = Arrangement.spacedBy(ForgeSpacing.compact)) {
                        ForgeChoiceChip("Yes", value == "true", { value = "true" }, Modifier.weight(1f))
                        ForgeChoiceChip("No", value == "false", { value = "false" }, Modifier.weight(1f))
                    }
                    ForgePromptKind.SELECT -> Column(verticalArrangement = Arrangement.spacedBy(ForgeSpacing.micro)) {
                        selected.choices.forEach { choice ->
                            ForgeChoiceChip(choice, value == choice, { value = choice }, Modifier.fillMaxWidth())
                        }
                    }
                    ForgePromptKind.TEXT, ForgePromptKind.PASSWORD -> ForgeTextField(
                        value = value.orEmpty(),
                        onValueChange = { value = it },
                        placeholder = selected.placeholder,
                        password = selected.kind == ForgePromptKind.PASSWORD,
                        mono = false,
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ForgeSpacing.compact),
                ) {
                    ForgeSecondaryButton(
                        text = "Cancel prompt",
                        onClick = { onCancel(selected) },
                        modifier = Modifier.weight(1f),
                    )
                    ForgePrimaryButton(
                        text = "Send response",
                        enabled = valid,
                        onClick = { onSubmit(selected, value) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
