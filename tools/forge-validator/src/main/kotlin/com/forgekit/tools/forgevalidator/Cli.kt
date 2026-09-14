package com.forgekit.tools.forgevalidator

import com.forgekit.core.security.TrustStore
import com.forgekit.core.model.ForgeError
import com.forgekit.plugin.installer.ForgePackage
import com.forgekit.plugin.manifest.ManifestParser
import com.forgekit.plugin.validator.PackageValidator
import com.forgekit.plugin.validator.ValidationContext
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * `forge inspect <package.forge>` — the CLI face of the §72 validation gate.
 *
 * Prints the same facts the import review renders (identity, content,
 * permissions, dependencies, trust) as deterministic text for humans and
 * CI. Exit code 0 = valid, 1 = rejected, 2 = usage error — so pipelines
 * can gate on it.
 */
public object Cli {

    @JvmStatic
    public fun main(args: Array<String>) {
        exitProcess(run(args))
    }

    /** The testable core: returns the process exit code without exiting. */
    public fun run(args: Array<String>): Int {
        if (args.isEmpty() || args[0] != "inspect" || args.size != 2) {
            System.err.println("usage: forge inspect <package.forge>")
            return 2
        }
        val file = Path.of(args[1])
        val report = try {
            val pkg = ForgePackage.open(file, ManifestParser())
            PackageValidator(ValidationContext(trustStore = TrustStore())).validate(pkg)
        } catch (e: ForgeError) {
            System.err.println("REJECTED: ${e.message}")
            e.detail?.let { System.err.println("  $it") }
            return 1
        }

        render(file, report).forEach(::println)
        println()
        if (!report.valid) {
            println("VERDICT: REJECTED")
            return 1
        }
        println("VERDICT: VALID${if (report.requiresUserApproval) " (requires user approval)" else ""}")
        return 0
    }

    /** Deterministic, line-oriented report — the diffable artifact. */
    public fun render(
        file: Path,
        report: com.forgekit.plugin.validator.ValidationReport,
    ): List<String> {
        val lines = mutableListOf<String>()
        lines += "package: ${file.fileName}"
        lines += "plugin: ${report.pluginId} @ ${report.version}"
        lines += "sha256: ${report.packageSha256}"
        lines += "manifest-sha256: ${report.manifestSha256}"
        lines += "signature: " + when {
            report.signaturePresent && report.trustLevel == com.forgekit.core.security.TrustLevel.TRUSTED -> "SIGNED_TRUSTED"
            report.signaturePresent -> "SIGNED_UNKNOWN"
            else -> "UNSIGNED"
        }
        report.publisher?.let { lines += "publisher: ${it.displayName ?: it.keyFingerprint.take(16)} (${it.keyFingerprint.take(16)}…)" }

        if (report.dependencyFacts.isNotEmpty()) {
            lines += "dependencies:"
            for (dep in report.dependencyFacts) {
                lines += "  ${dep.manager} ${dep.name}${dep.version?.let { " $it" } ?: ""}${dep.source?.let { " source $it" } ?: ""} ${if (dep.missing) "MISSING" else "OK"}"
            }
        }
        report.permissionClassification?.let { classification ->
            if (classification.known.isNotEmpty()) {
                lines += "permissions (risk ${classification.maxRisk}):"
                for (permission in classification.known) {
                    lines += "  ${permission.id} [${permission.risk}] ${permission.description}"
                }
            }
            for (unknown in classification.unknown) {
                lines += "  $unknown [UNKNOWN]"
            }
            lines += "requires-approval: ${classification.requiresApproval}"
        }
        if (report.contentWarnings.isNotEmpty()) {
            lines += "warnings:"
            for (warning in report.contentWarnings) {
                lines += "  ${warning.code}: ${warning.message}${warning.detail?.let { " ($it)" } ?: ""}"
            }
        }
        if (report.errors.isNotEmpty()) {
            lines += "errors:"
            for (error in report.errors) {
                lines += "  ${error.code}: ${error.message}${error.detail?.let { " ($it)" } ?: ""}"
            }
        }
        return lines
    }
}
