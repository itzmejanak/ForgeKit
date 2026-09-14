package com.forgekit.plugin.registry

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The `forgekit.registry/v1` index format spoken by registry sources
 * (§57/§58). An index is a plain JSON document served at `GET {base}/index.json`;
 * artifacts are served at `GET {base}/{file}`.
 *
 * The codec is strict: unknown keys are rejected (same discipline as
 * forgekit.plugin/v1), so a malformed or hostile index fails loudly.
 */
public object RegistryIndex {

    public const val SCHEMA: String = "forgekit.registry/v1"
    public const val INDEX_ENTRY: String = "index.json"

    private val json = Json {
        ignoreUnknownKeys = false
        prettyPrint = false
        encodeDefaults = true
    }

    @Serializable
    public data class Entry(
        @SerialName("id") public val id: String,
        @SerialName("name") public val name: String,
        @SerialName("version") public val version: String,
        @SerialName("description") public val description: String? = null,
        @SerialName("file") public val file: String,
        @SerialName("sha256") public val sha256: String,
        @SerialName("tags") public val tags: List<String> = emptyList(),
        @SerialName("runtimeType") public val runtimeType: String,
        @SerialName("runtimeVersion") public val runtimeVersion: String? = null,
        @SerialName("permissions") public val permissions: List<String> = emptyList(),
        @SerialName("actions") public val actions: List<String> = emptyList(),
    )

    @Serializable
    public data class Document(
        @SerialName("schema") public val schema: String,
        @SerialName("plugins") public val plugins: List<Entry> = emptyList(),
    )

    public fun decode(bytes: ByteArray): Document {
        val doc = json.decodeFromString(Document.serializer(), bytes.decodeToString())
        if (doc.schema != SCHEMA) {
            throw RegistryError(
                "unsupported registry index schema",
                "expected '$SCHEMA', got '${doc.schema}'",
            )
        }
        return doc
    }

    public fun encode(document: Document): ByteArray =
        json.encodeToString(Document.serializer(), document).encodeToByteArray()
}
