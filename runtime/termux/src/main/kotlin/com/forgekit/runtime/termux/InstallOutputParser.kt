package com.forgekit.runtime.termux

import com.forgekit.runtime.api.InstallPhase

/**
 * Classifies REAL package-manager output (apt/pkg/pip) into an
 * [InstallPhase]. This is a pure state classifier: it reads the observed line
 * and returns the phase that line represents, staying at the previous phase
 * for unrelated/unknown lines. The UI derives progress from these observed
 * markers — there is no fabricated percentage anywhere.
 *
 * Quirks handled:
 *  - apt interleaves download (Get:/Fetched), unpack, configure and trigger
 *    lines as several packages are processed inside one `apt install` call;
 *  - pip's download→build→install sequence uses different wording entirely.
 */
internal object InstallOutputParser {

    /** Phase for the observed [line], or [current] when the line is not a marker. */
    fun classify(line: String, current: InstallPhase): InstallPhase {
        val l = line.trim()
        if (l.isEmpty()) return current
        return when {
            // ---- pip ---------------------------------------------------------
            l.startsWith("Downloading ") ||
                l.startsWith("Collecting ") ||
                l.endsWith(" already downloaded") -> InstallPhase.FETCHING

            l.startsWith("Preparing metadata ") ||
                l.startsWith("Preparing wheel ") ||
                l.startsWith("Building wheel ") ||
                l.startsWith("Running setup.py") ||
                l.startsWith("Getting requirements ") -> InstallPhase.UNPACKING

            l.startsWith("Installing collected packages") ||
                l.startsWith("Successfully installed") ||
                l.startsWith("Successfully uninstalled") ||
                l.startsWith("Uninstalling ") -> InstallPhase.CONFIGURING

            // ---- apt / pkg -----------------------------------------------------
            l.startsWith("Get:") ||
                l.startsWith("Fetched ") -> InstallPhase.FETCHING

            l.startsWith("Selecting previously unselected") ||
                l.startsWith("(Reading database") ||
                l.startsWith("Preparing to unpack") ||
                l.startsWith("Unpacking ") -> InstallPhase.UNPACKING

            l.startsWith("Setting up ") ||
                l.startsWith("Processing triggers") ||
                l.startsWith("Configuring ") ||
                l.startsWith("Removing ") -> InstallPhase.CONFIGURING

            else -> current
        }
    }

    /** True when a line is worth surfacing as a log line (bounded volume). */
    fun isStatusLine(line: String): Boolean {
        val l = line.trim()
        if (l.isEmpty()) return false
        if (l.length > 160) return false
        if (l.startsWith("Get:") && l.contains("http")) return false
        return true
    }
}