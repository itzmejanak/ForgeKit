package com.forgekit.ui.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The `forgekit.ui/v1` document model — what `ui/main.json` inside a .forge
 * package parses into (ARCHITECTURE §14: ui.json → schema → renderer).
 *
 * The document is a list of BLOCKS, not a tree: the renderer lays them out
 * top-to-bottom, each block mapping to exactly one host component. Blocks
 * reference the manifest's action inputs/options by id — the UI document
 * describes PRESENTATION, the manifest owns the CONTRACT (control types,
 * choices, required flags). A document that references an input the action
 * never declared is rejected: no silent input smuggling.
 */
@Serializable
public data class UiDocument(
    @SerialName("schema") public val schema: String,
    @SerialName("title") public val title: String? = null,
    @SerialName("description") public val description: String? = null,
    @SerialName("blocks") public val blocks: List<UiBlock> = emptyList(),
)

@Serializable
public sealed interface UiBlock {
    public val id: String

    /** A control bound to one declared action input. */
    @Serializable
    @SerialName("input")
    public data class InputBlock(
        override val id: String,
        @SerialName("action") public val action: String,
        @SerialName("input") public val inputId: String,
        /** Optional render hint overriding the manifest's label. */
        @SerialName("label") public val label: String? = null,
        @SerialName("helper") public val helper: String? = null,
    ) : UiBlock

    /** A select-type option rendered as a chip group (run draft OPTIONS). */
    @Serializable
    @SerialName("option")
    public data class OptionBlock(
        override val id: String,
        @SerialName("action") public val action: String,
        @SerialName("option") public val optionId: String,
        @SerialName("label") public val label: String? = null,
    ) : UiBlock

    /** Static explanatory content (docs shown before the user acts). */
    @Serializable
    @SerialName("static")
    public data class StaticBlock(
        override val id: String,
        @SerialName("markdown") public val markdown: String? = null,
        @SerialName("text") public val text: String? = null,
    ) : UiBlock

    /** The invoke affordance — the ONLY element that may trigger execution. */
    @Serializable
    @SerialName("action")
    public data class ActionBlock(
        override val id: String,
        @SerialName("invoke") public val invoke: String,
        @SerialName("label") public val label: String? = null,
        @SerialName("confirm") public val confirm: Boolean = false,
    ) : UiBlock

    /** Live progress surface driven by protocol progress events. */
    @Serializable
    @SerialName("progress")
    public data class ProgressBlock(override val id: String) : UiBlock

    /** Live log surface driven by protocol log events (gated §15). */
    @Serializable
    @SerialName("log")
    public data class LogBlock(override val id: String) : UiBlock
}

/**
 * Strict decoder + cross-validator against the manifest.
 * Unknown keys, wrong schema, or references to undeclared actions/inputs
 * are all REJECTED with precise messages — a broken UI document must
 * surface at import time, never at tap time.
 */
public object UiDocumentParser {

    public const val SCHEMA: String = "forgekit.ui/v1"

    /** Control kinds the renderer implements (must match the manifest registry). */
    public val CONTROL_TYPES: Set<String> = setOf(
        "text", "password", "number", "select", "checkbox", "switch",
        "slider", "file", "directory", "date", "time",
    )

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    /** Decodes and fully validates against [manifest]'s action contracts. */
    public fun parse(
        bytes: ByteArray,
        manifest: com.forgekit.plugin.manifest.PluginManifest,
    ): UiDocument {
        val text = bytes.toString(Charsets.UTF_8)
        val document = try {
            json.decodeFromString(UiDocument.serializer(), text)
        } catch (e: Exception) {
            throw com.forgekit.plugin.api.PluginError(
                "ui document is not valid forgekit.ui/v1 JSON",
                e.message?.take(300),
            )
        }
        validate(document, manifest).forEach { problem ->
            throw com.forgekit.plugin.api.PluginError("ui document rejected", problem)
        }
        return document
    }

    /** All validation findings without throwing (validator integration). */
    public fun validate(
        document: UiDocument,
        manifest: com.forgekit.plugin.manifest.PluginManifest,
    ): List<String> {
        val problems = mutableListOf<String>()
        if (document.schema != SCHEMA) {
            problems += "schema must be '$SCHEMA' but was '${document.schema}'"
            return problems
        }
        val actions = manifest.actions.associateBy { it.id }
        val seenIds = HashSet<String>()
        val requiredFor = mutableMapOf<String, MutableSet<String>>() // action -> inputs present

        for (block in document.blocks) {
            if (!block.id.matches(Regex("""^[a-z][a-z0-9_-]*$"""))) {
                problems += "block id malformed: '${block.id}'"
            }
            if (!seenIds.add(block.id)) {
                problems += "duplicate block id: '${block.id}'"
            }
            when (block) {
                is UiBlock.InputBlock -> {
                    val action = actions[block.action]
                    val control = action?.inputs?.firstOrNull { it.id == block.inputId }
                    if (action == null) {
                        problems += "input block '${block.id}' references unknown action '${block.action}'"
                    } else if (control == null) {
                        problems += "input block '${block.id}' references undeclared input '${block.inputId}' of action '${block.action}'"
                    }
                    if (control != null) {
                        if (control.type !in CONTROL_TYPES) {
                            problems += "control type '${control.type}' has no renderer (input '${block.inputId}')"
                        }
                        requiredFor.getOrPut(block.action) { mutableSetOf() }.add(block.inputId)
                    }
                }
                is UiBlock.OptionBlock -> {
                    val action = actions[block.action]
                    val option = action?.options?.firstOrNull { it.id == block.optionId }
                    if (action == null) {
                        problems += "option block '${block.id}' references unknown action '${block.action}'"
                    } else if (option == null) {
                        problems += "option block '${block.id}' references undeclared option '${block.optionId}' of action '${block.action}'"
                    }
                    if (option != null && option.choices.isEmpty()) {
                        problems += "option '${block.optionId}' has no choices"
                    }
                }
                is UiBlock.ActionBlock -> {
                    if (!actions.containsKey(block.invoke)) {
                        problems += "action block '${block.id}' invokes unknown action '${block.invoke}'"
                    }
                }
                is UiBlock.StaticBlock -> {
                    if (block.markdown == null && block.text == null) {
                        problems += "static block '${block.id}' needs text or markdown"
                    }
                }
                is UiBlock.ProgressBlock, is UiBlock.LogBlock -> Unit
            }
        }

        // every REQUIRED input of an invoked action must be rendered somewhere
        for ((actionId, present) in requiredFor) {
            val action = actions[actionId] ?: continue
            for (control in action.inputs) {
                if (control.required && control.id !in present) {
                    problems += "required input '${control.id}' of action '$actionId' has no input block"
                }
            }
        }
        // and each action block may only invoke actions whose required inputs render
        for (block in document.blocks) {
            if (block is UiBlock.ActionBlock) {
                val action = actions[block.invoke] ?: continue
                val rendered = requiredFor[block.invoke] ?: emptySet()
                for (control in action.inputs) {
                    if (control.required && control.id !in rendered) {
                        problems += "action block '${block.id}' would invoke '${block.invoke}' without required input '${control.id}'"
                    }
                }
            }
        }
        return problems
    }
}
