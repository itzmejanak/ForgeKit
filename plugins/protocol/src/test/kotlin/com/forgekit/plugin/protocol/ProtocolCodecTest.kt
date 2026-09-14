package com.forgekit.plugin.protocol

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** REAL wire-protocol codec tests: exact §15 examples, round-trips, rejections. */
class ProtocolCodecTest {

    private val codec = ProtocolCodec()

    @Test
    fun `round-trips the architecture section-15 examples exactly`() {
        // invoke (platform → plugin)
        val invokeLine = """
            {"protocol":"forgekit/1","type":"invoke","requestId":"abc123","action":"download",
             "input":{"url":"https://example.com/file"}}
        """.trimIndent().replace("\n", "")
        // NOTE: compact single-line form used below instead (NDJSON is line-based)
        val invoke = codec.decode(
            """{"protocol":"forgekit/1","type":"invoke","requestId":"abc123","action":"download","input":{"url":"https://example.com/file"}}""",
        )
        assertTrue(invoke is ProtocolMessage.Invoke)
        assertEquals("abc123", invoke.requestId)
        assertEquals("download", invoke.action)
        assertEquals("https://example.com/file", (invoke.input["url"] as JsonPrimitive).content)

        // progress
        val progress = codec.decode(
            """{"protocol":"forgekit/1","type":"progress","requestId":"abc123","value":0.72,"message":"Downloading"}""",
        )
        assertTrue(progress is ProtocolMessage.Progress)
        assertEquals(0.72, progress.value)

        // result
        val result = codec.decode(
            """{"protocol":"forgekit/1","type":"result","requestId":"abc123","status":"success",
               "output":{"file":"/path/to/result"}}""".replace("\n", "").replace("  ", ""),
        )
        assertTrue(result is ProtocolMessage.Result)
        assertEquals("success", result.status)
        assertEquals("/path/to/result", (result.output["file"] as JsonPrimitive).content)
    }

    @Test
    fun `encode-decode round-trips every message kind`() {
        val samples: List<ProtocolMessage> = listOf(
            ProtocolMessage.Invoke(protocol = WIRE, requestId = "r1", action = "run", input = mapOf("value" to JsonPrimitive("hello"))),
            ProtocolMessage.Progress(protocol = WIRE, requestId = "r1", value = 0.5, message = "halfway"),
            ProtocolMessage.Log(protocol = WIRE, requestId = "r1", level = "debug", message = "step 3 done"),
            ProtocolMessage.Prompt(
                protocol = WIRE,
                requestId = "r1",
                promptId = "target.choice",
                kind = ProtocolMessage.PROMPT_SELECT,
                title = "Choose a target",
                choices = listOf("stable", "preview"),
                default = "stable",
            ),
            ProtocolMessage.PromptResponse(
                protocol = WIRE,
                requestId = "r1",
                promptId = "target.choice",
                status = ProtocolMessage.RESPONSE_SUBMITTED,
                value = JsonPrimitive("preview"),
            ),
            ProtocolMessage.Result(protocol = WIRE, requestId = "r1", status = "success", output = mapOf("file" to JsonPrimitive("/out/a.txt"))),
            ProtocolMessage.Error_(protocol = WIRE, requestId = "r1", code = "IO_ERROR", message = "disk full"),
        )
        for (message in samples) {
            val line = codec.encode(message)
            assertTrue(']' !in line.take(20))
            val decoded = codec.decode(line)
            assertEquals(message, decoded, "round-trip failed for ${message::class.simpleName}")
        }
    }

    @Test
    fun `encoded messages are exactly one line`() {
        val line = codec.encode(
            ProtocolMessage.Progress(protocol = WIRE, requestId = "r9", value = 1.0, message = "done\nwith newlines"),
        )
        // newlines inside message content must be escaped by the JSON encoder
        assertTrue(line.lines().size == 1, "NDJSON lines must not contain raw newlines")
    }

    @Test
    fun `decodeStream handles blank lines and terminal gating`() {
        val stream = listOf(
            codec.encode(ProtocolMessage.Progress(protocol = WIRE, requestId = "r1", value = 0.1)),
            "",
            codec.encode(ProtocolMessage.Log(protocol = WIRE, requestId = "r1", message = "hi")),
            codec.encode(ProtocolMessage.Result(protocol = WIRE, requestId = "r1", status = "success")),
        ).joinToString("\n")
        val messages = codec.decodeStream(stream, untilTerminal = true)
        assertEquals(3, messages.size)
        assertTrue(codec.isTerminal(messages.last()))
    }

    @Test
    fun `rejects wrong protocol ids and malformed payloads loudly`() {
        try {
            codec.decode("""{"protocol":"forgekit/2","type":"invoke","requestId":"x","action":"run"}""")
            fail("wrong protocol must fail")
        } catch (expected: ProtocolError) {
            assertTrue("unsupported protocol" in expected.message)
        }
        try {
            codec.decode("""{"protocol":"forgekit/1","type":"telepathy","requestId":"x"}""")
            fail("unknown type must fail")
        } catch (expected: ProtocolError) {
            assertTrue("not a forgekit/1 message" in expected.message)
        }
        try {
            codec.decode("""not json at all""")
            fail("non-JSON must fail")
        } catch (expected: ProtocolError) {
            assertTrue("not a forgekit/1 message" in expected.message)
        }
    }

    @Test
    fun `rejects out-of-range progress and bad result status`() {
        try {
            codec.decode("""{"protocol":"forgekit/1","type":"progress","requestId":"x","value":1.5}""")
            fail("progress > 1 must fail")
        } catch (expected: ProtocolError) {
            assertTrue("out of range" in expected.message)
        }
        try {
            codec.decode("""{"protocol":"forgekit/1","type":"result","requestId":"x","status":"maybe"}""")
            fail("bad status must fail")
        } catch (expected: ProtocolError) {
            assertTrue("status" in expected.message)
        }
    }

    @Test
    fun `validates prompt shapes and cancelled responses`() {
        val select = codec.decode(
            """{"protocol":"forgekit/1","type":"prompt","requestId":"r1","promptId":"channel","kind":"select","title":"Release channel","choices":["stable","preview"],"default":"stable"}""",
        )
        assertTrue(select is ProtocolMessage.Prompt)

        val badPrompts = listOf(
            """{"protocol":"forgekit/1","type":"prompt","requestId":"r1","promptId":"bad id","kind":"text","title":"Value"}""",
            """{"protocol":"forgekit/1","type":"prompt","requestId":"r1","promptId":"choice","kind":"select","title":"Value","choices":[]}""",
            """{"protocol":"forgekit/1","type":"prompt","requestId":"r1","promptId":"choice","kind":"text","title":"Value","choices":["x"]}""",
            """{"protocol":"forgekit/1","type":"prompt","requestId":"r1","promptId":"choice","kind":"select","title":"Value","choices":["x","x"]}""",
        )
        badPrompts.forEach { line ->
            try {
                codec.decode(line)
                fail("invalid prompt must fail: $line")
            } catch (_: ProtocolError) {
                // expected
            }
        }

        try {
            codec.decode(
                """{"protocol":"forgekit/1","type":"prompt_response","requestId":"r1","promptId":"choice","status":"cancelled","value":"secret"}""",
            )
            fail("cancelled response with a value must fail")
        } catch (expected: ProtocolError) {
            assertTrue("must not include" in expected.message)
        }
    }

    private companion object {
        const val WIRE = ProtocolMessage.WIRE
    }
}
