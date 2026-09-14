package com.forgekit.plugin.protocol

import com.forgekit.core.common.ForgeContracts
import com.forgekit.plugin.api.PluginError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * `forgekit/1` wire protocol (ARCHITECTURE §15-16): the ONLY application-level
 * communication channel between the platform and plugin processes.
 *
 * stdout/stderr stay diagnostics; structured JSON lines on the protocol
 * stream carry invoke / progress / log / prompt / prompt_response / result /
 * error. One JSON document per line (NDJSON): simple to frame, trivial to
 * parse on both sides.
 */
@Serializable
public sealed interface ProtocolMessage {

    /** Wire protocol id — always `forgekit/1` in v1. */
    public val protocol: String

    /** Correlates events with the originating invocation. */
    public val requestId: String

    /** The request side (platform → plugin): run an action with input values. */
    @Serializable
    @SerialName("invoke")
    public data class Invoke(
        override val protocol: String,
        override val requestId: String,
        public val action: String,
        /** Input values keyed by control id, plus option selections. */
        public val input: Map<String, JsonElement> = emptyMap(),
    ) : ProtocolMessage

    /** Progress event (plugin → platform, streaming 0..1). */
    @Serializable
    @SerialName("progress")
    public data class Progress(
        override val protocol: String,
        override val requestId: String,
        public val value: Double,
        public val message: String? = null,
    ) : ProtocolMessage

    /** Human-readable diagnostic log line from the plugin (distinct from stderr). */
    @Serializable
    @SerialName("log")
    public data class Log(
        override val protocol: String,
        override val requestId: String,
        public val level: String = "info",
        public val message: String,
    ) : ProtocolMessage

    /** A bounded host interaction requested by a running plugin. */
    @Serializable
    @SerialName("prompt")
    public data class Prompt(
        override val protocol: String,
        override val requestId: String,
        public val promptId: String,
        /** One of confirm|text|password|select. */
        public val kind: String,
        public val title: String,
        public val message: String? = null,
        public val required: Boolean = true,
        /** Required and non-empty only for select prompts. */
        public val choices: List<String> = emptyList(),
        public val default: String? = null,
        public val placeholder: String? = null,
    ) : ProtocolMessage

    /** Host response to one [Prompt]. Values are never copied into job events. */
    @Serializable
    @SerialName("prompt_response")
    public data class PromptResponse(
        override val protocol: String,
        override val requestId: String,
        public val promptId: String,
        /** submitted|cancelled */
        public val status: String,
        public val value: JsonElement? = null,
    ) : ProtocolMessage

    /** Terminal success (plugin → platform). */
    @Serializable
    @SerialName("result")
    public data class Result(
        override val protocol: String,
        override val requestId: String,
        public val status: String,
        /** Output values keyed by declared output id. */
        public val output: Map<String, JsonElement> = emptyMap(),
    ) : ProtocolMessage

    /** Terminal failure (plugin → platform). */
    @Serializable
    @SerialName("error")
    public data class Error_(
        override val protocol: String,
        override val requestId: String,
        public val code: String = "PLUGIN_ERROR",
        public val message: String,
    ) : ProtocolMessage

    public companion object {
        public const val WIRE: String = ForgeContracts.PROTOCOL_WIRE

        /** Status values for [Result]. */
        public const val RESULT_SUCCESS: String = "success"
        public const val RESULT_FAILURE: String = "failure"
        public const val PROMPT_CONFIRM: String = "confirm"
        public const val PROMPT_TEXT: String = "text"
        public const val PROMPT_PASSWORD: String = "password"
        public const val PROMPT_SELECT: String = "select"
        public const val RESPONSE_SUBMITTED: String = "submitted"
        public const val RESPONSE_CANCELLED: String = "cancelled"
    }
}

/** Protocol-domain errors. */
public class ProtocolError(message: String, detail: String? = null) : PluginError(message, detail)

/**
 * Real NDJSON codec for the wire protocol: strict validation, one message per
 * line, unambiguous failures (never silent drops).
 */
public class ProtocolCodec(
    private val json: Json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    },
) {

    /** Serializes one message to exactly one NDJSON line (no trailing newline). */
    public fun encode(message: ProtocolMessage): String {
        validateWire(message)
        return json.encodeToString(ProtocolMessage.serializer(), message)
    }

    /** Parses one NDJSON line. Blank lines are skipped by [decodeStream], not here. */
    public fun decode(line: String): ProtocolMessage {
        if (line.length > MAX_WIRE_CHARS) {
            throw ProtocolError("protocol line exceeds $MAX_WIRE_CHARS characters")
        }
        val message = try {
            json.decodeFromString(ProtocolMessage.serializer(), line)
        } catch (e: Exception) {
            throw ProtocolError("line is not a forgekit/1 message", "${e.message} — line: ${line.take(200)}")
        }
        validateWire(message)
        return message
    }

    /**
     * Decodes a full stream: splits on newlines, skips blanks, stops parsing at
     * the first terminal message for a request when [untilTerminal] is set.
     */
    public fun decodeStream(
        stream: String,
        untilTerminal: Boolean = false,
    ): List<ProtocolMessage> {
        val messages = mutableListOf<ProtocolMessage>()
        var terminated = false
        for (line in stream.lineSequence()) {
            if (line.isBlank()) continue
            val message = decode(line)
            if (terminated) {
                throw ProtocolError("message after terminal event", line.take(200))
            }
            messages += message
            if (untilTerminal && isTerminal(message)) terminated = true
        }
        return messages
    }

    /** Terminal messages end an invocation: result or error. */
    public fun isTerminal(message: ProtocolMessage): Boolean =
        message is ProtocolMessage.Result || message is ProtocolMessage.Error_

    private fun validateWire(message: ProtocolMessage) {
        if (message.protocol != ProtocolMessage.WIRE) {
            throw ProtocolError("unsupported protocol '${message.protocol}'", "expected ${ProtocolMessage.WIRE}")
        }
        if (message.requestId.isBlank()) {
            throw ProtocolError("requestId must not be blank")
        }
        if (message.requestId.length > MAX_REQUEST_ID_CHARS) {
            throw ProtocolError("requestId exceeds $MAX_REQUEST_ID_CHARS characters")
        }
        when (message) {
            is ProtocolMessage.Progress -> {
                if (message.value < 0.0 || message.value > 1.0) {
                    throw ProtocolError("progress value out of range", "${message.value}")
                }
            }
            is ProtocolMessage.Result -> {
                if (message.status != ProtocolMessage.RESULT_SUCCESS &&
                    message.status != ProtocolMessage.RESULT_FAILURE
                ) {
                    throw ProtocolError("result status must be success|failure", message.status)
                }
            }
            is ProtocolMessage.Invoke -> {
                if (message.action.isBlank()) throw ProtocolError("invoke action must not be blank")
            }
            is ProtocolMessage.Prompt -> validatePrompt(message)
            is ProtocolMessage.PromptResponse -> {
                validatePromptId(message.promptId)
                if (message.status != ProtocolMessage.RESPONSE_SUBMITTED &&
                    message.status != ProtocolMessage.RESPONSE_CANCELLED
                ) {
                    throw ProtocolError("prompt response status must be submitted|cancelled", message.status)
                }
                if (message.status == ProtocolMessage.RESPONSE_CANCELLED && message.value != null) {
                    throw ProtocolError("cancelled prompt response must not include a value")
                }
                if (message.value != null &&
                    (message.value !is JsonPrimitive || !message.value.isString)
                ) {
                    throw ProtocolError("prompt response value must be a JSON string or null")
                }
                if ((message.value as? JsonPrimitive)?.content?.length?.let { it > MAX_RESPONSE_CHARS } == true) {
                    throw ProtocolError("prompt response exceeds $MAX_RESPONSE_CHARS characters")
                }
            }
            else -> Unit
        }
    }

    private fun validatePrompt(message: ProtocolMessage.Prompt) {
        validatePromptId(message.promptId)
        if (message.title.isBlank()) throw ProtocolError("prompt title must not be blank")
        if (message.title.length > MAX_TITLE_CHARS) {
            throw ProtocolError("prompt title exceeds $MAX_TITLE_CHARS characters")
        }
        if (message.message?.length?.let { it > MAX_MESSAGE_CHARS } == true) {
            throw ProtocolError("prompt message exceeds $MAX_MESSAGE_CHARS characters")
        }
        if (message.placeholder?.length?.let { it > MAX_PLACEHOLDER_CHARS } == true) {
            throw ProtocolError("prompt placeholder exceeds $MAX_PLACEHOLDER_CHARS characters")
        }
        if (message.default?.length?.let { it > MAX_RESPONSE_CHARS } == true) {
            throw ProtocolError("prompt default exceeds $MAX_RESPONSE_CHARS characters")
        }
        val kinds = setOf(
            ProtocolMessage.PROMPT_CONFIRM,
            ProtocolMessage.PROMPT_TEXT,
            ProtocolMessage.PROMPT_PASSWORD,
            ProtocolMessage.PROMPT_SELECT,
        )
        if (message.kind !in kinds) {
            throw ProtocolError("prompt kind must be confirm|text|password|select", message.kind)
        }
        if (message.kind == ProtocolMessage.PROMPT_SELECT) {
            if (message.choices.isEmpty()) throw ProtocolError("select prompt requires choices")
            if (message.choices.size > MAX_CHOICES) {
                throw ProtocolError("select prompt exceeds $MAX_CHOICES choices")
            }
            if (message.choices.any(String::isBlank)) throw ProtocolError("select choices must not be blank")
            if (message.choices.any { it.length > MAX_CHOICE_CHARS }) {
                throw ProtocolError("select choice exceeds $MAX_CHOICE_CHARS characters")
            }
            if (message.choices.distinct().size != message.choices.size) {
                throw ProtocolError("select choices must be unique")
            }
            if (message.default != null && message.default !in message.choices) {
                throw ProtocolError("select default must be one of choices", message.default)
            }
        } else if (message.choices.isNotEmpty()) {
            throw ProtocolError("choices are only valid for select prompts")
        }
        if (message.kind == ProtocolMessage.PROMPT_CONFIRM &&
            message.default != null && message.default !in setOf("true", "false")
        ) {
            throw ProtocolError("confirm default must be true|false", message.default)
        }
        if (message.kind == ProtocolMessage.PROMPT_PASSWORD && message.default != null) {
            throw ProtocolError("password prompt must not include a default")
        }
    }

    private fun validatePromptId(promptId: String) {
        if (!PROMPT_ID.matches(promptId)) {
            throw ProtocolError(
                "invalid promptId",
                "use 1-64 ASCII letters, digits, dot, underscore, or hyphen; start with a letter or digit",
            )
        }
    }

    private companion object {
        val PROMPT_ID: Regex = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        const val MAX_WIRE_CHARS: Int = 65_536
        const val MAX_REQUEST_ID_CHARS: Int = 128
        const val MAX_TITLE_CHARS: Int = 120
        const val MAX_MESSAGE_CHARS: Int = 1_000
        const val MAX_PLACEHOLDER_CHARS: Int = 160
        const val MAX_CHOICES: Int = 32
        const val MAX_CHOICE_CHARS: Int = 160
        const val MAX_RESPONSE_CHARS: Int = 16_384
    }
}
