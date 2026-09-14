package com.forgekit.app.screens.component

private const val ESC = ''

/**
 * Terminal escape sequences (SGR colors, cursor moves, OSC titles) that tools print even when
 * their output is captured. Job and provisioning consoles show plain text lines, so these are
 * removed instead of appearing as `[31m` fragments. The interactive terminal renders them.
 */
private val ANSI_SEQUENCE = Regex(
    "\\x1B\\[[0-?]*[ -/]*[@-~]" + // CSI … final byte
        "|\\x1B\\][^\\x07\\x1B]*(?:\\x07|\\x1B\\\\)" + // OSC … BEL or ST
        "|\\x1B[@-Z\\\\-_]", // two-byte escapes
)

internal fun stripAnsi(text: String): String =
    if (text.indexOf(ESC) < 0) text else ANSI_SEQUENCE.replace(text, "")
