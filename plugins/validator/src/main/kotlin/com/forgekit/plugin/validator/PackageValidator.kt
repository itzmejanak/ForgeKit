package com.forgekit.plugin.validator

import com.forgekit.core.security.PermissionCatalog
import com.forgekit.core.security.PermissionClassification
import com.forgekit.core.security.TrustLevel
import com.forgekit.core.security.TrustStore
import com.forgekit.plugin.installer.ForgePackage

/**
 * Everything the validator needs to know about the environment — injected by
 * the composition root, keeping the module free of runtime/api dependencies
 * (runtime compatibility is judged against this snapshot).
 */
public data class ValidationContext(
    public val trustStore: TrustStore = TrustStore(),
    /** Available runtimes: type → detected version (from RuntimeCapabilities inspection). */
    public val availableRuntimes: Map<String, String> = emptyMap(),
    /** Max accepted package size in bytes (default 256 MiB). */
    public val maxPackageBytes: Long = 256L * 1024 * 1024,
    /** Max accepted entrypoint size (scripts must stay reviewable, default 8 MiB). */
    public val maxEntryBytes: Long = 8L * 1024 * 1024,
    /** Allowed protocol versions (wire values). */
    public val allowedProtocols: List<String> = listOf("forgekit/1"),
)

/**
 * Severity of one validation finding — the import review renders them as
 * warnings vs hard failures (reference UI: WARNINGS list vs rejection).
 */
public enum class Severity { ERROR, WARNING, INFO }

/** One validation finding. */
public data class ValidationFinding(
    public val severity: Severity,
    /** Machine code (CONTENT_UNCLASSIFIED, SIGNATURE_INVALID, …) for the review UI. */
    public val code: String,
    public val message: String,
    public val detail: String? = null,
)

/**
 * The full validation report (§72 steps 2-6): everything the import review
 * screen shows before the user approves a package.
 */
public data class ValidationReport(
    public val pluginId: String,
    public val version: String,
    public val packageSha256: String,
    public val manifestSha256: String,
    public val signaturePresent: Boolean,
    public val trustLevel: TrustLevel,
    public val publisher: com.forgekit.core.security.PublisherIdentity?,
    public val permissionClassification: PermissionClassification?,
    public val dependencyFacts: List<DependencyFact>,
    public val contentWarnings: List<ValidationFinding>,
    public val errors: List<ValidationFinding>,
) {
    public val valid: Boolean get() = errors.isEmpty()

    /** USER approval gate (§25): risky or unknown publishers/permissions. */
    public val requiresUserApproval: Boolean
        get() = trustLevel != TrustLevel.TRUSTED ||
            (permissionClassification?.requiresApproval ?: true)

    /** One dependency line for the DYNAMIC PROVISIONING card (reference UI). */
    public data class DependencyFact(
        public val manager: String,
        public val name: String,
        public val version: String?,
        public val source: String?,
        public val missing: Boolean,
    )
}

/**
 * Full `.forge` package validation (ARCHITECTURE §72):
 * entrypoint presence, UI entry, prohibited content, symlink entries, size
 * limits, signature integrity + trust, permission classification, dependency
 * facts, runtime compatibility.
 *
 * Input: a structurally-opened [ForgePackage] (archive gate already passed).
 * Output: a [ValidationReport] — the single source the import UI renders.
 */
public class PackageValidator(
    private val context: ValidationContext = ValidationContext(),
) {

    public fun validate(pkg: ForgePackage): ValidationReport {
        val errors = mutableListOf<ValidationFinding>()
        val warnings = mutableListOf<ValidationFinding>()
        val manifest = pkg.manifest

        // ---- size gates -----------------------------------------------------
        if (pkg.packageSizeBytes > context.maxPackageBytes) {
            errors += ValidationFinding(
                Severity.ERROR, "PACKAGE_TOO_LARGE",
                "package exceeds the size limit",
                "${pkg.packageSizeBytes} > ${context.maxPackageBytes} bytes",
            )
        }

        // ---- entrypoint + content presence ----------------------------------
        val entry = pkg.findEntry(manifest.entrypoint)
        if (entry == null) {
            errors += ValidationFinding(
                Severity.ERROR, "ENTRYPOINT_MISSING",
                "declared entrypoint is not in the package", manifest.entrypoint,
            )
        } else if (entry.sizeBytes > context.maxEntryBytes) {
            errors += ValidationFinding(
                Severity.ERROR, "ENTRYPOINT_TOO_LARGE",
                "entrypoint exceeds the reviewable size", "${entry.sizeBytes} bytes",
            )
        }
        manifest.ui?.let { ui ->
            if (pkg.findEntry(ui.entry) == null) {
                errors += ValidationFinding(
                    Severity.ERROR, "UI_ENTRY_MISSING",
                    "declared UI document is not in the package", ui.entry,
                )
            } else {
                errors += validateUiSchema(pkg, ui.entry)
            }
        }

        // ---- prohibited / suspicious content --------------------------------
        errors += prohibitedContentFindings(pkg)

        // ---- signature, integrity, trust ------------------------------------
        val trustLevel: TrustLevel
        val publisher: com.forgekit.core.security.PublisherIdentity?
        val signaturePresent = pkg.signature != null
        if (signaturePresent) {
            val signature = pkg.signature!!
            val verifier = com.forgekit.core.security.PackageVerifier()
            val document = pkg.rawHashesDocument
            val files = pkg.readAllFiles().filterKeys {
                it != ForgePackage.HASHES_ENTRY && it != ForgePackage.SIGNATURE_ENTRY && it != ForgePackage.PUBLISHER_ENTRY
            }
            val integrity = when {
                document == null -> "HASHES document missing"
                else -> verifier.verifyIntegrity(document, files) ?: ""
            }
            if (integrity.isNotEmpty()) {
                errors += ValidationFinding(
                    Severity.ERROR, "INTEGRITY_FAILED",
                    "package content does not match its hashes", integrity,
                )
                trustLevel = TrustLevel.UNTRUSTED
                publisher = null
            } else {
                // key source precedence: publisher.json carries the key; the trust
                // store decides whether that key means anything
                val keyBytes = pkg.publisher?.publicKeyBytes()
                val signatureOk = keyBytes != null && verifier.verifySignature(document!!, signature, keyBytes)
                if (!signatureOk) {
                    errors += ValidationFinding(
                        Severity.ERROR, "SIGNATURE_INVALID",
                        "signature does not verify against the publisher key",
                    )
                    trustLevel = TrustLevel.UNTRUSTED
                    publisher = null
                } else {
                    if (context.trustStore.isPinned(signature.keyFingerprint)) {
                        trustLevel = TrustLevel.TRUSTED
                    } else {
                        trustLevel = TrustLevel.UNTRUSTED
                        warnings += ValidationFinding(
                            Severity.WARNING, "PUBLISHER_UNKNOWN",
                            "signed by an unknown publisher", signature.keyFingerprint.take(16) + "…",
                        )
                    }
                    publisher = context.trustStore.publisher(signature.keyFingerprint)
                        ?: com.forgekit.core.security.PublisherIdentity(
                            keyFingerprint = signature.keyFingerprint,
                            displayName = pkg.publisher?.displayName,
                            contact = pkg.publisher?.contact,
                        )
                    // publisher.json key must match the signature fingerprint
                    if (keyBytes != null) {
                        val actualFingerprint = com.forgekit.core.security.CanonicalHashManifest.hashHex(keyBytes)
                        if (actualFingerprint != signature.keyFingerprint) {
                            errors += ValidationFinding(
                                Severity.ERROR, "PUBLISHER_KEY_MISMATCH",
                                "publisher.json key does not match the signing key",
                            )
                        }
                    }
                }
            }
        } else {
            trustLevel = TrustLevel.UNTRUSTED
            publisher = null
            warnings += ValidationFinding(
                Severity.WARNING, "UNSIGNED",
                "package is unsigned",
            )
        }

        // ---- permissions ------------------------------------------------------
        val classification = PermissionCatalog.classify(manifest.permissions)
        classification.unknown.forEach { unknown ->
            errors += ValidationFinding(
                Severity.ERROR, "PERMISSION_UNKNOWN",
                "requests a permission that does not exist", unknown,
            )
        }
        if (classification.maxRisk == PermissionCatalog.Risk.SYSTEM) {
            warnings += ValidationFinding(
                Severity.WARNING, "PERMISSION_SYSTEM",
                "requests system-level permissions", classification.summary(),
            )
        }

        // ---- runtime compatibility ---------------------------------------------
        val available = context.availableRuntimes[manifest.runtime.type]
        if (context.availableRuntimes.isNotEmpty()) { // empty = not inspectable here
            if (available == null) {
                warnings += ValidationFinding(
                    Severity.WARNING, "RUNTIME_NOT_INSTALLED",
                    "runtime '${manifest.runtime.type}' is not installed in the embedded runtime yet",
                    "resolver will provision it (termux: ${manifest.dependencies.termux.joinToString()})",
                )
            } else {
                manifest.runtime.version?.let { requirement ->
                    if (!satisfies(available, requirement)) {
                        errors += ValidationFinding(
                            Severity.ERROR, "RUNTIME_INCOMPATIBLE",
                            "runtime '${manifest.runtime.type}' version $available does not satisfy '$requirement'",
                        )
                    }
                }
            }
        }

        // ---- dependency facts (DYNAMIC PROVISIONING card) ------------------------
        val dependencyFacts = buildDependencyFacts(pkg, available)

        return ValidationReport(
            pluginId = manifest.id,
            version = manifest.version,
            packageSha256 = pkg.packageSha256,
            manifestSha256 = pkg.manifestSha256,
            signaturePresent = signaturePresent,
            trustLevel = trustLevel,
            publisher = publisher,
            permissionClassification = classification,
            dependencyFacts = dependencyFacts,
            contentWarnings = warnings,
            errors = errors,
        )
    }

    // ---- individual passes ---------------------------------------------------

    /** Minimal forgekit.ui/v1 structural check (full renderer arrives in ui modules). */
    private fun validateUiSchema(pkg: ForgePackage, entry: String): List<ValidationFinding> {
        val findings = mutableListOf<ValidationFinding>()
        val text = runCatching {
            pkg.readAllFiles()[entry]?.toString(Charsets.UTF_8)
        }.getOrNull() ?: return listOf(
            ValidationFinding(Severity.ERROR, "UI_UNREADABLE", "UI document unreadable", entry),
        )
        val json = try {
            kotlinx.serialization.json.Json.parseToJsonElement(text)
        } catch (e: Exception) {
            return listOf(ValidationFinding(Severity.ERROR, "UI_INVALID_JSON", "UI document is not JSON", e.message))
        }
        val schema = (json as? kotlinx.serialization.json.JsonObject)?.get("schema")?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        }
        if (schema != "forgekit.ui/v1") {
            findings += ValidationFinding(
                Severity.ERROR, "UI_SCHEMA_UNKNOWN",
                "UI document schema must be 'forgekit.ui/v1'", "was '$schema'",
            )
        }
        return findings
    }

    /** Prohibited content: symlink entries, ELF binaries outside bin/, hidden files. */
    private fun prohibitedContentFindings(pkg: ForgePackage): List<ValidationFinding> {
        val findings = mutableListOf<ValidationFinding>()
        val modes = runCatching {
            com.forgekit.plugin.installer.ZipModeReader.readModes(pkg.file)
        }.getOrDefault(emptyMap())
        for (packaged in pkg.entries) {
            val mode = modes[packaged.path] ?: continue
            val fileType = mode and 0xF000
            if (fileType == 0xA000) { // S_IFLNK: plugin packages must not carry symlinks
                findings += ValidationFinding(
                    Severity.ERROR, "PROHIBITED_SYMLINK",
                    "package contains a symlink entry", packaged.path,
                )
            }
        }

        for (packaged in pkg.entries) {
            val path = packaged.path
            if (path.split('/').any { it.startsWith(".") } && path != ".forgekit-ready") {
                findings += ValidationFinding(
                    Severity.WARNING, "CONTENT_HIDDEN",
                    "hidden file in package", path,
                )
            }
            // ELF payloads outside runtime/ or bin/ are suspicious (screenshots:
            // scripts/ssl.py CONTENT_UNCLASSIFIED warning class)
            if (!path.startsWith("runtime/") && !path.startsWith("bin/") && path.endsWith(".so")) {
                findings += ValidationFinding(
                    Severity.WARNING, "CONTENT_UNCLASSIFIED",
                    "native library outside runtime/ or bin/", path,
                )
            }
        }
        return findings
    }

    private fun buildDependencyFacts(
        pkg: ForgePackage,
        availableRuntimeVersion: String?,
    ): List<ValidationReport.DependencyFact> {
        val facts = mutableListOf<ValidationReport.DependencyFact>()
        val deps = pkg.manifest.dependencies
        deps.termux.forEach { name ->
            facts += ValidationReport.DependencyFact(
                manager = "TERMUX", name = name, version = null,
                source = "termux-main", missing = true,
            )
        }
        deps.python.forEach { dep ->
            facts += ValidationReport.DependencyFact(
                manager = "PIP", name = dep.name, version = dep.version,
                source = dep.source ?: "pypi", missing = true,
            )
        }
        deps.node.forEach { dep ->
            facts += ValidationReport.DependencyFact(
                manager = "NPM", name = dep.name, version = dep.version,
                source = dep.source ?: "npm", missing = true,
            )
        }
        if (availableRuntimeVersion == null) {
            facts += ValidationReport.DependencyFact(
                manager = "TERMUX", name = pkg.manifest.runtime.type, version = null,
                source = "termux-main", missing = true,
            )
        }
        return facts
    }

    /** Version satisfaction with a tolerant prefix match (3.11.4 satisfies >=3.11). */
    private fun satisfies(available: String, requirement: String): Boolean = try {
        com.forgekit.core.model.Version(padToFull(available)).satisfies(requirement)
    } catch (_: IllegalArgumentException) {
        // non-semver runtime reports (e.g. "Python 3.11.4") — compare loosely
        val digits = Regex("""\d+\.\d+(\.\d+)?""").find(available)?.value ?: return false
        com.forgekit.core.model.Version(padToFull(digits)).satisfies(requirement)
    }

    private fun padToFull(raw: String): String {
        val digits = Regex("""\d+(\.\d+){0,2}""").find(raw)?.value ?: raw
        val parts = digits.split('.')
        return when (parts.size) {
            1 -> "${parts[0]}.0.0"
            2 -> "${parts[0]}.${parts[1]}.0"
            else -> digits
        }
    }
}
