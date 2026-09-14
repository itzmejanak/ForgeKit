package com.forgekit.plugin.registry

import com.forgekit.plugin.api.PluginDescriptor
import java.nio.file.Path

/**
 * A place plugins come from (ARCHITECTURE §57/§58).
 *
 * Five implementations, one contract:
 *  - [OfficialRegistrySource] — the built-in index
 *  - [PrivateRegistrySource] — same protocol, auth token, custom base URL
 *  - [LocalFileSource] — a directory of `.forge` archives (no marketplace lock-in, §59)
 *  - [UrlSource] — a direct HTTPS URL to a `.forge` archive
 *  - [GitSource] — a git repository containing `.forge` archives
 *
 * Sources are network-free where possible and ALWAYS honest: fetch either
 * returns a verified local artifact or throws with the precise reason.
 * The installer never learns where the package came from (§57).
 */
public interface PluginSource {
    /** Stable identifier of this source (audit + UI provenance). */
    public val sourceId: String

    /** True when search/fetch can work right now (e.g. local dir exists). */
    public val available: Boolean

    /**
     * Search the source. [query] matches id, name, and tags; blank query
     * lists everything the source knows. Results carry NO install state
     * (status/trust are source-level facts, not device facts).
     */
    public suspend fun search(query: String): List<PluginDescriptor>

    /**
     * Materializes the `.forge` archive for [id]@[version] into [targetDir]
     * and returns the local path plus integrity facts. Throws
     * [com.forgekit.core.common.ForgeError] subclasses with the precise
     * problem (not found / hash mismatch / transport failure).
     */
    public suspend fun fetch(id: String, version: String, targetDir: Path): PluginArtifact
}

/**
 * A fetched package: the local `.forge` file and what the source claimed
 * about it. [sha256] is the source-published digest — the caller MUST run
 * full validation (PluginValidator) before trusting the contents; for
 * hash-checked sources the transport layer already refused mismatches.
 */
public data class PluginArtifact(
    public val pluginId: String,
    public val version: String,
    /** The locally materialized `.forge` archive. */
    public val file: Path,
    /** Digest the source published for this artifact, if any. */
    public val sha256: String?,
    /** Provenance for the audit log. */
    public val origin: String,
)
