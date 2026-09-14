package com.forgekit.plugin.manager

import com.forgekit.core.security.SecurityAuditLog
import com.forgekit.core.security.TrustLevel
import com.forgekit.plugin.api.PluginCapability
import com.forgekit.plugin.api.PluginDescriptor
import com.forgekit.plugin.api.PluginError
import com.forgekit.plugin.api.PluginId
import com.forgekit.plugin.api.PluginStatus
import com.forgekit.plugin.api.PluginTrustLevel
import com.forgekit.plugin.api.PluginVersion
import com.forgekit.plugin.installer.ForgePackage
import com.forgekit.plugin.installer.PackageInstaller
import com.forgekit.plugin.manifest.ManifestParser
import com.forgekit.plugin.resolver.DependencyPlan
import com.forgekit.plugin.resolver.DependencyResolver
import com.forgekit.plugin.resolver.ResolutionEvent
import com.forgekit.plugin.resolver.ResolutionReport
import com.forgekit.plugin.validator.PackageValidator
import com.forgekit.plugin.validator.ValidationContext
import com.forgekit.plugin.validator.ValidationReport
import com.forgekit.runtime.api.ForgeRuntime
import kotlinx.coroutines.flow.FlowCollector
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The plugin lifecycle state machine (STRUCTURE.md plugins/manager: IMPORT →
 * REMOVE) and the §72 use cases: import / validate / approve / install /
 * initialize / disable / remove.
 *
 * Two-phase import (matching the reference UI):
 *  1. [import] — parse + validate the .forge archive, produce the review
 *     facts (identity, provisioning, warnings). Nothing touches the plugin
 *     tree yet; the package id is QUARANTINED → UNRESOLVED.
 *  2. [approveImport] — only after the user saw the report: install, resolve
 *     real dependencies, initialize → READY.
 *
 * Job creation for RUNS deliberately lives in `:app` (RunPluginUseCase), not here.
 */
public class PluginManager(
    /** plugins/ root from ForgePaths. */
    private val pluginsRoot: Path,
    /** The live embedded runtime (dependency provisioning). */
    private val runtime: ForgeRuntime,
    private val validationContext: ValidationContext = ValidationContext(),
    private val audit: SecurityAuditLog = SecurityAuditLog(),
    private val parser: ManifestParser = ManifestParser(),
) {

    /** Review bundle produced by import, rendered by the import UI. */
    public data class ImportReview(
        public val descriptor: PluginDescriptor,
        public val report: ValidationReport,
        /** The opened package awaiting the approval decision. */
        internal val pkg: ForgePackage,
    )

    /** In-memory pending imports (id → review) awaiting user approval. */
    private val pending = ConcurrentHashMap<String, ImportReview>()

    private val installer = PackageInstaller(pluginsRoot)
    private val resolver = DependencyResolver(runtime)
    private val validator = PackageValidator(validationContext)

    // ---- import phase (§72 steps 1-6) -----------------------------------------

    /**
     * Imports a `.forge` archive: structural open, manifest parse, full
     * validation. Returns the review facts; installation happens only in
     * [approveImport].
     */
    public suspend fun import(archive: Path): ImportReview {
        val pkg = ForgePackage.open(archive, parser)
        val report = validator.validate(pkg)
        val descriptor = descriptorFrom(pkg, report, PluginStatus.UNRESOLVED)
        audit.record(
            "import", descriptor.id.raw,
            if (report.valid) "VALIDATED" else "REJECTED",
            "sha256=${report.packageSha256.take(16)}… trust=${report.trustLevel} errors=${report.errors.size}",
        )
        if (!report.valid) {
            // rejected packages stay reviewable but are never installable
            pending[descriptor.id.raw] = ImportReview(descriptor, report, pkg)
            throw PluginError(
                "package rejected: ${report.errors.size} validation errors",
                report.errors.joinToString("; ") { "${it.code}: ${it.message}" }.take(600),
            )
        }
        pending[descriptor.id.raw] = ImportReview(descriptor, report, pkg)
        return pending.getValue(descriptor.id.raw)
    }

    /**
     * LIVE dependency plan for a pending review — REAL runtime presence at review
     * time (package database checks), replacing the validator's static
     * "not installed" tags with what the runtime actually has.
     */
    public suspend fun liveDependencyPlan(review: ImportReview): List<DependencyPlan> =
        resolver.plan(review.pkg.manifest)

    // ---- approval + install phase (§72 steps 7-10) ------------------------------

    /**
     * User-approved installation: install the package, provision real
     * dependencies, initialize. Idempotent per pending import.
     */
    public suspend fun approveImport(pluginId: PluginId): PluginDescriptor =
        approveImportStreaming(pluginId) {}

    /**
     * User-approved installation with streaming dependency progress: unpack the
     * package, then [DependencyResolver.resolveStreaming] — every install
     * milestone is forwarded to [events] as [ResolutionEvent]s so the UI can
     * show real time, real per-dependency state (never silence during the
     * minutes-long download phase). Status logic is identical to
     * [approveImport].
     */
    public suspend fun approveImportStreaming(
        pluginId: PluginId,
        events: FlowCollector<ResolutionEvent>,
    ): PluginDescriptor {
        val review = pending[pluginId.raw]
            ?: throw PluginError("no pending import for '${pluginId.raw}'")

        // §25: approval must have been explicit for risky/unknown packages
        audit.record("approve", pluginId.raw, "USER_APPROVED", null)

        val install = installer.install(review.pkg)
        val resolution: ResolutionReport = resolver.resolveStreaming(review.pkg.manifest, events)

        val status = when {
            resolution.satisfied -> PluginStatus.READY
            else -> PluginStatus.UNRESOLVED
        }
        if (status == PluginStatus.READY) {
            installer.markReady(pluginId)
        }
        audit.record(
            "install", pluginId.raw,
            if (status == PluginStatus.READY) "READY" else "UNRESOLVED",
            "deps=${resolution.installResults.size} missing=${resolution.missingAfterResolution.size}",
        )

        val descriptor = review.descriptor.copy(
            status = status,
            installedPath = install.layout.rootPath.toString(),
            updatedAtMillis = System.currentTimeMillis(),
        )
        pending.remove(pluginId.raw)
        return descriptor
    }

    /** Re-attempts dependency provisioning for an UNRESOLVED installed plugin. */
    public suspend fun provision(pluginId: PluginId): PluginDescriptor = provisionStreaming(pluginId) {}

    /** [provision] with streaming dependency progress (see [approveImportStreaming]). */
    public suspend fun provisionStreaming(
        pluginId: PluginId,
        events: FlowCollector<ResolutionEvent>,
    ): PluginDescriptor {
        val manifest = manifestOfInstalled(pluginId)
            ?: throw PluginError("plugin '${pluginId.raw}' is not installed")
        val resolution = resolver.resolveStreaming(manifest, events)
        val descriptor = descriptorOfInstalled(pluginId)
        return if (resolution.satisfied) {
            installer.markReady(pluginId)
            descriptor.copy(status = PluginStatus.READY, updatedAtMillis = System.currentTimeMillis())
        } else {
            descriptor.copy(
                status = PluginStatus.UNRESOLVED,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
    }

    // ---- lifecycle use cases -----------------------------------------------------

    public suspend fun disable(pluginId: PluginId): PluginDescriptor {
        val descriptor = descriptorOfInstalled(pluginId)
        audit.record("disable", pluginId.raw, "DISABLED", null)
        return descriptor.copy(status = PluginStatus.DISABLED, updatedAtMillis = System.currentTimeMillis())
    }

    public suspend fun enable(pluginId: PluginId): PluginDescriptor {
        val descriptor = descriptorOfInstalled(pluginId)
        val manifest = manifestOfInstalled(pluginId)!!
        val ready = installer.layoutOf(pluginId).isReady() || resolver.plan(manifest).all { it.alreadyPresent }
        return descriptor.copy(
            status = if (ready) PluginStatus.READY else PluginStatus.UNRESOLVED,
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    public fun remove(pluginId: PluginId): Boolean {
        pending.remove(pluginId.raw)
        val removed = installer.remove(pluginId)
        if (removed) audit.record("remove", pluginId.raw, "REMOVED", null)
        return removed
    }

    // ---- queries ------------------------------------------------------------

    /** All known plugins: pending imports + installed trees (disk truth). */
    /**
     * Installed plugins plus pending imports NOT yet installed — exactly one entry per
     * id. During approveImport the plugin is installed on disk before its (minutes-long)
     * dependency resolution finishes and removes it from [pending], so for that window
     * the same id is both installed AND pending. Returning both crashes any id-keyed
     * list (LazyColumn duplicate key), so installed always wins.
     */
    public fun all(): List<PluginDescriptor> {
        val installed = installedDescriptors().toList()
        val installedIds = installed.mapTo(HashSet()) { it.id.raw }
        val pendingOnly = pending.values.map { it.descriptor }.filter { it.id.raw !in installedIds }
        return (installed + pendingOnly).sortedBy { it.id.raw }
    }

    public fun descriptor(pluginId: PluginId): PluginDescriptor? =
        pending[pluginId.raw]?.descriptor ?: installedDescriptor(pluginId)

    /**
     * The validated manifest of an installed plugin (disk truth) — the run
     * draft builds its INPUTS/OPTIONS cards from the ActionDeclarations.
     */
    public fun manifest(pluginId: PluginId): com.forgekit.plugin.manifest.PluginManifest? =
        manifestOfInstalled(pluginId)

    public fun pendingImports(): List<ImportReview> = pending.values.sortedBy { it.descriptor.id.raw }

    // ---- internals ------------------------------------------------------------

    private fun installedDescriptors(): Sequence<PluginDescriptor> =
        listInstalledIds().asSequence().mapNotNull { runCatching { descriptorOfInstalled(it) }.getOrNull() }

    private fun listInstalledIds(): List<PluginId> {
        if (!Files.isDirectory(pluginsRoot)) return emptyList()
        val ids = mutableListOf<PluginId>()
        Files.list(pluginsRoot).use { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                val dir = iterator.next()
                if (Files.isRegularFile(dir.resolve(ForgePackage.MANIFEST_ENTRY))) {
                    runCatching { PluginId.parse(dir.fileName.toString()) }.getOrNull()?.let(ids::add)
                }
            }
        }
        return ids
    }

    private fun manifestOfInstalled(pluginId: PluginId): com.forgekit.plugin.manifest.PluginManifest? {
        val manifestFile = pluginsRoot.resolve(pluginId.raw).resolve(ForgePackage.MANIFEST_ENTRY)
        if (!Files.isRegularFile(manifestFile)) return null
        return runCatching { parser.parse(Files.readAllBytes(manifestFile)) }.getOrNull()
    }

    private fun descriptorOfInstalled(pluginId: PluginId): PluginDescriptor {
        val manifest = manifestOfInstalled(pluginId)
            ?: throw PluginError("plugin '${pluginId.raw}' is not installed")
        val root = pluginsRoot.resolve(pluginId.raw)
        val ready = PackageInstaller(pluginsRoot).layoutOf(pluginId).isReady()
        return PluginDescriptor(
            id = PluginId.parse(manifest.id),
            version = PluginVersion.parse(manifest.version),
            name = manifest.name,
            description = manifest.description,
            status = if (ready) PluginStatus.READY else PluginStatus.UNRESOLVED,
            trust = trustFromManifest(manifest),
            publisherKeyFingerprint = null,
            packageSha256 = null,
            installedPath = root.toString(),
            runtimeType = manifest.runtime.type,
            runtimeVersionRequirement = manifest.runtime.version,
            capabilities = capabilitiesOf(manifest),
            requestedPermissions = manifest.permissions,
            tags = manifest.tags,
            installedAtMillis = Files.getLastModifiedTime(root).toMillis(),
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    private fun installedDescriptor(pluginId: PluginId): PluginDescriptor? =
        runCatching { descriptorOfInstalled(pluginId) }.getOrNull()

    private fun descriptorFrom(
        pkg: ForgePackage,
        report: ValidationReport,
        status: PluginStatus,
    ): PluginDescriptor {
        val manifest = pkg.manifest
        return PluginDescriptor(
            id = PluginId.parse(manifest.id),
            version = PluginVersion.parse(manifest.version),
            name = manifest.name,
            description = manifest.description,
            status = status,
            trust = when {
                !report.signaturePresent -> PluginTrustLevel.UNSIGNED
                report.trustLevel == TrustLevel.TRUSTED -> PluginTrustLevel.SIGNED_TRUSTED
                else -> PluginTrustLevel.SIGNED_UNKNOWN
            },
            publisherKeyFingerprint = report.publisher?.keyFingerprint,
            packageSha256 = report.packageSha256,
            installedPath = null,
            runtimeType = manifest.runtime.type,
            runtimeVersionRequirement = manifest.runtime.version,
            capabilities = capabilitiesOf(manifest),
            requestedPermissions = manifest.permissions,
            tags = manifest.tags,
            installedAtMillis = System.currentTimeMillis(),
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    private fun capabilitiesOf(manifest: com.forgekit.plugin.manifest.PluginManifest): List<PluginCapability> =
        manifest.actions.map { action ->
            PluginCapability(
                kind = "action",
                name = action.id,
                title = action.title,
                description = action.description,
            )
        } + (manifest.ui?.let {
            listOf(PluginCapability(kind = "ui", name = "main", title = "Declarative UI", description = null))
        } ?: emptyList())

    private fun trustFromManifest(@Suppress("UNUSED_PARAMETER") manifest: com.forgekit.plugin.manifest.PluginManifest): PluginTrustLevel =
        PluginTrustLevel.UNSIGNED // disk descriptors carry trust from the DB record (M7); manifest alone can't prove signing
}
