package com.forgekit.plugin.registry

import com.forgekit.plugin.api.PluginCapability
import com.forgekit.plugin.api.PluginDescriptor
import com.forgekit.plugin.api.PluginId
import com.forgekit.plugin.api.PluginStatus
import com.forgekit.plugin.api.PluginTrustLevel
import com.forgekit.plugin.api.PluginVersion
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import kotlin.io.path.createTempFile

/**
 * HTTP registry protocol implementation shared by the official and private
 * sources (§57/§58):
 *
 *  - `GET {base}/index.json` → [RegistryIndex] document (strict decode)
 *  - `GET {base}/{file}`     → the `.forge` artifact, digest-verified in-flight
 *
 * Real `java.net.http.HttpClient`, real network error propagation (no mock
 * transports anywhere): transport failures, non-2xx statuses, hash
 * mismatches and unknown ids each fail with their precise reason.
 */
public open class HttpRegistrySource(
    /** Base URL of the registry (index at `{base}/index.json`). */
    private val baseUrl: String,
    final override public val sourceId: String,
    /** Optional bearer token (PrivateRegistrySource). */
    private val bearerToken: String? = null,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) : PluginSource {

    override public val available: Boolean get() = true

    private var index: RegistryIndex.Document? = null

    /** Drops the cached index so the next search re-fetches it. */
    public fun invalidate() {
        index = null
    }

    override public suspend fun search(query: String): List<PluginDescriptor> {
        val doc = index ?: fetchIndex().also { index = it }
        val q = query.trim().lowercase()
        return doc.plugins
            .filter { e ->
                q.isBlank() || e.id.contains(q) || e.name.lowercase().contains(q) ||
                    e.tags.any { it.lowercase().contains(q) }
            }
            .map { entry -> descriptorOf(entry) }
    }

    override public suspend fun fetch(id: String, version: String, targetDir: Path): PluginArtifact {
        val doc = index ?: fetchIndex().also { index = it }
        val entry = doc.plugins
            .filter { it.id == id }
            .sortedWith(compareByDescending { it.version })
            .firstOrNull { version == "latest" || it.version == version }
            ?: throw RegistryError(
                "plugin '$id@$version' not found in registry",
                "registry knows ${doc.plugins.size} plugin(s)",
            )

        val bytes = get("$baseUrl/${entry.file.trimStart('/')}")
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        if (digest != entry.sha256) {
            throw RegistryError(
                "registry artifact hash mismatch — refused",
                "id=$id version=${entry.version} expected=${entry.sha256} actual=$digest",
            )
        }

        Files.createDirectories(targetDir)
        val target = targetDir.resolve("${id.replace('.', '_')}-${entry.version}.forge")
        createTempFile(targetDir, "fetch-", ".part").toFile().useSafe { tmp ->
            Files.write(tmp, bytes)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        return PluginArtifact(
            pluginId = id,
            version = entry.version,
            file = target,
            sha256 = digest,
            origin = sourceId,
        )
    }

    // ---- internals ----------------------------------------------------------

    private fun fetchIndex(): RegistryIndex.Document {
        val bytes = get("$baseUrl/${RegistryIndex.INDEX_ENTRY}")
        return RegistryIndex.decode(bytes)
    }

    private fun get(url: String): ByteArray {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .apply {
                bearerToken?.let { header("Authorization", "Bearer $it") }
            }
            .build()
        val response: HttpResponse<ByteArray> = try {
            client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        } catch (e: IOException) {
            throw RegistryError("registry unreachable: ${e.javaClass.simpleName}", e.message ?: url)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RegistryError("registry request interrupted", url)
        }
        if (response.statusCode() != 200) {
            throw RegistryError(
                "registry returned HTTP ${response.statusCode()}",
                "$url → ${response.statusCode()}",
            )
        }
        return response.body()
    }

    private fun descriptorOf(e: RegistryIndex.Entry): PluginDescriptor = PluginDescriptor(
        id = PluginId.parse(e.id),
        version = PluginVersion.parse(e.version),
        name = e.name,
        description = e.description,
        status = PluginStatus.READY, // source-level fact: fetchable
        trust = PluginTrustLevel.SIGNED_UNKNOWN, // unknown until validation
        publisherKeyFingerprint = null,
        packageSha256 = e.sha256,
        installedPath = null,
        runtimeType = e.runtimeType,
        runtimeVersionRequirement = e.runtimeVersion,
        capabilities = e.actions.map { a ->
            PluginCapability(kind = "action", name = a, title = a, description = null)
        },
        requestedPermissions = e.permissions,
        tags = e.tags,
        installedAtMillis = 0L,
        updatedAtMillis = 0L,
    )
}

/** The built-in registry (§57: one well-known source among five, never mandatory). */
public class OfficialRegistrySource(
    baseUrl: String,
) : HttpRegistrySource(baseUrl, "official")

/** A self-hosted registry with token auth (§58). */
public class PrivateRegistrySource(
    baseUrl: String,
    /** Registry label shown in provenance, e.g. `private:corp`. */
    sourceId: String = "private",
    bearerToken: String,
) : HttpRegistrySource(baseUrl, sourceId, bearerToken)

/** Small helper: apply-and-delete so partial downloads never linger. */
private inline fun java.io.File.useSafe(block: (Path) -> Unit) {
    val path = toPath()
    try {
        block(path)
    } finally {
        if (exists()) delete()
    }
}
