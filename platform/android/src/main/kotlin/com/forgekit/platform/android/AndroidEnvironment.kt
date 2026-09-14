package com.forgekit.platform.android

import android.content.Context
import com.forgekit.core.filesystem.ForgePaths
import java.io.InputStream
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Android environment hooks (STRUCTURE.md §4 `platform/android`): the ONLY place
 * allowed to touch [Context] and resolve device storage locations for the pure
 * JVM core (§7.3 rule 9 — no absolute platform paths outside this module).
 *
 * The ForgeKit root is `context.filesDir` — app-private, writable, and executable
 * (targetSdk 28 keeps the W^X policy that allows running binaries from app data,
 * which the embedded Termux runtime requires).
 */
public class AndroidEnvironment(
    private val context: Context,
) {

    /**
     * ForgeKit-owned filesystem root (§11): {filesDir}/app, /forge — plus the Termux
     * tree, which is deliberately rooted at `/data/data/<package>` instead of under
     * filesDir.
     *
     * Reason: Termux bootstrap binaries carry their build-time prefix
     * (`/data/data/com.termux/files/usr`, 31 bytes) compiled in — 556 files in the
     * v0.119 bootstrap reference it, including 308 ELF binaries. Relocating the
     * runtime means patching that string in place, which cannot lengthen it. With
     * this root, $PREFIX is `/data/data/com.forgekit.app/usr` — exactly 31 bytes,
     * a byte-for-byte substitution needing no padding.
     *
     * `/data/data/<pkg>` and `/data/user/0/<pkg>` are the same directory for user 0;
     * the literal `/data/data` form is used because it is what gets patched into the
     * binaries. Like upstream Termux, the runtime is a user-0 feature.
     */
    public fun forgePaths(): ForgePaths = ForgePaths(
        root = Paths.get(context.filesDir.absolutePath),
        termuxRootPath = Paths.get(LEGACY_DATA_ROOT, context.packageName),
        outputRootPath = publicOutputDirectory(),
    )

    /**
     * User-visible output tree: `/sdcard/ForgeKit`. Job and plugin results go here so
     * they outlive the app's private data and can be opened by a file manager or
     * shared. Reachable from the runtime as `$FORGEKIT_OUTPUT`.
     *
     * Writing here needs WRITE_EXTERNAL_STORAGE to be granted at runtime; the path is
     * resolved regardless so the UI can show where output will land, and
     * [ensureOutputDirectory] reports whether it is actually usable.
     */
    public fun publicOutputDirectory(): Path =
        Paths.get(android.os.Environment.getExternalStorageDirectory().absolutePath, OUTPUT_DIR_NAME)

    /**
     * Creates the public output directory if permitted.
     *
     * @return the directory, or null when it cannot be created (permission not granted
     *   yet, or no external storage) — callers fall back to app-private job storage.
     */
    public fun ensureOutputDirectory(): Path? {
        if (android.os.Environment.getExternalStorageState() != android.os.Environment.MEDIA_MOUNTED) {
            return null
        }
        val dir = publicOutputDirectory()
        return runCatching { java.nio.file.Files.createDirectories(dir) }.getOrNull()
    }

    /** True when the runtime may write to [publicOutputDirectory]. */
    public fun canWriteOutputDirectory(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Physical root directory backing [forgePaths]. */
    public fun rootDirectory(): Path = Paths.get(context.filesDir.absolutePath)

    /** True when the device ABI is supported by the bundled bootstrap (arm64-v8a). */
    public fun isSupportedAbi(): Boolean =
        supportedAbis().any { it == ForgeAndroidBootstrap.ABI_ARM64_V8A }

    /** Device ABIs ordered by preference (most preferred first). */
    public fun supportedAbis(): List<String> =
        BuildAbiHelper.deviceAbis(context)

    /** Cache dir for transient downloads (never holds runtime state). */
    public fun cacheDirectory(): Path = Paths.get(context.cacheDir.absolutePath)

    private companion object {
        /** Same directory as `/data/user/0` for user 0, and the form compiled into binaries. */
        const val LEGACY_DATA_ROOT = "/data/data"

        /** Public output tree name under external storage. */
        const val OUTPUT_DIR_NAME = "ForgeKit"
    }
}

/** Device ABI resolution (isolated for testability). */
public object BuildAbiHelper {
    public fun deviceAbis(context: Context): List<String> {
        // Supported ABIs as the platform installer sees them, then the build list
        // as fallback — both are device truth, merged and de-duplicated in order.
        val buildAbis = android.os.Build.SUPPORTED_ABIS?.toList() ?: emptyList()
        return buildAbis.filter { it.isNotBlank() }.distinct()
    }
}

/**
 * The bootstrap archive bundled in the APK (ARCHITECTURE §33: the APK carries an
 * architecture-specific Termux bootstrap). Assets are uncompressed and streamed
 * straight into the runtime staging area by the bootstrap orchestrator.
 */
public object ForgeAndroidBootstrap {

    public const val ABI_ARM64_V8A: String = "arm64-v8a"

    /** Asset path of the pinned Termux apt-android-7 bootstrap (see Docs/UPSTREAM.md). */
    public const val ASSET_PATH_ARM64: String = "bootstrap/bootstrap-arm64-v8a.zip"

    /** Pinned SHA-256 of the bundled arm64 bootstrap. */
    public const val SHA256_ARM64: String =
        "c8d702b6f742935001c37cda81b8ac69504a95d5cf28f2899532dd8cd4b057eb"

    /** Exact byte size of the bundled arm64 bootstrap. */
    public const val SIZE_ARM64: Long = 29_388_903L

    /** Package-repository suite used by the bundled bootstrap. */
    public const val TERMUX_SUITE: String = "apt-android-7"

    /**
     * True when the APK actually contains the bootstrap asset for [abi]
     * (guards development builds that stripped it for size).
     */
    public fun isBundled(context: Context, abi: String = ABI_ARM64_V8A): Boolean = try {
        context.assets.open(assetPathFor(abi)).use { it.read() >= 0 }
        true
    } catch (_: Exception) {
        false
    }

    /** Asset path for an ABI. */
    public fun assetPathFor(abi: String): String = "bootstrap/bootstrap-$abi.zip"

    /** [com.forgekit.runtime.bootstrap.BootstrapSource] view over the bundled asset. */
    public fun assetSource(context: Context, abi: String = ABI_ARM64_V8A): AndroidAssetSource =
        AndroidAssetSource(context, assetPathFor(abi))
}

/**
 * Re-openable [com.forgekit.runtime.bootstrap.BootstrapSource] over an APK asset.
 * Each [open] returns a FRESH stream — the contract requires re-openability
 * because the orchestrator stages once and the verifier may re-read.
 */
public class AndroidAssetSource(
    private val context: Context,
    private val assetPath: String,
) : com.forgekit.runtime.bootstrap.BootstrapSource {
    override fun open(): InputStream = context.assets.open(assetPath)
}
