package com.forgekit.plugin.manifest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * REAL manifest parsing tests: exact JSON fixtures (including one modeled on
 * the reference app's imported package), strict-schema rejections, and every
 * validator branch.
 */
class ManifestParserTest {

    private val parser = ManifestParser()

    private val fullManifest = """
        {
          "schema": "forgekit.plugin/v1",
          "id": "com.r2.tool",
          "name": "R2 Analyzer",
          "version": "1.2.0",
          "description": "Radare2-driven binary analysis",
          "author": "re@example.org",
          "tags": ["Reverse Engineering", "binary"],
          "runtime": { "type": "python", "version": ">=3.6.0" },
          "entrypoint": "runtime/main.py",
          "ui": { "entry": "ui/main.json" },
          "dependencies": {
            "termux": ["radare2"],
            "python": [ { "name": "r2pipe", "version": "==1.9.8", "source": "pypi" } ]
          },
          "permissions": ["network", "files.read", "files.write", "artifact.read", "artifact.write", "ui.progress"],
          "actions": [
            {
              "id": "analyze",
              "title": "Analyze binary",
              "description": "Runs r2 analysis on the input file",
              "inputs": [
                { "id": "binary", "label": "Input binary", "type": "file", "required": true }
              ],
              "options": [
                {
                  "id": "arch",
                  "label": "Binary architecture",
                  "choices": ["auto", "arm", "arm64", "x86", "x64"],
                  "default": "auto",
                  "mapsTo": "input.arch"
                }
              ],
              "outputs": [
                { "id": "report", "type": "file", "label": "Analysis report" }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses a complete real-world manifest`() {
        val manifest = parser.parse(fullManifest.toByteArray())

        assertEquals("forgekit.plugin/v1", manifest.schema)
        assertEquals("com.r2.tool", manifest.id)
        assertEquals("R2 Analyzer", manifest.name)
        assertEquals("1.2.0", manifest.version)
        assertEquals("python", manifest.runtime.type)
        assertEquals(">=3.6.0", manifest.runtime.version)
        assertEquals("runtime/main.py", manifest.entrypoint)
        assertEquals("ui/main.json", manifest.ui?.entry)
        assertEquals(listOf("radare2"), manifest.dependencies.termux)
        assertEquals(1, manifest.dependencies.python.size)
        assertEquals("r2pipe", manifest.dependencies.python[0].name)
        assertEquals("==1.9.8", manifest.dependencies.python[0].version)
        assertEquals(6, manifest.permissions.size)
        assertEquals(1, manifest.actions.size)

        val action = manifest.actions[0]
        assertEquals("analyze", action.id)
        assertEquals(1, action.inputs.size)
        assertEquals("file", action.inputs[0].type)
        assertTrue(action.inputs[0].required)
        assertEquals(5, action.options[0].choices.size)
        assertEquals(1, action.outputs.size)
        assertEquals("file", action.outputs[0].type)

        val validation = parser.validate(manifest)
        assertTrue(validation.valid, "problems: ${validation.problems}")
    }

    @Test
    fun `rejects wrong schema id loudly`() {
        val bad = fullManifest.replace("forgekit.plugin/v1", "forgekit.plugin/v2")
        try {
            parser.parse(bad.toByteArray())
            fail("wrong schema must fail")
        } catch (expected: com.forgekit.plugin.api.PluginError) {
            assertTrue("schema" in (expected.detail ?: ""))
        }
    }

    @Test
    fun `rejects malformed plugin ids and versions`() {
        for (id in listOf("Not-Valid", "single", "com..double", "com.Upper")) {
            val bad = fullManifest.replace("\"com.r2.tool\"", "\"$id\"")
            val problems = parser.validate(parser.parseLenient(bad.toByteArray()))
            assertTrue(problems.problems.isNotEmpty(), "id '$id' must be rejected")
        }
        for (version in listOf("1.0", "v1.0.0", "1.0.0.0")) {
            val bad = fullManifest.replace("\"1.2.0\"", "\"$version\"")
            val problems = parser.validate(parser.parseLenient(bad.toByteArray()))
            assertTrue(problems.problems.isNotEmpty(), "version '$version' must be rejected")
        }
    }

    @Test
    fun `rejects entrypoints outside runtime and ui outside ui`() {
        val badEntry = fullManifest.replace("\"runtime/main.py\"", "\"main.py\"")
        assertTrue(parser.validate(parser.parseLenient(badEntry.toByteArray())).problems.isNotEmpty())

        val badUi = fullManifest.replace("\"ui/main.json\"", "\"runtime/main.json\"")
        assertTrue(parser.validate(parser.parseLenient(badUi.toByteArray())).problems.isNotEmpty())

        val escape = fullManifest.replace("\"runtime/main.py\"", "\"../escape.py\"")
        assertTrue(parser.validate(parser.parseLenient(escape.toByteArray())).problems.isNotEmpty())
    }

    @Test
    fun `rejects unknown top-level keys (strict schema)`() {
        val extra = fullManifest.replaceFirst("{", """{ "bogus": 1,""")
        try {
            parser.parse(extra.toByteArray())
            fail("unknown key must fail")
        } catch (expected: com.forgekit.plugin.api.PluginError) {
            assertTrue("JSON" in expected.message)
        }
    }

    @Test
    fun `rejects malformed permissions and duplicates`() {
        val badPerm = fullManifest.replace("\"network\",", "\"Network Access\",")
        val problems = parser.validate(parser.parseLenient(badPerm.toByteArray())).problems
        assertTrue(problems.any { it.contains("permission malformed") }, "problems: $problems")

        val dup = fullManifest.replace(
            "\"permissions\": [\"network\",",
            "\"permissions\": [\"network\", \"network\",",
        )
        val dupProblems = parser.validate(parser.parseLenient(dup.toByteArray())).problems
        assertTrue(dupProblems.any { it.contains("duplicate permission") }, "problems: $dupProblems")
    }

    @Test
    fun `rejects actions with bad control types or select without choices`() {
        val badType = fullManifest.replace("\"type\": \"file\", \"required\": true", "\"type\": \"hologram\", \"required\": true")
        val problems = parser.validate(parser.parseLenient(badType.toByteArray())).problems
        assertTrue(problems.any { it.contains("type unknown") }, "problems: $problems")

        val noChoices = fullManifest.replace("\"choices\": [\"auto\", \"arm\", \"arm64\", \"x86\", \"x64\"],", "\"choices\": [],")
        val problems2 = parser.validate(parser.parseLenient(noChoices.toByteArray())).problems
        assertTrue(problems2.any { it.contains("needs choices") }, "problems: $problems2")
    }

    @Test
    fun `rejects malformed python version pins`() {
        val badPin = fullManifest.replace("\"==1.9.8\"", "\"1.9.8\"")
        val problems = parser.validate(parser.parseLenient(badPin.toByteArray())).problems
        assertTrue(problems.any { it.contains("pin malformed") }, "problems: $problems")
    }

    @Test
    fun `rejects unknown runtime types`() {
        val badRuntime = fullManifest.replace("\"type\": \"python\", \"version\": \">=3.6.0\"", "\"type\": \"java\", \"version\": \">=17\"")
        val problems = parser.validate(parser.parseLenient(badRuntime.toByteArray())).problems
        assertTrue(problems.any { it.contains("runtime.type") }, "problems: $problems")
    }

    @Test
    fun `missing required fields fail as JSON decode errors`() {
        val noEntrypoint = fullManifest.replace("\"entrypoint\": \"runtime/main.py\",", "")
        try {
            parser.parse(noEntrypoint.toByteArray())
            fail("missing entrypoint must fail")
        } catch (expected: com.forgekit.plugin.api.PluginError) {
            assertTrue("JSON" in expected.message)
        }
    }

    /** Tolerant path for mutation-based tests (skips strict validation pass). */
    private fun ManifestParser.parseLenient(bytes: ByteArray): PluginManifest =
        com.forgekit.plugin.manifest.parseLenientForTests(bytes)
}

/** Lenient decode used only by tests to reach validate() on mutated input. */
internal fun parseLenientForTests(bytes: ByteArray): PluginManifest {
    val lenient = kotlinx.serialization.json.Json { ignoreUnknownKeys = false }
    return lenient.decodeFromString(PluginManifest.serializer(), bytes.toString(Charsets.UTF_8))
}
