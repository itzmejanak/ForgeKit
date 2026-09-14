package com.forgekit.plugin.installer

import com.forgekit.core.filesystem.PluginLayout
import com.forgekit.core.logging.ForgeLogger
import com.forgekit.core.logging.LogCategory
import com.forgekit.plugin.api.PluginError
import com.forgekit.plugin.api.PluginId
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

/**
 * Installs a VALIDATED `.forge` package into the ForgeKit plugin tree and
 * manages its lifecycle on disk (§72 "Install / initialize", §11.1 layout).
 *
 * Contract: this class trusts its input — [ForgePackage.open] did the
 * structural gate, the validator did schema/signature/policy. The installer's
 * own job is honest extraction (no zip-slip, mode preservation, atomic-ish
 * swap) and removal.
 */
public class PackageInstaller(
    /** plugins/ root from ForgePaths. */
    private val pluginsRoot: Path,
    private val logger: ForgeLogger = ForgeLogger(LogCategory.PLUGIN, {}),
) {

    /** Result of one install operation. */
    public data class InstallResult(
        public val pluginId: PluginId,
        public val version: String,
        public val layout: PluginLayout,
        public val replacedPrevious: Boolean,
        public val filesInstalled: Int,
    )

    /**
     * Installs the package: `plugins/<id>/` receives the package areas
     * (runtime/, ui/, …) + the frozen manifest. An existing install of the
     * same id is moved aside and deleted only after the new tree is complete.
     */
    public fun install(pkg: ForgePackage): InstallResult {
        val id = PluginId.parse(pkg.manifest.id)
        val target = pluginsRoot.resolve(id.raw)
        val replaced = Files.isDirectory(target)

        val staging = pluginsRoot.resolve(".staging-${id.raw}-${System.currentTimeMillis()}")
        try {
            extractPackage(pkg, staging)
            // freeze the manifest into the tree root (source of truth from now on)
            Files.write(staging.resolve(ForgePackage.MANIFEST_ENTRY), pkg.rawManifestBytes)

            if (replaced) {
                val backup = pluginsRoot.resolve(".old-${id.raw}-${System.currentTimeMillis()}")
                Files.move(target, backup, StandardCopyOption.ATOMIC_MOVE)
                try {
                    Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
                    backup.toFile().deleteRecursively()
                } catch (e: IOException) {
                    // roll back to the previous tree — never leave a half-installed plugin
                    Files.move(backup, target, StandardCopyOption.REPLACE_EXISTING)
                    throw PluginError("install failed, previous version restored", e.message)
                }
            } else {
                Files.createDirectories(pluginsRoot)
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
            }

            val layout = PluginLayout(target)
            layout.ensureDirectories()
            val count = countFiles(target)
            logger.info(
                "install",
                "installed ${id.raw} ${pkg.manifest.version}: $count files (replaced=$replaced)",
            )
            return InstallResult(id, pkg.manifest.version, layout, replaced, count)
        } finally {
            staging.toFile().deleteRecursively()
        }
    }

    /** Marks an installed plugin ready (§72 run initialization → READY). */
    public fun markReady(pluginId: PluginId) {
        val layout = PluginLayout(pluginsRoot.resolve(pluginId.raw))
        if (!Files.isRegularFile(layout.manifestFile)) {
            throw PluginError("plugin ${pluginId.raw} is not installed")
        }
        layout.ensureDirectories()
        val tmp = layout.readyMarker.resolveSibling(".forgekit-ready.tmp")
        Files.writeString(tmp, "ready")
        Files.move(tmp, layout.readyMarker, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Physically removes the plugin tree. Idempotent for absent ids. */
    public fun remove(pluginId: PluginId): Boolean {
        val target = pluginsRoot.resolve(pluginId.raw)
        return if (Files.isDirectory(target)) {
            target.toFile().deleteRecursively()
            logger.info("remove", "removed ${pluginId.raw}")
            true
        } else false
    }

    /** True when the plugin id is installed on disk. */
    public fun isInstalled(pluginId: PluginId): Boolean =
        Files.isRegularFile(pluginsRoot.resolve(pluginId.raw).resolve(ForgePackage.MANIFEST_ENTRY))

    /** Layout of an installed plugin (throws when missing). */
    public fun layoutOf(pluginId: PluginId): PluginLayout {
        val layout = PluginLayout(pluginsRoot.resolve(pluginId.raw))
        if (!Files.isRegularFile(layout.manifestFile)) {
            throw PluginError("plugin ${pluginId.raw} is not installed", "expected ${layout.manifestFile}")
        }
        return layout
    }

    // ---- internals ------------------------------------------------------------

    private fun extractPackage(pkg: ForgePackage, target: Path) {
        Files.createDirectories(target)
        val modes = runCatching { ZipModeReader.readModes(pkg.file) }.getOrDefault(emptyMap())
        ZipFile(pkg.file.toFile()).use { zip ->
            val zipEntries = zip.entries()
            while (zipEntries.hasMoreElements()) {
                val entry = zipEntries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name.removePrefix("./")
                // re-check at extraction time: the gate ran at open(), but the
                // file on disk could have been swapped since — trust nothing
                if (name.isBlank() || name.startsWith("/") ||
                    name.split('/').any { it == ".." || it.isEmpty() }
                ) {
                    throw PluginError("package entry name unsafe at extract time", name)
                }
                val out = target.resolve(name).toAbsolutePath().normalize()
                if (!out.startsWith(target.toAbsolutePath().normalize())) {
                    throw PluginError("zip-slip attempt detected", name)
                }
                Files.createDirectories(out.parent)
                zip.getInputStream(entry).use { input ->
                    Files.newOutputStream(out).use { output -> input.copyTo(output) }
                }
                modes[name]?.takeIf { it != 0 }?.let { mode ->
                    applyPosixMode(mode, out)
                }
            }
        }
    }

    /** Applies a POSIX mode from the archive's central directory (exec bits for scripts). */
    private fun applyPosixMode(mode: Int, out: Path) {
        // POSIX permission bits (st_mode & 0xFFF): o400/o200/o100 = owner rwx …
        val perms = mutableSetOf<java.nio.file.attribute.PosixFilePermission>()
        if (mode and 0x100 != 0) perms += java.nio.file.attribute.PosixFilePermission.OWNER_READ
        if (mode and 0x080 != 0) perms += java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
        if (mode and 0x040 != 0) perms += java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
        if (mode and 0x020 != 0) perms += java.nio.file.attribute.PosixFilePermission.GROUP_READ
        if (mode and 0x010 != 0) perms += java.nio.file.attribute.PosixFilePermission.GROUP_WRITE
        if (mode and 0x008 != 0) perms += java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE
        if (mode and 0x004 != 0) perms += java.nio.file.attribute.PosixFilePermission.OTHERS_READ
        if (mode and 0x002 != 0) perms += java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE
        if (mode and 0x001 != 0) perms += java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE
        if (perms.isNotEmpty()) {
            runCatching { Files.setPosixFilePermissions(out, perms) }
        }
    }

    private fun countFiles(root: Path): Int {
        var count = 0
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { count++ }
        }
        return count
    }
}
