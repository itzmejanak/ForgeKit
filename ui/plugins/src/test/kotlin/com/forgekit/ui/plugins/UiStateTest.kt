package com.forgekit.ui.plugins

import com.forgekit.plugin.manifest.ManifestParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The declarative UI → protocol input bridge. REAL manifest + REAL
 * document, exercising exactly what a host does before invoking a job.
 */
class UiStateTest {

    private val manifest = ManifestParser().parse(MANIFEST_JSON.toByteArray())
    private val document = UiDocumentParser.parse(DOC_JSON.toByteArray(), manifest)

    private fun state() = UiState(manifest, document)

    @Test
    fun `invocation is gated until required inputs are filled`() {
        val s = state()
        val before = s.invocationProblems("run")
        assertTrue(before.isNotEmpty(), "target + name are required")

        s.setValue("name-in", "Janak")
        val middle = s.invocationProblems("run")
        assertTrue(middle.size == 1 && "target" in middle.first(), "problems: $middle")

        s.setValue("target-in", "/tmp/app.apk")
        assertEquals(emptyList(), s.invocationProblems("run"))
    }

    @Test
    fun `input payload carries control ids not block ids`() {
        val s = state()
        s.setValue("name-in", "Janak")
        s.setValue("target-in", "/tmp/app.apk")
        s.setValue("arch-opt", "arm64")
        val payload = s.inputPayload("run")

        assertEquals("Janak", payload["name"])
        assertEquals("/tmp/app.apk", payload["target"])
        assertEquals("arm64", payload["arch"])
        assertTrue("name-in" !in payload, "block ids must never leak into the protocol")
    }

    @Test
    fun `option default applies when untouched`() {
        val s = state()
        s.setValue("name-in", "Janak")
        s.setValue("target-in", "/tmp/app.apk")
        val payload = s.inputPayload("run")
        assertEquals("auto", payload["arch"], "declared default must apply")
    }

    @Test
    fun `option fallback is the first choice without default`() {
        val noDefault = UiState(manifestNoDefault, documentNoDefault())
        noDefault.setValue("name-in", "Janak")
        noDefault.setValue("target-in", "/tmp/app.apk")
        assertEquals("a", noDefault.inputPayload("run")["pick"])
    }

    @Test
    fun `select values outside declared choices are refused`() {
        val s = state()
        s.setValue("name-in", "Janak")
        s.setValue("target-in", "/tmp/app.apk")
        s.setValue("arch-opt", "riscv") // not a declared choice
        val problems = s.invocationProblems("run")
        assertTrue(problems.any { "not a declared choice" in it }, "problems: $problems")
    }

    @Test
    fun `numeric inputs reject non-numeric values`() {
        val s = state()
        s.setValue("name-in", "Janak")
        s.setValue("target-in", "/tmp/app.apk")
        s.setValue("level-in", "very-high")
        val problems = s.invocationProblems("run")
        assertTrue(problems.any { "numeric" in it }, "problems: $problems")
    }

    @Test
    fun `pending file grants block invocation`() {
        val s = state()
        s.setValue("name-in", "Janak")
        s.setValue("target-in", "content://picked.apk")
        s.setFilePending("target-in", true)
        val problems = s.invocationProblems("run")
        assertTrue(problems.any { "being resolved" in it }, "problems: $problems")

        s.setFilePending("target-in", false)
        assertEquals(emptyList(), s.invocationProblems("run"))
    }

    @Test
    fun `input payload throws instead of shipping invalid state`() {
        val s = state()
        assertFailsWith<IllegalStateException> { s.inputPayload("run") }
    }

    @Test
    fun `snapshot and restore round-trip values`() {
        val s = state()
        s.setValue("name-in", "Janak")
        s.setValue("arch-opt", "arm")
        val snapshot = s.snapshot()

        val fresh = state()
        fresh.restore(snapshot)
        assertEquals("Janak", fresh.valueOf("name-in"))
        assertEquals("arm", fresh.valueOf("arch-opt"))

        // restored state produces the identical protocol payload
        s.setValue("target-in", "/tmp/app.apk")
        snapshot // keep original snapshot for the fresh copy
        fresh.setValue("target-in", "/tmp/app.apk")
        assertEquals(
            s.inputPayload("run"),
            fresh.inputPayload("run"),
        )
    }

    @Test
    fun `unknown action is an honest error`() {
        val s = state()
        assertEquals(listOf("unknown action 'nope'"), s.invocationProblems("nope"))
    }

    // ---- fixtures -----------------------------------------------------------

    private fun documentNoDefault(): UiDocument = UiDocumentParser.parse(
        """
            {
              "schema": "forgekit.ui/v1",
              "blocks": [
                { "type": "input", "id": "name-in", "action": "run", "input": "name" },
                { "type": "input", "id": "target-in", "action": "run", "input": "target" },
                { "type": "option", "id": "pick-opt", "action": "run", "option": "pick" },
                { "type": "action", "id": "go", "invoke": "run" }
              ]
            }
        """.trimIndent().toByteArray(),
        manifestNoDefault,
    )

    private val manifestNoDefault: com.forgekit.plugin.manifest.PluginManifest by lazy {
        ManifestParser().parse(
            """
            {
              "schema": "forgekit.plugin/v1",
              "id": "com.example.tool",
              "name": "Example Tool",
              "version": "1.0.0",
              "runtime": { "type": "python", "version": ">=3.6" },
              "entrypoint": "runtime/main.py",
              "ui": { "entry": "ui/main.json" },
              "actions": [
                {
                  "id": "run",
                  "title": "Run",
                  "inputs": [
                    { "id": "target", "label": "Target", "type": "file", "required": true },
                    { "id": "name", "label": "Name", "type": "text", "required": true },
                    { "id": "level", "label": "Level", "type": "number" }
                  ],
                  "options": [
                    { "id": "pick", "label": "Pick", "type": "select", "choices": ["a", "b", "c"] }
                  ],
                  "outputs": [ { "id": "patched", "type": "file" } ]
                }
              ]
            }
            """.trimIndent().toByteArray(),
        )
    }

    private companion object {
        val MANIFEST_JSON = """
            {
              "schema": "forgekit.plugin/v1",
              "id": "com.example.tool",
              "name": "Example Tool",
              "version": "1.0.0",
              "runtime": { "type": "python", "version": ">=3.6" },
              "entrypoint": "runtime/main.py",
              "ui": { "entry": "ui/main.json" },
              "actions": [
                {
                  "id": "run",
                  "title": "Run",
                  "inputs": [
                    { "id": "target", "label": "Target", "type": "file", "required": true },
                    { "id": "name", "label": "Name", "type": "text", "required": true },
                    { "id": "level", "label": "Level", "type": "number" }
                  ],
                  "options": [
                    { "id": "arch", "label": "Binary architecture",
                      "type": "select", "choices": ["auto", "arm", "arm64", "x86", "x64"], "default": "auto" }
                  ],
                  "outputs": [ { "id": "patched", "type": "file" } ]
                }
              ]
            }
        """.trimIndent()

        val DOC_JSON = """
            {
              "schema": "forgekit.ui/v1",
              "title": "Run tool",
              "blocks": [
                { "type": "input", "id": "name-in", "action": "run", "input": "name" },
                { "type": "input", "id": "target-in", "action": "run", "input": "target" },
                { "type": "input", "id": "level-in", "action": "run", "input": "level" },
                { "type": "option", "id": "arch-opt", "action": "run", "option": "arch" },
                { "type": "action", "id": "go", "invoke": "run" }
              ]
            }
        """.trimIndent()
    }
}
