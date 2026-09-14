package com.forgekit.ui.plugins

import com.forgekit.plugin.api.PluginError
import com.forgekit.plugin.manifest.ManifestParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * REAL parser + state tests: the exact JSON the renderer would consume,
 * the exact manifest contract it binds to. Every rejection branch is a
 * way a hostile or broken package would try to smuggle UI we never agreed
 * to render.
 */
class UiDocumentParserTest {

    private val manifest = ManifestParser().parse(MANIFEST_JSON.toByteArray())

    private fun parseDoc(ui: String) = UiDocumentParser.parse(ui.toByteArray(), manifest)

    @Test
    fun `parses a full document with every block kind`() {
        val doc = parseDoc(FULL_DOC)
        assertEquals(UiDocumentParser.SCHEMA, doc.schema)
        assertEquals("Run tool", doc.title)
        assertEquals(7, doc.blocks.size)
        assertTrue(doc.blocks.any { it is UiBlock.InputBlock })
        assertTrue(doc.blocks.any { it is UiBlock.OptionBlock })
        assertTrue(doc.blocks.any { it is UiBlock.ActionBlock })
        assertTrue(doc.blocks.any { it is UiBlock.StaticBlock })
        assertTrue(doc.blocks.any { it is UiBlock.ProgressBlock })
        assertTrue(doc.blocks.any { it is UiBlock.LogBlock })
    }

    @Test
    fun `wrong schema is rejected`() {
        val error = assertFailsWith<PluginError> {
            parseDoc(FULL_DOC.replace("forgekit.ui/v1", "forgekit.ui/v2"))
        }
        val text = (error.detail ?: "") + error.message
        assertTrue("schema" in text, "detail=${error.detail} message=${error.message}")
    }

    @Test
    fun `unknown JSON keys are rejected`() {
        val error = assertFailsWith<PluginError> {
            parseDoc(FULL_DOC.replace("\"blocks\"", "\"surprise\": 1, \"blocks\""))
        }
        assertTrue("not valid forgekit.ui/v1" in error.message, error.message)
    }

    @Test
    fun `references to undeclared action are rejected`() {
        val error = assertFailsWith<PluginError> {
            parseDoc(FULL_DOC.replace("\"invoke\": \"run\"", "\"invoke\": \"format-disk\""))
        }
        assertTrue("unknown action" in (error.detail ?: error.message), error.detail)
    }

    @Test
    fun `references to undeclared input are rejected`() {
        val error = assertFailsWith<PluginError> {
            parseDoc(FULL_DOC.replace("\"input\": \"target\"", "\"input\": \"target2\""))
        }
        assertTrue("undeclared input" in (error.detail ?: ""), error.detail)
    }

    @Test
    fun `references to undeclared option are rejected`() {
        val error = assertFailsWith<PluginError> {
            parseDoc(FULL_DOC.replace("\"option\": \"arch\"", "\"option\": \"flavor\""))
        }
        assertTrue("undeclared option" in (error.detail ?: ""), error.detail)
    }

    @Test
    fun `required input missing from the document is rejected`() {
        val error = assertFailsWith<PluginError> {
            parseDoc(DOC_WITHOUT_REQUIRED_INPUT)
        }
        assertTrue("required input" in (error.detail ?: ""), error.detail)
    }

    @Test
    fun `malformed block ids are rejected`() {
        val error = assertFailsWith<PluginError> {
            parseDoc(FULL_DOC.replace("\"id\": \"name-in\"", "\"id\": \"Name In\""))
        }
        assertTrue("malformed" in (error.detail ?: ""), error.detail)
    }

    @Test
    fun `duplicate block ids are rejected`() {
        val error = assertFailsWith<PluginError> {
            parseDoc(FULL_DOC.replace("\"id\": \"intro\"", "\"id\": \"name-in\""))
        }
        assertTrue("duplicate" in (error.detail ?: ""), error.detail)
    }

    @Test
    fun `static block without content is rejected`() {
        val error = assertFailsWith<PluginError> {
            parseDoc(
                FULL_DOC.replace(
                    "{ \"type\": \"static\", \"id\": \"intro\", \"text\": \"Runs the tool.\" },",
                    "{ \"type\": \"static\", \"id\": \"intro\" },",
                ),
            )
        }
        assertTrue("needs text or markdown" in (error.detail ?: ""), error.detail)
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
                    { "id": "name", "label": "Name", "type": "text", "required": true }
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

        val FULL_DOC = """
            {
              "schema": "forgekit.ui/v1",
              "title": "Run tool",
              "description": "Prepare and run the example action.",
              "blocks": [
                { "type": "static", "id": "intro", "text": "Runs the tool." },
                { "type": "input", "id": "name-in", "action": "run", "input": "name", "label": "Name" },
                { "type": "input", "id": "target-in", "action": "run", "input": "target" },
                { "type": "option", "id": "arch-opt", "action": "run", "option": "arch", "label": "Binary architecture" },
                { "type": "progress", "id": "run-progress" },
                { "type": "log", "id": "run-log" },
                { "type": "action", "id": "go", "invoke": "run", "label": "Run" }
              ]
            }
        """.trimIndent()

        val DOC_WITHOUT_REQUIRED_INPUT = """
            {
              "schema": "forgekit.ui/v1",
              "blocks": [
                { "type": "input", "id": "name-in", "action": "run", "input": "name" },
                { "type": "option", "id": "arch-opt", "action": "run", "option": "arch" },
                { "type": "action", "id": "go", "invoke": "run" }
              ]
            }
        """.trimIndent()
    }
}
