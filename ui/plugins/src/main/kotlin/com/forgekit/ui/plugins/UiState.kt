package com.forgekit.ui.plugins

import com.forgekit.plugin.manifest.PluginManifest

/**
 * Live state of a rendered [UiDocument]: the current value per input and
 * per option block, plus the validation the host enforces BEFORE an
 * action may be invoked. This is the bridge between the declarative UI
 * and the forgekit/1 protocol `input` payload — nothing else may invent
 * input values.
 */
public class UiState(
    /** The manifest whose actions are rendered. */
    public val manifest: PluginManifest,
    /** The document being rendered. */
    public val document: UiDocument,
) {
    /** Raw rendered values keyed by BLOCK id (never by control id — blocks are the UI truth). */
    private val values = LinkedHashMap<String, String>()

    /** Marker for file/directory selections pending a real content URI grant. */
    private val pendingFiles = HashMap<String, Boolean>()

    public fun setValue(blockId: String, value: String) {
        values[blockId] = value
    }

    public fun valueOf(blockId: String): String? = values[blockId]

    /** Marks a file/directory input as selected-but-not-yet-resolved. */
    public fun setFilePending(blockId: String, pending: Boolean) {
        pendingFiles[blockId] = pending
    }

    /**
     * Validation for invoking [actionId]: every required input block bound
     * to that action has a non-blank value; every select value is one of the
     * declared choices; no file input is still pending. Returns the list of
     * violations — empty means invocable.
     */
    public fun invocationProblems(actionId: String): List<String> {
        val problems = mutableListOf<String>()
        val action = manifest.actions.firstOrNull { it.id == actionId }
            ?: return listOf("unknown action '$actionId'")

        val inputBlocks = document.blocks.filterIsInstance<UiBlock.InputBlock>()
            .filter { it.action == actionId }
        val optionBlocks = document.blocks.filterIsInstance<UiBlock.OptionBlock>()
            .filter { it.action == actionId }

        for (block in inputBlocks) {
            val control = action.inputs.firstOrNull { it.id == block.inputId } ?: continue
            val value = values[block.id]
            when {
                control.required && value.isNullOrBlank() ->
                    problems += "input '${control.id}' (${control.type}) is required"
                control.type == "select" && !value.isNullOrEmpty() &&
                    value !in control.choices ->
                    problems += "value '$value' for '${control.id}' is not a declared choice"
                control.type == "number" && !value.isNullOrEmpty() && value.toDoubleOrNull() == null ->
                    problems += "input '${control.id}' must be numeric"
            }
            if (pendingFiles[block.id] == true) {
                problems += "file for '${control.id}' is still being resolved"
            }
        }
        for (block in optionBlocks) {
            val option = action.options.firstOrNull { it.id == block.optionId } ?: continue
            val value = values[block.id] ?: option.default
            if (value != null && value !in option.choices) {
                problems += "value '$value' for option '${option.id}' is not a declared choice"
            }
        }
        return problems
    }

    /**
     * The exact `input` payload for a forgekit/1 invoke on [actionId]:
     * control-id → value for inputs, option-id → effective value for
     * options (default applied when the user never touched the chips).
     * Throws if [invocationProblems] is non-empty — the host must gate.
     */
    public fun inputPayload(actionId: String): Map<String, String> {
        val problems = invocationProblems(actionId)
        if (problems.isNotEmpty()) {
            throw IllegalStateException("action not invocable: ${problems.joinToString("; ")}")
        }
        val action = manifest.actions.first { it.id == actionId }
        val payload = LinkedHashMap<String, String>()

        for (block in document.blocks.filterIsInstance<UiBlock.InputBlock>().filter { it.action == actionId }) {
            val control = action.inputs.firstOrNull { it.id == block.inputId } ?: continue
            payload[control.id] = values[block.id].orEmpty()
        }
        for (block in document.blocks.filterIsInstance<UiBlock.OptionBlock>().filter { it.action == actionId }) {
            val option = action.options.firstOrNull { it.id == block.optionId } ?: continue
            payload[option.id] = values[block.id] ?: option.default ?: option.choices.first()
        }
        return payload
    }

    /** Snapshot for state restoration (process death, screen rotation). */
    public fun snapshot(): Map<String, String> = LinkedHashMap(values)

    public fun restore(snapshot: Map<String, String>) {
        values.clear()
        values.putAll(snapshot)
    }
}
