package com.forgekit.plugin.registry

import com.forgekit.plugin.api.PluginDescriptor
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import kotlin.io.path.createTempFile

/**
 * Direct `.forge` URL import (§58/§59): the user hands ForgeKit a URL, the
 * source downloads it verbatim. No index, no id resolution — the URL IS
 * the address. Provenance records the URL; validation happens later on the
 * local artifact (packages are never trusted by origin).
 */
public class UrlSource(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) : PluginSource {

    override public val sourceId: String = "url"

    override public val available: Boolean get() = true

    override public suspend fun search(query: String): List<PluginDescriptor> =
        emptyList() // a bare URL has no catalog; fetch is the whole contract

    /**
     * Downloads [url] into [targetDir]. The artifact identity (id/version)
     * is discovered by the caller from the archive's manifest — a URL source
     * cannot know it beforehand, so [PluginArtifact.pluginId] carries the
     * file name and version `url`.
     */
    public suspend fun fetch(url: String, targetDir: Path): PluginArtifact {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(120))
            .GET()
            .build()
        val response: HttpResponse<ByteArray> = try {
            client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        } catch (e: IOException) {
            throw RegistryError("download failed: ${e.javaClass.simpleName}", e.message ?: url)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RegistryError("download interrupted", url)
        }
        if (response.statusCode() != 200) {
            throw RegistryError("download returned HTTP ${response.statusCode()}", "$url → ${response.statusCode()}")
        }
        val bytes = response.body()
        if (bytes.size < 22) throw RegistryError("downloaded file is not a ZIP archive", "$url (${bytes.size} bytes)")

        val fileName = URI.create(url).path.substringAfterLast('/').ifBlank { "download.forge" }
        Files.createDirectories(targetDir)
        val target = targetDir.resolve(fileName)
        createTempFile(targetDir, "url-", ".part").toFile().useSafe { tmp ->
            Files.write(tmp, bytes)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return PluginArtifact(
            pluginId = fileName.removeSuffix(".forge"),
            version = "url",
            file = target,
            sha256 = digest,
            origin = "url:$url",
        )
    }

    override public suspend fun fetch(id: String, version: String, targetDir: Path): PluginArtifact =
        throw RegistryError("UrlSource fetches by URL, not by id", "use fetch(url, targetDir)")

    /** Applies when the id IS a URL (manager-level routing convenience). */
    public suspend fun fetchByString(idOrUrl: String, targetDir: Path): PluginArtifact =
        if (idOrUrl.startsWith("http://") || idOrUrl.startsWith("https://")) {
            fetch(idOrUrl, targetDir)
        } else {
            throw RegistryError("not a URL", idOrUrl)
        }
}

/** Apply-and-delete so partial downloads never linger. */
private inline fun java.io.File.useSafe(block: (Path) -> Unit) {
    val path = toPath()
    try {
        block(path)
    } finally {
        if (exists()) delete()
    }
}
