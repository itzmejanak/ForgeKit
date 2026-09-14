package com.forgekit.plugin.registry

import com.forgekit.plugin.api.PluginCapability
import com.forgekit.plugin.api.PluginDescriptor
import com.forgekit.plugin.api.PluginId
import com.forgekit.plugin.api.PluginStatus
import com.forgekit.plugin.api.PluginTrustLevel
import com.forgekit.plugin.api.PluginVersion
import com.forgekit.plugin.manifest.ManifestParser
import com.forgekit.plugin.manifest.PluginManifest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * A directory of `.forge` archives on local storage (§59: import
 * `my-tool.forge` directly, no marketplace required).
 *
 * Everything here is REAL: archives are opened as ZIP, manifests parsed
 * with the strict v1 parser, digests computed over the actual bytes.
 * Broken archives are skipped from search and refused on fetch with the
 * precise reason — never silently passed.
 */
public class LocalFileSource(
    /** Directory scanned for `*.forge` files (non-recursive, per §59 import semantics). */
    private val directory: Path,
    private val parser: ManifestParser = ManifestParser(),
    override val sourceId: String = "local:${directory.fileName}",
) : PluginSource {

    override public val available: Boolean get() = Files.isDirectory(directory)

    /** One scanned archive: the manifest, or the reason it could not be read. */
    private data class Scanned(
        val file: Path,
        val manifest: PluginManifest?,
        val problem: String?,
        val sha256: String,
        val modifiedMillis: Long,
    )

    private var scanned: List<Scanned> = emptyList()

    /** Rescans the directory; returns the number of archives found. */
    public suspend fun refresh(): Int {
        if (!available) throw RegistryError("source directory does not exist", directory.toString())
        val result = mutableListOf<Scanned>()
        Files.list(directory).use { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                val file = iterator.next()
                if (!Files.isRegularFile(file) || !file.fileName.toString().endsWith(".forge")) continue
                val parsed = runCatching { readManifest(file) }
                result += Scanned(
                    file = file,
                    manifest = parsed.getOrNull(),
                    problem = parsed.exceptionOrNull()?.message,
                    sha256 = sha256Of(file),
                    modifiedMillis = Files.getLastModifiedTime(file).toMillis(),
                )
            }
        }
        scanned = result.sortedWith(compareBy({ it.file.fileName.toString() }))
        return result.size
    }

    override public suspend fun search(query: String): List<PluginDescriptor> {
        if (!available) return emptyList()
        if (scanned.isEmpty()) refresh()
        val q = query.trim().lowercase()
        return scanned.filter { it.manifest != null && it.problem == null }
            .map { descriptorOf(it) }
            .filter { d ->
                q.isBlank() || d.id.raw.contains(q) || d.name.lowercase().contains(q) ||
                    d.tags.any { it.lowercase().contains(q) }
            }
    }

    override public suspend fun fetch(id: String, version: String, targetDir: Path): PluginArtifact {
        if (!available) throw RegistryError("source directory does not exist", directory.toString())
        if (scanned.isEmpty()) refresh()
        val match = scanned.filter { it.manifest?.id == id }
            .sortedWith(compareByDescending { it.manifest!!.version })
            .firstOrNull { version == "latest" || it.manifest!!.version == version }
            ?: throw RegistryError(
                "plugin '$id@$version' not found in local source",
                "scanned ${scanned.size} archive(s) in ${directory.fileName}",
            )
        match.problem?.let { throw RegistryError("archive is not a valid .forge package", it) }

        Files.createDirectories(targetDir)
        val target = targetDir.resolve("${id.replace('.', '_')}-${match.manifest!!.version}.forge")
        Files.copy(match.file, target, StandardCopyOption.REPLACE_EXISTING)
        return PluginArtifact(
            pluginId = id,
            version = match.manifest!!.version,
            file = target,
            sha256 = match.sha256,
            origin = sourceId,
        )
    }

    // ---- internals ----------------------------------------------------------

    private fun readManifest(file: Path): PluginManifest {
        ZipFile(file.toFile()).use { zip ->
            val entry = zip.getEntry("manifest.json")
                ?: throw RegistryError("archive has no manifest.json", file.toString())
            return parser.parse(zip.getInputStream(entry).readBytes())
        }
    }

    private fun descriptorOf(s: Scanned): PluginDescriptor {
        val m = s.manifest!!
        return PluginDescriptor(
            id = PluginId.parse(m.id),
            version = PluginVersion.parse(m.version),
            name = m.name,
            description = m.description,
            status = PluginStatus.READY, // source-level fact: fetchable from this source
            trust = PluginTrustLevel.SIGNED_UNKNOWN, // unknown until validation
            publisherKeyFingerprint = null,
            packageSha256 = s.sha256,
            installedPath = null,
            runtimeType = m.runtime.type,
            runtimeVersionRequirement = m.runtime.version,
            capabilities = m.actions.map { a ->
                PluginCapability(kind = "action", name = a.id, title = a.title, description = a.description)
            },
            requestedPermissions = m.permissions,
            tags = m.tags,
            installedAtMillis = s.modifiedMillis,
            updatedAtMillis = s.modifiedMillis,
        )
    }

    private fun sha256Of(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
