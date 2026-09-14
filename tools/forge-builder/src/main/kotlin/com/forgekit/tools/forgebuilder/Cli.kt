package com.forgekit.tools.forgebuilder

import com.forgekit.core.security.PublisherKeyPair
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * `forge build <src-dir> [--out <file>] [--key <hex-seed>] [--name <publisher>]`
 *
 * Thin shell over [ForgePackager]; the packager itself is fully testable.
 * The key is a 64-hex-char ed25519 seed. Omit `--key` to build unsigned.
 */
public object Cli {

    @JvmStatic
    public fun main(args: Array<String>) {
        if (args.isEmpty() || args[0] != "build") {
            System.err.println("usage: forge build <src-dir> [--out <file>] [--key <hex-seed>] [--name <publisher>]")
            exitProcess(2)
        }
        var sourceDir: Path? = null
        var out: Path? = null
        var seedHex: String? = null
        var publisherName: String? = null
        var i = 1
        while (i < args.size) {
            when (args[i]) {
                "--out" -> { out = Path.of(args.getOrNull(i + 1) ?: usage("")); i += 2 }
                "--key" -> { seedHex = args.getOrNull(i + 1) ?: usage(""); i += 2 }
                "--name" -> { publisherName = args.getOrNull(i + 1) ?: usage(""); i += 2 }
                else -> {
                    if (sourceDir != null) usage("multiple source directories")
                    sourceDir = Path.of(args[i]); i += 1
                }
            }
        }
        val source = sourceDir ?: usage("missing source directory")
        val key = seedHex?.let { hex ->
            val bytes = hex.chunked(2).map { ch -> ch.toInt(16).toByte() }.toByteArray()
            PublisherKeyPair.fromSeed(bytes)
        }

        val output = out ?: source.resolve("${source.fileName}.forge")
        val result = ForgePackager.build(source, output, key, publisherName)
        println("built ${result.fileCount} entries → ${result.output}")
        println("sha256 ${result.sha256}")
        println(if (result.signed) "signed by ${result.keyFingerprint}" else "UNSIGNED")
    }

    private fun usage(problem: String): Nothing {
        if (problem.isNotBlank()) System.err.println("error: $problem")
        System.err.println("usage: forge build <src-dir> [--out <file>] [--key <hex-seed>] [--name <publisher>]")
        exitProcess(2)
    }
}
