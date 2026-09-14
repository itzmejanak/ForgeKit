package com.forgekit.core.filesystem

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves the logical ForgeKit filesystem contract (STRUCTURE.md §11) against a physical root.
 *
 * The root is injected once by the platform layer (`platform/android` on the device, temp dirs in tests).
 * No other module is allowed to embed absolute platform paths (§7.3 rule 9).
 */
public class ForgePaths(
    /** Application-owned root: everything ForgeKit manages lives below this directory. */
    public val root: Path,
    /**
     * Root of the Termux-owned tree ($PREFIX/$HOME live directly below it).
     *
     * Decoupled from [root] because the prefix path is not free: Termux bootstrap
     * binaries have their build-time prefix compiled in, and relocating them means
     * byte-patching that string in place — which requires the replacement to be no
     * longer than the original (`/data/data/com.termux/files/usr`, 31 bytes). The
     * platform layer therefore picks a short root; ForgeKit's own tree stays under
     * [root]. See PrefixRewriter.
     */
    termuxRootPath: Path = root.resolve("termux"),
    /**
     * User-visible output tree for job/plugin results (on Android: `/sdcard/ForgeKit`).
     * Defaults inside [root] so host tests and non-Android hosts stay self-contained.
     */
    public val outputRootPath: Path = root.resolve("output"),
) {
    init {
        require(root.isAbsolute) { "ForgePaths root must be absolute: $root" }
        require(termuxRootPath.isAbsolute) { "ForgePaths termux root must be absolute: $termuxRootPath" }
        require(outputRootPath.isAbsolute) { "ForgePaths output root must be absolute: $outputRootPath" }
    }

    // ---- application data -------------------------------------------------

    /** Android application data (Room DB, settings, logs). */
    public val appDir: Path = root.resolve("app")

    /** ForgeKit-generated shell configuration (interactive terminal profile). */
    public val shellDir: Path = appDir.resolve("shell")

    /** ForgeKit-owned plugin area. */
    public val forgeDir: Path = root.resolve("forge")

    /** Per-plugin trees. */
    public val pluginsDir: Path = forgeDir.resolve("plugins")

    /** Job artifacts (inputs/outputs/logs). */
    public val jobsDir: Path = forgeDir.resolve("jobs")

    /** Plugin source metadata/cache. */
    public val registryDir: Path = forgeDir.resolve("registry")

    /** Reserved ForgeKit metadata. */
    public val metadataDir: Path = forgeDir.resolve("metadata")

    // ---- Termux-owned runtime area (ARCHITECTURE §11: termux owns termux/) --

    public val termuxDir: Path = termuxRootPath

    /** $PREFIX — app + shell + packages. */
    public val termuxPrefix: Path = termuxDir.resolve("usr")

    /** $HOME. */
    public val termuxHome: Path = termuxDir.resolve("home")

    /** $TMPDIR. */
    public val termuxTmp: Path = termuxPrefix.resolve("tmp")

    /** dpkg/apt state lives inside $PREFIX (var/lib/dpkg …). */
    public val termuxPackages: Path = termuxPrefix.resolve("var/lib/dpkg")

    /** Per-plugin managed tree (ARCHITECTURE §11: never write plugin data directly into $HOME). */
    public fun pluginDir(pluginId: String): Path = pluginsDir.resolve(safeSegment(pluginId))

    public fun pluginLayout(pluginId: String): PluginLayout = PluginLayout(pluginDir(pluginId))

    public fun jobDir(jobId: String): Path = jobsDir.resolve(safeSegment(jobId))

    public fun jobLogsDir(jobId: String): Path = jobDir(jobId).resolve("logs")

    /** Environment the runtime exec layer must apply (ARCHITECTURE §8). */
    public fun termuxEnvironment(): Map<String, String> = buildMap {
        put("HOME", termuxHome.toAbsolutePath().toString())
        put("PREFIX", termuxPrefix.toAbsolutePath().toString())
        // termux-exec 2.x consumes the scoped names. Without them it falls back to the
        // com.termux paths compiled into the shared library, so LD_PRELOAD alone cannot map
        // Linux shebangs into ForgeKit's relocated rootfs.
        put("TERMUX_APP__DATA_DIR", termuxDir.toAbsolutePath().toString())
        put("TERMUX__ROOTFS", termuxDir.toAbsolutePath().toString())
        put("TERMUX__HOME", termuxHome.toAbsolutePath().toString())
        put("TERMUX__PREFIX", termuxPrefix.toAbsolutePath().toString())
        put("PATH", termuxPrefix.resolve("bin").toAbsolutePath().toString())
        put("LD_LIBRARY_PATH", termuxPrefix.resolve("lib").toAbsolutePath().toString())
        // Termux packages and maintainer scripts contain Linux/Termux shebangs that Android's
        // kernel cannot resolve directly. The official execution environment keeps termux-exec
        // preloaded so its execve interceptor maps those interpreters through this prefix. This
        // complements the dpkg wrapper's archive repair for ordinary commands and scripts.
        val termuxExec = termuxPrefix.resolve("lib/libtermux-exec.so").toAbsolutePath()
        if (Files.isRegularFile(termuxExec)) put("LD_PRELOAD", termuxExec.toString())
        put("TMPDIR", termuxTmp.toAbsolutePath().toString())
        put("TERM", "xterm-256color")
        // ncurses (clear/tput/less/vim …) has the ORIGINAL Termux terminfo path compiled in;
        // after prefix relocation that path no longer exists, so without an explicit TERMINFO
        // every terminfo lookup fails with "terminals database is inaccessible". Point it at
        // the relocated database.
        put("TERMINFO", termuxPrefix.resolve("share/terminfo").toAbsolutePath().toString())
        put("LANG", "en_US.UTF-8")
        put("SHELL", termuxPrefix.resolve("bin/bash").toAbsolutePath().toString())
        // Where a plugin or shell command should write results the user will look for.
        put("FORGEKIT_OUTPUT", outputRootPath.toAbsolutePath().toString())
    }

    /** Creates the directory skeleton (idempotent). */
    public fun ensureSkeleton() {
        for (dir in listOf(appDir, pluginsDir, jobsDir, registryDir, metadataDir, termuxHome, termuxTmp)) {
            Files.createDirectories(dir)
        }
    }

    /** Validates that a resolved path stays inside the given base (policy layer uses this for grants). */
    public fun isInside(base: Path, candidate: Path): Boolean {
        val normalizedBase = base.toAbsolutePath().normalize()
        val normalizedCandidate = candidate.toAbsolutePath().normalize()
        return normalizedCandidate == normalizedBase || normalizedCandidate.startsWith(normalizedBase)
    }

    private fun safeSegment(id: String): String {
        require(id.isNotBlank() && id.matches(Regex("""[A-Za-z0-9._-]+"""))) {
            "unsafe path segment: '$id'"
        }
        return id
    }

    public companion object {
        /** Creates paths rooted at the given absolute directory. */
        public fun at(rootPath: String): ForgePaths = ForgePaths(Paths.get(rootPath))
    }
}
