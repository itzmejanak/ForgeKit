package com.forgekit.runtime.api

/**
 * Observable phases of a single dependency install (ARCHITECTURE §10 pipeline:
 * inspect → check → resolve/install → verify).
 *
 * Phases are derived by parsing the REAL package-manager output (apt/pkg/pip),
 * never assumed from timing. Progress in the UI is the position of these real
 * markers — there is no fabricated percentage.
 */
public enum class InstallPhase {
    /** Presence being verified against the package database. */
    INSPECTING,

    /** Downloading (apt "Get:"/"Fetched", pip "Downloading"). */
    FETCHING,

    /** Unpacking downloaded packages (apt "Unpacking", pip "Preparing wheels"). */
    UNPACKING,

    /** Configuring / installing (apt "Setting up", pip "Installing collected"). */
    CONFIGURING,

    /** Final verification against the package database. */
    VERIFYING,
}