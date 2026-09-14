package com.forgekit.plugin.registry

import com.forgekit.core.security.PublisherKeyPair
import com.forgekit.plugin.installer.ForgePackageBuilder
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * REAL registry tests — zero mocks:
 *  - a REAL JDK HttpServer serves a real forgekit.registry/v1 index and real
 *    (signed) .forge archives over real HTTP sockets on 127.0.0.1
 *  - a REAL git repository (git init + commit) backs GitSource
 *  - digests are computed over the actual transported bytes
 */
class RegistrySourcesTest {

    private lateinit var dir: Path
    private lateinit var server: HttpServer
    private lateinit var serverUrl: String
    private val receivedAuthHeaders = mutableListOf<String?>()

    private val indexBytes: ByteArray by lazy {
        val pkg = dir.resolve("pkg/tool.forge")
        val hash = sha256(pkg)
        RegistryIndex.encode(
            RegistryIndex.Document(
                schema = RegistryIndex.SCHEMA,
                plugins = listOf(
                    RegistryIndex.Entry(
                        id = "com.example.tool",
                        name = "Example Tool",
                        version = "1.0.0",
                        description = "demonstration package",
                        file = "tool.forge",
                        sha256 = hash,
                        tags = listOf("demo", "tools"),
                        runtimeType = "python",
                        runtimeVersion = "3.11",
                        permissions = listOf("storage:plugin-data"),
                        actions = listOf("run"),
                    ),
                ),
            ),
        )
    }

    @BeforeTest
    fun start() {
        dir = Files.createTempDirectory("registry-test")
        Files.createDirectories(dir.resolve("pkg"))
        PublisherKeyPair.generate().let { key ->
            ForgePackageBuilder.standard().signedBy(key, "ForgeLabs")
                .writeTo(dir.resolve("pkg/tool.forge"))
        }

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange: HttpExchange ->
            receivedAuthHeaders += exchange.requestHeaders.getFirst("Authorization")
            val path = exchange.requestURI.path.removePrefix("/")
            val (body, type) = when (path) {
                RegistryIndex.INDEX_ENTRY -> indexBytes to "application/json"
                "tool.forge" -> Files.readAllBytes(dir.resolve("pkg/tool.forge")) to "application/octet-stream"
                "tampered.forge" -> ByteArray(1024) { (it % 251).toByte() } to "application/octet-stream"
                else -> ByteArray(0) to "text/plain"
            }
            val status = if (path == "missing") 404 else 200
            exchange.responseHeaders.add("Content-Type", type)
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.executor = null
        server.start()
        serverUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterTest
    fun stop() {
        server.stop(0)
        dir.toFile().deleteRecursively()
    }

    // ---- HttpRegistrySource (official + private) ----------------------------

    @Test
    fun `official source searches the real index over HTTP`() = runBlocking {
        val source = OfficialRegistrySource(serverUrl)
        val all = source.search("")
        assertEquals(1, all.size)
        val d = all.first()
        assertEquals("com.example.tool", d.id.raw)
        assertEquals("1.0.0", d.version.raw)
        assertEquals("python", d.runtimeType)
        assertTrue(d.tags.contains("demo"))

        val hits = source.search("tools")
        assertEquals(1, hits.size)
        assertEquals(0, source.search("no-such-tag").size)
    }

    @Test
    fun `fetch downloads the real archive and passes hash verification`() = runBlocking {
        val source = OfficialRegistrySource(serverUrl)
        val artifact = source.fetch("com.example.tool", "1.0.0", dir.resolve("downloads"))
        assertEquals(sha256(dir.resolve("pkg/tool.forge")), artifact.sha256)
        assertTrue(Files.size(artifact.file) > 0)
        // byte-identical transport
        assertEquals(
            Files.readAllBytes(dir.resolve("pkg/tool.forge")).toList(),
            Files.readAllBytes(artifact.file).toList(),
        )
        assertEquals("official", artifact.origin)
    }

    @Test
    fun `hash mismatch is refused loudly`() {
        // A SECOND server serving a lying index: it claims the digest of a
        // DIFFERENT file than what tampered.forge actually contains.
        val badIndex = RegistryIndex.encode(
            RegistryIndex.Document(
                schema = RegistryIndex.SCHEMA,
                plugins = listOf(
                    RegistryIndex.Entry(
                        id = "com.example.evil", name = "Evil", version = "2.0.0",
                        file = "tampered.forge",
                        sha256 = sha256(dir.resolve("pkg/tool.forge")), // wrong on purpose
                        runtimeType = "python",
                    ),
                ),
            ),
        )
        val evilServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            evilServer.createContext("/") { exchange: HttpExchange ->
                val body = if (exchange.requestURI.path.removePrefix("/") == RegistryIndex.INDEX_ENTRY) {
                    badIndex
                } else {
                    ByteArray(1024) { (it % 251).toByte() }
                }
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                exchange.close()
            }
            evilServer.executor = null
            evilServer.start()
            val evilUrl = "http://127.0.0.1:${evilServer.address.port}"

            runBlocking {
                val source = OfficialRegistrySource(evilUrl)
                val error = assertFailsWith<com.forgekit.core.model.ForgeError> {
                    source.fetch("com.example.evil", "2.0.0", dir.resolve("downloads"))
                }
                assertTrue("mismatch" in error.message, error.message)
            }
        } finally {
            evilServer.stop(0)
        }
    }

    @Test
    fun `unknown plugin fails with the precise reason`() = runBlocking {
        val source = OfficialRegistrySource(serverUrl)
        val error = assertFailsWith<com.forgekit.core.model.ForgeError> {
            source.fetch("com.example.nope", "9.9.9", dir.resolve("downloads"))
        }
        assertTrue("not found" in error.message)
    }

    @Test
    fun `unreachable registry fails with a transport error`() = runBlocking {
        // bind then immediately stop a server: the port is closed → real connection refused
        val dead = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = dead.address.port
        dead.stop(0)
        val source = OfficialRegistrySource("http://127.0.0.1:$port")
        val error = assertFailsWith<com.forgekit.core.model.ForgeError> {
            source.search("")
        }
        assertTrue("unreachable" in error.message, error.message)
    }

    @Test
    fun `private registry sends the bearer token`() = runBlocking {
        receivedAuthHeaders.clear()
        val source = PrivateRegistrySource(
            baseUrl = serverUrl,
            sourceId = "private:corp",
            bearerToken = "s3cret-token",
        )
        val artifact = source.fetch("com.example.tool", "latest", dir.resolve("downloads"))
        assertEquals("private:corp", artifact.origin)
        // every request (index + artifact) carried the token
        assertTrue(receivedAuthHeaders.isNotEmpty())
        assertTrue(receivedAuthHeaders.all { it == "Bearer s3cret-token" }, "headers=$receivedAuthHeaders")
    }

    // ---- LocalFileSource ------------------------------------------------------

    @Test
    fun `local source scans real archives and searches them`() = runBlocking {
        val source = LocalFileSource(dir.resolve("pkg"))
        assertEquals(1, source.refresh())
        val all = source.search("")
        assertEquals(1, all.size)
        assertEquals("com.example.tool", all.first().id.raw)
        assertEquals(1, source.search("example").size, "matches the id substring")
        assertEquals(0, source.search("nothing").size)
    }

    @Test
    fun `local fetch copies the archive and reports its true digest`() = runBlocking {
        val source = LocalFileSource(dir.resolve("pkg"))
        val artifact = source.fetch("com.example.tool", "1.0.0", dir.resolve("downloads"))
        assertEquals(sha256(dir.resolve("pkg/tool.forge")), artifact.sha256)
        assertTrue(Files.isRegularFile(artifact.file))
    }

    @Test
    fun `broken archives are skipped in search and refused in fetch`() = runBlocking {
        Files.write(dir.resolve("pkg/broken.forge"), "this is not a zip".toByteArray())
        val source = LocalFileSource(dir.resolve("pkg"))
        assertEquals(2, source.refresh())
        assertEquals(1, source.search("").size, "broken archive must not appear in search")

        runBlocking {
            val error = assertFailsWith<com.forgekit.core.model.ForgeError> {
                source.fetch("broken-package-id", "1.0.0", dir.resolve("downloads"))
            }
            assertTrue("not found" in error.message)
        }
    }

    @Test
    fun `missing directory is honestly unavailable`() = runBlocking {
        val source = LocalFileSource(dir.resolve("does-not-exist"))
        assertEquals(false, source.available)
        assertNull(source.search("").firstOrNull())
    }

    // ---- UrlSource ------------------------------------------------------------

    @Test
    fun `url source downloads the artifact directly`() = runBlocking {
        val source = UrlSource()
        val artifact = source.fetch("$serverUrl/tool.forge", dir.resolve("downloads"))
        assertEquals("tool.forge", artifact.file.fileName.toString())
        assertEquals(sha256(dir.resolve("pkg/tool.forge")), artifact.sha256)
        assertTrue(artifact.origin.startsWith("url:"))
        // no partial .part files left behind
        assertEquals(0, Files.list(dir.resolve("downloads")).use { s -> s.filter { it.fileName.toString().endsWith(".part") }.count() })
    }

    @Test
    fun `url source fails on HTTP error status`() = runBlocking {
        val source = UrlSource()
        val error = assertFailsWith<com.forgekit.core.model.ForgeError> {
            source.fetch("$serverUrl/missing", dir.resolve("downloads"))
        }
        assertTrue("HTTP 404" in error.message, error.message)
    }

    // ---- GitSource ------------------------------------------------------------

    @Test
    fun `git source clones a real repository and serves its archives`() = runBlocking {
        // build a REAL git repository containing the .forge archive
        val repo = dir.resolve("origin-repo")
        Files.createDirectories(repo)
        Files.copy(dir.resolve("pkg/tool.forge"), repo.resolve("tool.forge"))
        runGit(repo, "init", "-q")
        runGit(repo, "config", "user.email", "test@forgekit")
        runGit(repo, "config", "user.name", "ForgeKit Test")
        runGit(repo, "add", ".")
        runGit(repo, "commit", "-q", "-m", "plugin drop")

        val source = GitSource(repo.toAbsolutePath().toString(), dir.resolve("clones"))
        assertTrue(source.available, "git must be installed for this test")

        val results = source.search("")
        assertEquals(1, results.size)
        assertEquals("com.example.tool", results.first().id.raw)

        val artifact = source.fetch("com.example.tool", "1.0.0", dir.resolve("downloads"))
        assertEquals(sha256(repo.resolve("tool.forge")), artifact.sha256)
        assertTrue(Files.isRegularFile(artifact.file))
    }

    // ---- helpers --------------------------------------------------------------

    private fun runGit(workingDir: Path, vararg args: String) {
        val p = ProcessBuilder(listOf("git") + args)
            .directory(workingDir.toFile())
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.readBytes().decodeToString()
        check(p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) {
            "git ${args.joinToString(" ")} failed: $out"
        }
    }

    private fun sha256(file: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
            .joinToString("") { "%02x".format(it) }
}
