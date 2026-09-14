package com.forgekit.plugin.manifest

import com.forgekit.core.common.ForgeContracts
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * `forgekit.plugin/v1` manifest schema (ARCHITECTURE §13) — the declarative
 * contract every `.forge` package must satisfy.
 *
 * The manifest tells ForgeKit what a plugin REQUIRES; it never grants anything
 * (§13: "It does not grant itself permissions").
 */
@Serializable
public data class PluginManifest(
    /** MUST be exactly `forgekit.plugin/v1`. */
    public val schema: String,
    /** Reverse-domain plugin id (validated on parse). */
    public val id: String,
    public val name: String,
    /** Semantic version string. */
    public val version: String,
    public val description: String? = null,
    public val author: String? = null,
    public val tags: List<String> = emptyList(),
    /** Runtime language + version requirement, e.g. python >= 3.11. */
    public val runtime: RuntimeRequirement,
    /** Runtime-rooted entry script, e.g. `runtime/main.py`. */
    public val entrypoint: String,
    /** Optional declarative UI entry (`ui/main.json`). */
    public val ui: UiDeclaration? = null,
    /** Declared dependency groups (termux / python / node / …). */
    public val dependencies: Dependencies = Dependencies(),
    /** REQUESTED permissions (the policy engine decides what they mean). */
    public val permissions: List<String> = emptyList(),
    /** Runnable actions with typed inputs/options/outputs (reference UI: ACTIONS card). */
    public val actions: List<ActionDeclaration> = emptyList(),
) {
    @Serializable
    public data class RuntimeRequirement(
        /** Lowercase runtime family: python, node, bash, binary. */
        public val type: String,
        /** Version requirement, e.g. `>=3.11`; null = any. */
        public val version: String? = null,
    )

    @Serializable
    public data class UiDeclaration(
        /** Path to the forgekit.ui/v1 document inside the package. */
        public val entry: String,
    )

    @Serializable
    public data class Dependencies(
        /** Termux packages (pkg/apt names). */
        @SerialName("termux")
        public val termux: List<String> = emptyList(),
        /** Python packages (pip names, optionally pinned `==1.2.0`). */
        @SerialName("python")
        public val python: List<PythonDependency> = emptyList(),
        /** Node packages (npm names, optionally pinned). */
        @SerialName("node")
        public val node: List<NodeDependency> = emptyList(),
    )

    @Serializable
    public data class PythonDependency(
        public val name: String,
        /** Pin, e.g. `==1.9.8`; null = latest acceptable. */
        public val version: String? = null,
        /** Where the package must come from (pypi default). */
        public val source: String? = null,
    )

    @Serializable
    public data class NodeDependency(
        public val name: String,
        public val version: String? = null,
        public val source: String? = null,
    )

    @Serializable
    public data class ActionDeclaration(
        /** Stable action id used in protocol invokes. */
        public val id: String,
        /** Human title. */
        public val title: String,
        public val description: String? = null,
        /** Typed inputs the action consumes. */
        public val inputs: List<ControlDeclaration> = emptyList(),
        /** Pre-baked options the user picks in the run draft (chip groups). */
        public val options: List<OptionDeclaration> = emptyList(),
        /** Declared outputs (metadata for the artifacts view). */
        public val outputs: List<OutputDeclaration> = emptyList(),
    )

    /**
     * One typed input control. Control kinds follow the forgekit.ui/v1 control
     * registry (text, password, number, select, checkbox, switch, slider,
     * file, directory, date, time).
     */
    @Serializable
    public data class ControlDeclaration(
        public val id: String,
        public val label: String,
        /** Control kind, e.g. `text`, `file`, `select`. */
        public val type: String,
        public val required: Boolean = false,
        /** For select-type controls: allowed choices. */
        public val choices: List<String> = emptyList(),
        /** Value constraints (min/max/pattern) depending on type. */
        public val constraints: JsonObject? = null,
        public val description: String? = null,
    )

    /** An option shown as a chip group in the run draft (reference UI: OPTIONS cards). */
    @Serializable
    public data class OptionDeclaration(
        public val id: String,
        public val label: String,
        /** Option kind (select-type in v1). */
        public val type: String = "select",
        public val choices: List<String> = emptyList(),
        public val default: String? = null,
        /** Pass-through flag: how the choice maps to the protocol input. */
        public val mapsTo: String? = null,
    )

    /** Declared output artifact. */
    @Serializable
    public data class OutputDeclaration(
        public val id: String,
        public val label: String? = null,
        /** Output kind: file, directory, value, table. */
        public val type: String,
        public val description: String? = null,
    )

    public companion object {
        public const val SCHEMA: String = ForgeContracts.MANIFEST_SCHEMA
    }
}
