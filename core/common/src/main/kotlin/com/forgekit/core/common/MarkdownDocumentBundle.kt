package com.forgekit.core.common

/**
 * Deterministically joins an ordered set of maintainable Markdown sources.
 *
 * The order file is deliberately plain text: one relative `*.md` file name per
 * line, with blank lines and `#` comments ignored. Paths are single-segment so
 * a bundle cannot escape its asset/source directory.
 */
public object MarkdownDocumentBundle {

    public fun sourceOrder(orderDocument: String): List<String> {
        val names = orderDocument
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .toList()

        require(names.isNotEmpty()) { "documentation order lists no Markdown sources" }
        require(names.distinct().size == names.size) { "documentation order contains duplicate sources" }
        names.forEach { name ->
            require(name.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]*\\.md$"))) {
                "documentation source name is unsafe: '$name'"
            }
        }
        return names
    }

    /** Produces one LF-normalized document with exactly one trailing newline. */
    public fun merge(orderDocument: String, readSource: (String) -> String): String =
        sourceOrder(orderDocument).joinToString("\n\n") { name ->
            val source = readSource(name)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim()
            require(source.isNotEmpty()) { "documentation source is empty: '$name'" }
            source
        } + "\n"
}
