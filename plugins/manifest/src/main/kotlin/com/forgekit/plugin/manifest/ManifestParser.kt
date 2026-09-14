package com.forgekit.plugin.manifest

import com.forgekit.core.common.ForgeContracts
import com.forgekit.plugin.api.PluginError
import com.forgekit.plugin.api.PluginId
import com.forgekit.plugin.api.PluginVersion
import kotlinx.serialization.json.Json

/** Structural validation outcome: precise problems, never silent passes. */
public data class ManifestValidation(
    public val valid: Boolean,
    public val problems: List<String>,
) {
    public fun requireValid(): Unit {
        if (!valid) {
            throw PluginError(
                "manifest validation failed (${problems.size} problems)",
                problems.joinToString("; ").take(600),
            )
        }
    }
}

/**
 * Strict parser + validator for `forgekit.plugin/v1` (ARCHITECTURE §13, §72).
 *
 * Strictness policy: unknown top-level keys are REJECTED (schemas are frozen
 * contracts, typos must fail loudly), every declared path must stay inside
 * the package and use the reserved area names.
 */
public class ManifestParser(
    private val json: Json = Json {
        ignoreUnknownKeys = false
        prettyPrint = false
        encodeDefaults = false
    },
) {

    /** Parses raw manifest bytes; JSON or schema failures throw [PluginError]. */
    public fun parse(bytes: ByteArray): PluginManifest {
        val text = bytes.toString(Charsets.UTF_8)
        val manifest = try {
            json.decodeFromString(PluginManifest.serializer(), text)
        } catch (e: Exception) {
            throw PluginError("manifest.json is not valid forgekit.plugin/v1 JSON", e.message)
        }
        validate(manifest).requireValid()
        return manifest
    }

    /** Structural validation of a parsed manifest (deterministic problem list). */
    public fun validate(manifest: PluginManifest): ManifestValidation {
        val problems = mutableListOf<String>()

        if (manifest.schema != ForgeContracts.MANIFEST_SCHEMA) {
            problems += "schema must be '${ForgeContracts.MANIFEST_SCHEMA}', was '${manifest.schema}'"
        }
        problems += idProblems(manifest.id)
        if (manifest.name.isBlank()) problems += "name must not be blank"
        problems += versionProblems(manifest.version)
        problems += runtimeProblems(manifest.runtime)
        problems += pathProblems("entrypoint", manifest.entrypoint, mustBeUnder = "runtime")
        manifest.ui?.let { problems += pathProblems("ui.entry", it.entry, mustBeUnder = "ui") }

        problems += permissionsProblems(manifest.permissions)
        problems += dependencyProblems(manifest.dependencies)
        problems += actionsProblems(manifest.actions)

        return ManifestValidation(problems.isEmpty(), problems)
    }

    // ---- individual checks ---------------------------------------------------

    private fun idProblems(id: String): List<String> = try {
        PluginId.parse(id)
        emptyList()
    } catch (e: PluginError) {
        listOf(e.message)
    }

    private fun versionProblems(version: String): List<String> = try {
        PluginVersion.parse(version)
        emptyList()
    } catch (e: PluginError) {
        listOf(e.message)
    }

    private fun runtimeProblems(runtime: PluginManifest.RuntimeRequirement): List<String> {
        val problems = mutableListOf<String>()
        val allowed = setOf("python", "node", "bash", "binary")
        if (runtime.type !in allowed) {
            problems += "runtime.type must be one of ${allowed.sorted()} (was '${runtime.type}')"
        }
        runtime.version?.let { v ->
            if (Regex("""^(>=|<=|>|<|=)?\s*\d+(\.\d+){0,2}$""").matches(v.trim()).not()) {
                problems += "runtime.version requirement malformed: '$v'"
            }
        }
        return problems
    }

    private fun pathProblems(field: String, path: String, mustBeUnder: String): List<String> {
        val problems = mutableListOf<String>()
        if (path.isBlank() || path.startsWith("/") || path.contains("..") || path.contains('\\')) {
            problems += "$field must be a relative path inside the package: '$path'"
        } else if (path.substringBefore('/') != mustBeUnder) {
            problems += "$field must live under '$mustBeUnder/' (was '$path')"
        }
        return problems
    }

    private fun permissionsProblems(permissions: List<String>): List<String> {
        val problems = mutableListOf<String>()
        val seen = HashSet<String>()
        for (permission in permissions) {
            if (!permission.matches(Regex("""^[a-z]+(\.[a-z]+)*$"""))) {
                problems += "permission malformed (lowercase dotted): '$permission'"
            }
            if (!seen.add(permission)) problems += "duplicate permission: '$permission'"
        }
        return problems
    }

    private fun dependencyProblems(deps: PluginManifest.Dependencies): List<String> {
        val problems = mutableListOf<String>()
        val seenTermux = HashSet<String>()
        for (termux in deps.termux) {
            if (!termux.matches(Regex("""^[a-z0-9][a-z0-9+.-]*$"""))) {
                problems += "termux dependency malformed: '$termux'"
            }
            if (!seenTermux.add(termux)) problems += "duplicate termux dependency: '$termux'"
        }
        for (dep in deps.python) {
            if (!dep.name.matches(Regex("""^[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?$"""))) {
                problems += "python dependency malformed: '${dep.name}'"
            }
            dep.version?.let { v ->
                if (!Regex("""^(==|>=|<=|>|<|=)\s*[\w.]+$""").matches(v.trim())) {
                    problems += "python dependency '${dep.name}' version pin malformed: '$v'"
                }
            }
        }
        for (dep in deps.node) {
            if (dep.name.isBlank()) problems += "node dependency name blank"
            dep.version?.let { v ->
                if (!Regex("""^(~|\^|==|>=|<=|>|<|=)?\s*[\w.]+$""").matches(v.trim())) {
                    problems += "node dependency '${dep.name}' version pin malformed: '$v'"
                }
            }
        }
        return problems
    }

    private fun actionsProblems(actions: List<PluginManifest.ActionDeclaration>): List<String> {
        val problems = mutableListOf<String>()
        val seenIds = HashSet<String>()
        val controlTypes = setOf(
            "text", "password", "number", "select", "checkbox", "switch",
            "slider", "file", "directory", "date", "time",
        )
        for (action in actions) {
            if (!action.id.matches(Regex("""^[a-z][a-z0-9_-]*$"""))) {
                problems += "action id malformed (lowercase, dashes): '${action.id}'"
            }
            if (action.title.isBlank()) problems += "action '${action.id}' title blank"
            if (!seenIds.add(action.id)) problems += "duplicate action id: '${action.id}'"
            val seenControls = HashSet<String>()
            for (input in action.inputs) {
                if (!input.id.matches(Regex("""^[a-z][a-z0-9_-]*$"""))) {
                    problems += "action '${action.id}' input id malformed: '${input.id}'"
                }
                if (input.type !in controlTypes) {
                    problems += "action '${action.id}' input '${input.id}' type unknown: '${input.type}'"
                }
                if (input.type == "select" && input.choices.isEmpty()) {
                    problems += "action '${action.id}' select input '${input.id}' needs choices"
                }
                if (!seenControls.add(input.id)) {
                    problems += "action '${action.id}' duplicate input id: '${input.id}'"
                }
            }
            val seenOptions = HashSet<String>()
            for (option in action.options) {
                if (!option.id.matches(Regex("""^[a-z][a-z0-9_-]*$"""))) {
                    problems += "action '${action.id}' option id malformed: '${option.id}'"
                }
                if (option.choices.isEmpty()) {
                    problems += "action '${action.id}' option '${option.id}' needs choices"
                }
                if (!seenOptions.add(option.id)) {
                    problems += "action '${action.id}' duplicate option id: '${option.id}'"
                }
            }
            for (output in action.outputs) {
                if (!output.id.matches(Regex("""^[a-z][a-z0-9_-]*$"""))) {
                    problems += "action '${action.id}' output id malformed: '${output.id}'"
                }
                val allowedOutputs = setOf("file", "directory", "value", "table")
                if (output.type !in allowedOutputs) {
                    problems += "action '${action.id}' output '${output.id}' type unknown: '${output.type}'"
                }
            }
        }
        return problems
    }
}
