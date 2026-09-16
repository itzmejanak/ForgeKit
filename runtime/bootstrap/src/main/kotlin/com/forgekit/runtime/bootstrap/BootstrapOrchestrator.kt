package com.forgekit.runtime.bootstrap

import com.forgekit.core.logging.ForgeLogger
import com.forgekit.core.logging.LogCategory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Drives the full bootstrap flow (ARCHITECTURE §69):
 * verify → extract → initialize environment → initialize package manager → health check.
 *
 * The orchestrator itself is pure JVM logic; device-specific execution (running the real
 * shell/dpkg inside the prefix) is delegated to the [EnvironmentInitializer] supplied by
 * the composition root (on Android: the embedded runtime).
 */
/** Physical targets of the bootstrap flow, resolved by the composition root from ForgePaths. */
public data class BootstrapTargets(
    public val termuxRoot: Path,
    public val prefix: Path,
    public val home: Path,
    public val tmp: Path,
    public val stagingDir: Path,
) {
    public companion object {
        /**
         * @param stagingDir where the bootstrap archive is staged for verification.
         *   Passed in explicitly rather than derived from [termuxRoot]: the Termux tree
         *   is rooted outside ForgeKit's own directory (see ForgePaths.termuxRootPath),
         *   so a relative hop out of it would land outside the app sandbox entirely.
         */
        public fun of(termuxRoot: Path, stagingDir: Path): BootstrapTargets = BootstrapTargets(
            termuxRoot = termuxRoot,
            prefix = termuxRoot.resolve("usr"),
            home = termuxRoot.resolve("home"),
            tmp = termuxRoot.resolve("usr/tmp"),
            stagingDir = stagingDir.toAbsolutePath().normalize(),
        )
    }
}

public class BootstrapOrchestrator(
    private val targets: BootstrapTargets,
    private val logger: ForgeLogger = ForgeLogger(LogCategory.RUNTIME, {}),
) {

    /** Runs post-extract initialization commands inside the freshly extracted prefix. */
    public fun interface EnvironmentInitializer {
        /**
         * @param prefixDirectory the extracted $PREFIX
         * @param command the command to run with $PREFIX/bin as PATH and $PREFIX as cwd root
         * @return trimmed stdout of the command
         */
        public suspend fun runInPrefix(prefixDirectory: Path, command: List<String>): String
    }

    public sealed interface BootResult {
        /** Bootstrap extracted and initialized. */
        public data class Ready(val extraction: BootstrapExtractor.Result) : BootResult

        /** Verification failed — bootstrap must be replaced before extraction. */
        public data class VerificationFailed(val reason: String) : BootResult

        /** Initialization command failed after successful extraction. */
        public data class InitializationFailed(val reason: String) : BootResult
    }

    // ---- repositories -------------------------------------------------------------

    /** APT repository line for sources.list. */
    public data class AptRepository(
        val url: String,
        val suite: String,
        val components: List<String>,
        val signedBy: String? = null,
    ) {
        public fun toSourcesLine(arch: String): String =
            "deb [arch=$arch${signedBy?.let { " signed-by=$it" } ?: ""}] $url $suite ${components.joinToString(" ")}"
    }

    public companion object {
        /**
         * Relative location of the symlink that redirects `.deb` member paths into the
         * relocated prefix; it points back at the data root (writePackageManagerRelocation).
         */
        private const val REDIRECT_LINK = "data/data/com.termux/files"

        /** ForgeKit's own state dir inside $PREFIX (holds the dpkg-wrapper's find-newer stamp). */
        private const val FORGEKIT_STATE_DIR = "var/lib/forgekit"

        /** Durable transaction marker: package state changed but full relocation is unfinished. */
        private const val RELOCATION_PENDING = "$FORGEKIT_STATE_DIR/relocation-pending"

        /** Bump when an existing prefix must receive a new full relocation pass. */
        private const val RELOCATION_SCHEMA = "$FORGEKIT_STATE_DIR/relocation-v2-complete"

        /** Config and state directories apt expects to exist inside $PREFIX. */
        private val APT_DIRECTORIES = listOf(
            "etc/apt/apt.conf.d",
            "etc/apt/preferences.d",
            "etc/apt/sources.list.d",
            "var/log/apt",
            "var/lib/apt/lists/partial",
            "var/cache/apt/archives/partial",
        )

        /**
         * apt cache directories, relative to the app data root (NOT $PREFIX) — this is
         * where the relocated `Dir::Cache` points.
         */
        private val APT_CACHE_DIRECTORIES = listOf(
            "apt/archives/partial",
            "apt/lists/partial",
        )

        /** Canonical repositories for the apt-android-7 suite (v0.119 line). */
        public fun aptAndroid7Repositories(): List<AptRepository> = listOf(
            AptRepository(
                url = "https://packages.termux.dev/apt/termux-main",
                suite = "main",
                components = listOf("main", "root"),
            ),
        )
    }

    /** True when package presence alone is insufficient to call the prefix ready. */
    public fun relocationReconciliationPending(): Boolean =
        Files.exists(targets.prefix.resolve(RELOCATION_PENDING))

    /**
     * Marks the prefix dirty before any dpkg mutation. The marker is deliberately durable:
     * process death, an OOM or a failed maintainer script must make the next run reconcile
     * instead of treating dpkg's `installed` status as the whole truth.
     */
    public fun markRelocationPending() {
        val marker = targets.prefix.resolve(RELOCATION_PENDING)
        Files.createDirectories(marker.parent)
        Files.writeString(marker, "pending\n")
    }

    private fun markRelocationComplete() {
        val complete = targets.prefix.resolve(RELOCATION_SCHEMA)
        Files.createDirectories(complete.parent)
        Files.writeString(complete, "complete\n")
        // Complete first, clear pending last. A crash between the two safely re-runs repair.
        Files.deleteIfExists(targets.prefix.resolve(RELOCATION_PENDING))
    }

    // ---- steps --------------------------------------------------------------------

    /** Step 1+2: stage the bootstrap, verify it, then extract into $PREFIX. */
    public fun verifyAndExtract(
        descriptor: BootstrapDescriptor,
        verifier: BootstrapVerifier = BootstrapVerifier(logger),
        onProgress: (filesDone: Int) -> Unit = {},
    ): BootResult {
        // stage the bootstrap zip first (assets/native libs are not seekable; the
        // verifier and extractor both need a real file with a central directory)
        val staging: Path
        try {
            staging = Files.createDirectories(targets.stagingDir)
                .resolve("bootstrap-${descriptor.abi}.zip")
            descriptor.source.open().use { input ->
                Files.copy(input, staging, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            return BootResult.VerificationFailed("bootstrap staging failed: ${e.message}")
        }

        // trust gate: full verification of the staged archive before extraction
        val verifierFailure = verifier.verify(staging, descriptor)
        if (verifierFailure != null) return BootResult.VerificationFailed(verifierFailure)

        val result = try {
            BootstrapExtractor().extract(staging, targets.prefix, onProgress)
        } catch (e: Exception) {
            return BootResult.InitializationFailed("extraction failed: ${e.message}")
        }
        logger.info(
            "BootstrapOrchestrator",
            "extracted ${result.filesWritten} files (${result.symlinksCreated} symlinks) into prefix",
        )

        // The packages were built against the Termux app's directories; nothing in the
        // tree works until every absolute reference points where we installed it. Both
        // the data root ($PREFIX/$HOME) and apt's cache root map onto our app data dir.
        try {
            PrefixRewriter(logger).rewrite(targets.prefix, relocationRules(descriptor), relocationSkip())
            markRelocationComplete()
        } catch (e: Exception) {
            return BootResult.InitializationFailed("prefix relocation failed: ${e.message}")
        }
        return BootResult.Ready(result)
    }

    /** Step 3: initialize environment (dirs, sources.list, dpkg state). */
    public fun initializeEnvironment(
        arch: String,
        descriptor: BootstrapDescriptor,
        repositories: List<AptRepository> = aptAndroid7Repositories(),
    ): String? {
        try {
            Files.createDirectories(targets.home)
            Files.createDirectories(targets.tmp)

            val bash = targets.prefix.resolve("bin/bash")
            if (!Files.isRegularFile(bash) && !Files.isSymbolicLink(bash)) {
                return "prefix invalid: \$PREFIX/bin/bash missing after extraction"
            }

            // apt warns on every invocation about config/state dirs it expects to exist;
            // the bootstrap archive only ships etc/apt/sources.list + trusted.gpg.d.
            for (relative in APT_DIRECTORIES) {
                Files.createDirectories(targets.prefix.resolve(relative))
            }
            // apt's Dir::Cache is compiled to point OUTSIDE $PREFIX (Termux keeps it in
            // the app's cache dir); after relocation it resolves under the data root.
            // apt refuses to download if the partial/ directory is absent.
            for (relative in APT_CACHE_DIRECTORIES) {
                Files.createDirectories(targets.termuxRoot.resolve(relative))
            }

            writePackageManagerRelocation(descriptor)
            // Links unpacked verbatim by a terminal apt run before the wrapper repaired links
            // (e.g. termux-keyring's trusted.gpg.d) still point into the Termux app's tree;
            // apt then cannot read any signing key. Re-point them on every start.
            PrefixRewriter(logger).rewriteSymlinks(targets.prefix, relocationRules(descriptor))

            val mainList = targets.prefix.resolve("etc/apt/sources.list")
            // keep the empty main sources.list from confusing apt: bootstrap ships it empty
            if (Files.notExists(mainList)) Files.writeString(mainList, "\n")

            // Only supply repositories when the bootstrap brought none of its own.
            // v0.119 ships a working sources.list; adding ours on top would configure
            // the same suite twice and apt reports that as a duplicate-source error.
            if (!hasEnabledRepository(mainList)) {
                Files.writeString(
                    targets.prefix.resolve("etc/apt/sources.list.d/forgekit.list"),
                    repositories.joinToString("\n") { it.toSourcesLine(arch) } + "\n",
                )
            }
            return null
        } catch (e: Exception) {
            return "environment initialization failed: ${e.message}"
        }
    }

    /**
     * Teaches dpkg and apt how to install packages into a RELOCATED prefix.
     *
     * Termux `.deb` payloads carry absolute member paths (`./data/data/com.termux/files/
     * usr/...`), so a plain dpkg run unpacks them into the Termux app's directory and
     * fails with EACCES. Three settings make it work:
     *
     *  - `instdir` points at an "instroot" containing a single symlink,
     *    `<instroot>/<compiledDataRoot>` -> our data root, so each archive member
     *    resolves through it into our real prefix.
     *  - `admindir` is addressed THROUGH that same symlink. dpkg refuses to run with
     *    "admindir must be inside instdir" otherwise, even though both spellings name
     *    the same directory.
     *  - `force-script-chrootless` stops dpkg chrooting into instdir before maintainer
     *    scripts. Android's seccomp policy kills any app process that calls chroot
     *    (arm64 syscall 51, SIGSYS), which took dpkg down mid-configure.
     */
    private fun writePackageManagerRelocation(descriptor: BootstrapDescriptor) {
        // The redirect lives INSIDE instdir and points back at it, so a member path
        // `./data/data/com.termux/files/usr/bin/x` lands on `<root>/usr/bin/x`.
        val redirect = targets.termuxRoot.resolve(descriptor.compiledDataRoot.trimStart('/'))
        redirect.parent?.let { Files.createDirectories(it) }
        if (!Files.isSymbolicLink(redirect)) {
            if (Files.exists(redirect)) redirect.toFile().deleteRecursively()
            Files.createSymbolicLink(redirect, targets.termuxRoot)
        }

        // instdir MUST be an ancestor of the real prefix. dpkg resolves admindir and
        // then re-applies instdir to it, so an admindir reached through a symlink that
        // leaves instdir makes dpkg silently create an EMPTY shadow database — it then
        // reports every bootstrap package as "not installed" and no dependency can be
        // satisfied. Rooting instdir at the data root keeps admindir a plain descendant.
        val instDir = targets.termuxRoot
        val adminDir = targets.prefix.resolve("var/lib/dpkg")

        Files.createDirectories(targets.prefix.resolve("etc/dpkg/dpkg.cfg.d"))
        Files.writeString(
            targets.prefix.resolve("etc/dpkg/dpkg.cfg.d/forgekit"),
            """
            instdir=$instDir
            admindir=$adminDir
            force-script-chrootless
            """.trimIndent() + "\n",
        )
        Files.writeString(
            targets.prefix.resolve("etc/apt/apt.conf.d/99-forgekit.conf"),
            "DPkg::Options { \"--instdir=$instDir\"; \"--admindir=$adminDir\"; " +
                "\"--force-script-chrootless\"; };\n",
        )

        // Home for the dpkg wrapper's find-newer stamp.
        Files.createDirectories(targets.prefix.resolve(FORGEKIT_STATE_DIR))
        // Existing installations created before this reconciliation contract receive one full,
        // bounded relocation pass. Fresh bootstraps already wrote the completion marker above.
        if (Files.notExists(targets.prefix.resolve(RELOCATION_SCHEMA))) markRelocationPending()
        writeRelocationHook(descriptor)
    }

    /**
     * Wires up the UNSUPERVISED-install path (a user typing `apt`/`pkg install` in the terminal,
     * where ForgeKit is not orchestrating and cannot relocate between phases itself), as a thin
     * dpkg WRAPPER installed via `Dir::Bin::dpkg`.
     *
     * A freshly-unpacked maintainer script's SHEBANG (`#!/data/data/com.termux/files/usr/bin/sh`)
     * — and the shebang of any `bin`/`libexec` helper it calls — is resolved by the kernel with
     * no notion of dpkg's instdir, and `/data/data/com.termux` is a foreign app dir this uid
     * cannot even traverse, so exec fails with EACCES and `dpkg --configure` aborts. apt drives
     * dpkg through `Dir::Bin::dpkg`, invoking it once per operation (`--unpack`, then a separate
     * `--configure`), so the wrapper is the right place — unlike `DPkg::Pre-Invoke`, which apt
     * runs only once per transaction, too early to catch a package unpacked later in the run.
     * Maintainer-script BODIES keep the compiled paths so dpkg's `DPKG_ROOT`/instdir and
     * update-alternatives resolve them through the redirect symlink; only their shebang is
     * rewritten. A package `preinst` is special: dpkg extracts and executes it inside the same
     * `--unpack` call, before this wrapper gets control again. The wrapper therefore inspects each
     * incoming `.deb` control archive and, only when its `preinst` still needs relocation, rebuilds
     * that cached archive with relocated and executable maintainer scripts before invoking dpkg.
     * Runtime files in `bin`/`libexec` and apt's libraries/methods are different: they
     * run without `DPKG_ROOT`, and a newly unpacked dpkg/apt ELF embeds its config path. For the
     * Android layout the old and new roots are exactly the same byte length, so the wrapper safely
     * rewrites every occurrence in runtime files whose ctime changed since its last pass
     * (`find -cnewer`).
     * Ctime is deliberate: package archives preserve old mtimes, so `find -newer` can miss a
     * freshly unpacked file. The wrapper runs the real dpkg, performs the same cheap relocation
     * again afterwards, and normally returns its status. dpkg is the one exceptional self-update:
     * it replaces its own ELF during `--unpack` and can fail before apt gets another wrapper call.
     * If (and only if) the post-pass actually had to relocate the dpkg ELF and the first call
     * failed, the wrapper retries that same operation once against the now-relocated binary.
     *
     * Non-exec references (ELF `DT_RUNPATH`, pkg-config `.pc`, configs) are NOT rewritten here:
     * execution already holds (shebangs fixed above; libraries found via `$LD_LIBRARY_PATH`,
     * which the runtime always sets), and the full [PrefixRewriter] pass runs only where it is
     * safely serialized — at bootstrap and in ForgeKit-orchestrated provisioning
     * ([relocateAndConfigure]). It is deliberately NOT triggered from the app on terminal
     * activity: running `PrefixRewriter` (which edits files without the dpkg lock) concurrently
     * with a terminal apt operation races the transaction and corrupts the package database.
     */
    private fun writeRelocationHook(descriptor: BootstrapDescriptor) {
        val prefix = targets.prefix
        val sh = prefix.resolve("bin/sh")
        val realDpkg = prefix.resolve("bin/dpkg")
        val wrapper = prefix.resolve("libexec/forgekit-dpkg")
        val stamp = prefix.resolve("$FORGEKIT_STATE_DIR/dpkg-reloc-stamp")
        val relocationPending = prefix.resolve(RELOCATION_PENDING)
        Files.createDirectories(wrapper.parent)

        // The dpkg wrapper (Dir::Bin::dpkg): fix stale maintainer-script shebangs and newly
        // changed runtime files before an unpack/configure, then hand off to the real dpkg.
        // `sh` (dash) keeps it free of libreadline, which may itself be mid-upgrade.
        Files.writeString(
            wrapper,
            """
            #!$sh
            # Written by ForgeKit. Thin dpkg front-end wired as Dir::Bin::dpkg. See BootstrapOrchestrator.
            OLD_DATA='${descriptor.compiledDataRoot}'
            OLD_CACHE='${descriptor.compiledCacheRoot}'
            NEW='${targets.termuxRoot}'
            P='$prefix'
            SELF='$wrapper'
            RELOCATION_PENDING='$relocationPending'
            discard_deb_workspace() {
              case "${'$'}1" in
                "${'$'}P/tmp/forgekit-deb."*) rm -rf -- "${'$'}1" ;;
              esac
            }
            relocate_deb_preinsts() {
              for deb in "${'$'}@"; do
                case "${'$'}deb" in *.deb) ;; *) continue ;; esac
                [ -f "${'$'}deb" ] || continue

                work=${'$'}(mktemp -d "${'$'}P/tmp/forgekit-deb.XXXXXX") || return 1
                control="${'$'}work/control"
                if ! "${'$'}P/bin/dpkg-deb" -e "${'$'}deb" "${'$'}control" >/dev/null 2>&1; then
                  echo "ForgeKit: unable to inspect package control archive: ${'$'}deb" >&2
                  discard_deb_workspace "${'$'}work"
                  return 1
                fi

                preinst="${'$'}control/preinst"
                needs_repack=0
                if [ -f "${'$'}preinst" ]; then
                  IFS= read -r first < "${'$'}preinst" 2>/dev/null || first=
                  case "${'$'}first" in
                    '#!'*"${'$'}OLD_DATA"*|'#!'*"${'$'}OLD_CACHE"*) needs_repack=1 ;;
                  esac
                  [ -x "${'$'}preinst" ] || needs_repack=1
                fi

                if [ "${'$'}needs_repack" -eq 1 ]; then
                  tree="${'$'}work/tree"
                  if ! "${'$'}P/bin/dpkg-deb" -R "${'$'}deb" "${'$'}tree" >/dev/null 2>&1; then
                    echo "ForgeKit: unable to unpack package for relocation: ${'$'}deb" >&2
                    discard_deb_workspace "${'$'}work"
                    return 1
                  fi
                  for name in preinst postinst prerm postrm config; do
                    script="${'$'}tree/DEBIAN/${'$'}name"
                    [ -f "${'$'}script" ] || continue
                    IFS= read -r first < "${'$'}script" 2>/dev/null || first=
                    case "${'$'}first" in
                      '#!'*"${'$'}OLD_DATA"*|'#!'*"${'$'}OLD_CACHE"*)
                        sed -i -e "1 s|${'$'}OLD_DATA|${'$'}NEW|g" -e "1 s|${'$'}OLD_CACHE|${'$'}NEW|g" "${'$'}script" || {
                          discard_deb_workspace "${'$'}work"
                          return 1
                        } ;;
                    esac
                    # Termux's exec shim can launch non-executable scripts via their interpreter;
                    # Android's direct exec path cannot, so preserve the normal Debian invariant.
                    chmod 755 "${'$'}script" || {
                      discard_deb_workspace "${'$'}work"
                      return 1
                    }
                  done
                  repacked="${'$'}work/repacked.deb"
                  if ! "${'$'}P/bin/dpkg-deb" --root-owner-group -b "${'$'}tree" "${'$'}repacked" >/dev/null 2>&1 ||
                     ! mv -f "${'$'}repacked" "${'$'}deb"; then
                    echo "ForgeKit: unable to rebuild relocated package: ${'$'}deb" >&2
                    discard_deb_workspace "${'$'}work"
                    return 1
                  fi
                fi
                discard_deb_workspace "${'$'}work"
              done
            }
            relocate_maintainer_shebangs() {
              [ -d "${'$'}P/var/lib/dpkg/info" ] || return 0
              find "${'$'}P/var/lib/dpkg/info" -maxdepth 1 -type f 2>/dev/null |
              while IFS= read -r f; do
                case "${'$'}f" in
                  *.preinst|*.postinst|*.prerm|*.postrm|*.config) ;;
                  *) continue ;;
                esac
                IFS= read -r first < "${'$'}f" 2>/dev/null || continue
                case "${'$'}first" in
                  '#!'*"${'$'}OLD_DATA"*|'#!'*"${'$'}OLD_CACHE"*)
                    sed -i -e "1 s|${'$'}OLD_DATA|${'$'}NEW|g" -e "1 s|${'$'}OLD_CACHE|${'$'}NEW|g" "${'$'}f" 2>/dev/null ;;
                esac
                case "${'$'}first" in '#!'*) chmod 755 "${'$'}f" 2>/dev/null ;; esac
              done
            }
            relocate_runtime_files() {
              stamp='$stamp'
              # Rewriting an ELF with sed is valid only when byte lengths are identical. That
              # invariant holds for the Android roots; a non-device/test root safely skips it.
              [ "${'$'}{#OLD_DATA}" -eq "${'$'}{#NEW}" ] || return 0
              [ "${'$'}{#OLD_CACHE}" -eq "${'$'}{#NEW}" ] || return 0
              {
                for d in "${'$'}P/bin" "${'$'}P/libexec"; do
                  [ -d "${'$'}d" ] || continue
                  if [ -f "${'$'}stamp" ]; then
                    find "${'$'}d" -maxdepth 1 -type f -cnewer "${'$'}stamp" 2>/dev/null
                  else
                    find "${'$'}d" -maxdepth 1 -type f 2>/dev/null
                  fi
                done
                # apt's config roots live in libapt, and its transport methods are ELFs under
                # lib/apt/methods. They must be usable before the next apt invocation.
                for d in "${'$'}P/lib" "${'$'}P/lib/apt"; do
                  [ -d "${'$'}d" ] || continue
                  depth=1
                  [ "${'$'}d" = "${'$'}P/lib/apt" ] && depth=3
                  if [ -f "${'$'}stamp" ]; then
                    find "${'$'}d" -maxdepth "${'$'}depth" -type f -cnewer "${'$'}stamp" 2>/dev/null
                  else
                    find "${'$'}d" -maxdepth "${'$'}depth" -type f 2>/dev/null
                  fi
                done
              } | while IFS= read -r f; do
                # Keep this script's OLD_* values intact: they are the needles used to repair
                # the next package transaction. Rewriting SELF would permanently disable it.
                [ "${'$'}f" = "${'$'}SELF" ] && continue
                grep -a -q -e "${'$'}OLD_DATA" -e "${'$'}OLD_CACHE" "${'$'}f" 2>/dev/null || continue
                sed -i -e "s|${'$'}OLD_DATA|${'$'}NEW|g" -e "s|${'$'}OLD_CACHE|${'$'}NEW|g" "${'$'}f" 2>/dev/null
              done
              : > "${'$'}stamp" 2>/dev/null || touch "${'$'}stamp" 2>/dev/null
            }
            relocate_symlinks() {
              # .deb payloads carry absolute symlinks into the Termux app's tree (e.g. apt's
              # trusted.gpg.d keys). The kernel resolves them literally, so re-point each one
              # into the relocated root. Link targets have no length constraint.
              find "${'$'}P" -type l \( -lname "${'$'}OLD_DATA/*" -o -lname "${'$'}OLD_CACHE/*" \) 2>/dev/null |
              while IFS= read -r link; do
                target=${'$'}(readlink "${'$'}link") || continue
                case "${'$'}target" in
                  "${'$'}OLD_DATA"/*) relocated="${'$'}NEW${'$'}{target#"${'$'}OLD_DATA"}" ;;
                  "${'$'}OLD_CACHE"/*) relocated="${'$'}NEW${'$'}{target#"${'$'}OLD_CACHE"}" ;;
                  *) continue ;;
                esac
                ln -sfn "${'$'}relocated" "${'$'}link" 2>/dev/null
              done
            }
            relocate_real_dpkg() {
              [ "${'$'}{#OLD_DATA}" -eq "${'$'}{#NEW}" ] || return 1
              [ "${'$'}{#OLD_CACHE}" -eq "${'$'}{#NEW}" ] || return 1
              grep -a -q -e "${'$'}OLD_DATA" -e "${'$'}OLD_CACHE" "$realDpkg" 2>/dev/null || return 1
              sed -i -e "s|${'$'}OLD_DATA|${'$'}NEW|g" -e "s|${'$'}OLD_CACHE|${'$'}NEW|g" "$realDpkg" 2>/dev/null
            }
            case " ${'$'}* " in
              *" --configure "*|*" --unpack "*|*" --install "*|*" -i "*)
                # dpkg is about to mutate the prefix. Only ForgeKit's serialized full pass
                # clears this marker after every file and `dpkg --configure -a` succeed.
                : > "${'$'}RELOCATION_PENDING" || exit 2
                relocate_maintainer_shebangs
                relocate_real_dpkg || true
                relocate_runtime_files
                relocate_symlinks
                relocate_deb_preinsts "${'$'}@" || exit 2

                "$realDpkg" "${'$'}@"
                rc=${'$'}?

                # Catch files written by this operation. A failed dpkg self-upgrade is retried
                # exactly once only when this pass proves that the dpkg ELF was replaced stale.
                dpkg_relocated=0
                relocate_real_dpkg && dpkg_relocated=1
                relocate_maintainer_shebangs
                relocate_runtime_files
                relocate_symlinks
                if [ "${'$'}rc" -ne 0 ] && [ "${'$'}dpkg_relocated" -eq 1 ]; then
                  "$realDpkg" "${'$'}@"
                  rc=${'$'}?
                  relocate_maintainer_shebangs
                  relocate_runtime_files
                  relocate_symlinks
                fi
                exit "${'$'}rc" ;;
            esac
            exec "$realDpkg" "${'$'}@"
            """.trimIndent() + "\n",
        )

        runCatching {
            Files.setPosixFilePermissions(
                wrapper,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"),
            )
        }
        Files.writeString(
            prefix.resolve("etc/apt/apt.conf.d/98-forgekit-relocate.conf"),
            "Dir::Bin::dpkg \"$wrapper\";\n",
        )
    }

    /**
     * Relocates files a package manager has just installed, then completes any package
     * configuration that was deferred because of it.
     *
     * Freshly unpacked files come straight out of the `.deb` and still reference the
     * build-time Termux paths — including the shebangs of maintainer scripts and of
     * installed tools like `pip`. Rewriting has to happen before those scripts run, so
     * `dpkg --configure -a` is issued afterwards to finish anything left unconfigured.
     *
     * @return failure detail, or null when the tree is consistent.
     */
    public suspend fun relocateAndConfigure(
        descriptor: BootstrapDescriptor,
        initializer: EnvironmentInitializer,
    ): String? {
        try {
            markRelocationPending()
            PrefixRewriter(logger).rewrite(targets.prefix, relocationRules(descriptor), relocationSkip())
        } catch (e: Exception) {
            return "relocating installed files failed: ${e.message}"
        }
        return try {
            initializer.runInPrefix(
                targets.prefix,
                listOf("${targets.prefix.resolve("bin/dpkg")}", "--configure", "-a"),
            )
            markRelocationComplete()
            null
        } catch (e: Exception) {
            "completing package configuration failed: ${e.message}"
        }
    }

    /** Repairs a dirty/migrated prefix once; clean prefixes do no filesystem walk. */
    public suspend fun reconcileRelocationIfPending(
        descriptor: BootstrapDescriptor,
        initializer: EnvironmentInitializer,
    ): String? = if (relocationReconciliationPending()) {
        relocateAndConfigure(descriptor, initializer)
    } else {
        null
    }

    /** Path substitutions that move a Termux-built tree into ForgeKit's directories. */
    private fun relocationRules(descriptor: BootstrapDescriptor): List<PrefixRewriter.Rule> {
        val installedRoot = targets.termuxRoot.toString()
        return listOf(
            PrefixRewriter.Rule(descriptor.compiledDataRoot, installedRoot),
            PrefixRewriter.Rule(descriptor.compiledCacheRoot, installedRoot),
        )
    }

    /**
     * Excludes ForgeKit's OWN relocation scripts from [PrefixRewriter]. The dpkg wrapper embeds
     * the compiled path as its `OLD_DATA` sed-needle; relocating its body would rewrite that
     * needle to the new prefix and turn the wrapper into a no-op (every stale shebang would then
     * slip through to `dpkg --configure` and fail). The stamp/marker state dir is skipped too.
     */
    private fun relocationSkip(): (Path) -> Boolean {
        val wrapper = targets.prefix.resolve("libexec/forgekit-dpkg").normalize()
        val marker = targets.prefix.resolve("libexec/forgekit-relocate-mark").normalize()
        val stateDir = targets.prefix.resolve(FORGEKIT_STATE_DIR).normalize()
        return { p ->
            val n = p.normalize()
            n == wrapper || n == marker || n.startsWith(stateDir)
        }
    }

    /** True when [sourcesList] already declares at least one non-commented repository. */
    private fun hasEnabledRepository(sourcesList: java.nio.file.Path): Boolean = try {
        Files.readAllLines(sourcesList).any { it.trimStart().startsWith("deb ") }
    } catch (_: Exception) {
        false
    }

    /**
     * Step 4: initialize the package manager (REAL dpkg sanity run).
     *
     * dpkg/apt packages replace their executables and libapt ELFs while the old process is still
     * handling `--unpack`. If Android kills the app in that narrow interval, the wrapper never
     * gets its post-operation pass and the replacements still point at Termux's config directory.
     * Repair only these bootstrap-critical files before trying to launch them. This runs during
     * serialized runtime initialization, so it cannot race a user terminal transaction.
     */
    public suspend fun initializePackageManager(
        descriptor: BootstrapDescriptor,
        initializer: EnvironmentInitializer,
    ): String? {
        val dpkgStatus = targets.prefix.resolve("var/lib/dpkg/status")
        if (!Files.isRegularFile(dpkgStatus)) {
            return "dpkg database missing (var/lib/dpkg/status)"
        }
        val dpkg = targets.prefix.resolve("bin/dpkg")
        if (!Files.isRegularFile(dpkg)) return "dpkg executable missing (bin/dpkg)"
        try {
            val critical = listOf(
                dpkg,
                targets.prefix.resolve("bin/apt"),
                targets.prefix.resolve("bin/apt-cache"),
                targets.prefix.resolve("bin/apt-config"),
                targets.prefix.resolve("bin/apt-get"),
                targets.prefix.resolve("bin/apt-mark"),
                targets.prefix.resolve("lib/libapt-pkg.so"),
                targets.prefix.resolve("lib/libapt-private.so"),
                targets.prefix.resolve("lib/apt"),
            )
            val rewriter = PrefixRewriter(logger)
            for (path in critical) {
                if (Files.exists(path)) rewriter.rewrite(path, relocationRules(descriptor))
            }
        } catch (e: Exception) {
            return "repairing package-manager runtime failed: ${e.message}"
        }
        return try {
            val output = initializer.runInPrefix(
                targets.prefix,
                listOf("$dpkg", "--version"),
            )
            if ("dpkg" in output.lowercase()) null else "dpkg reported: $output"
        } catch (e: Exception) {
            "package manager init failed: ${e.message}"
        }
    }

    /**
     * Wipes the Termux area for a full re-bootstrap (used by [RuntimeRepairer]).
     *
     * Deletes the runtime-owned subtrees individually and NEVER [BootstrapTargets.termuxRoot]
     * itself: the root is the app's data directory, which also holds ForgeKit's database,
     * installed plugins and job history. A repair must not destroy those.
     */
    public fun wipeRuntime(): Boolean = try {
        // Delete the dpkg redirect LINK first, and with Files (which removes the link
        // itself). It points back at the data root, and a recursive walk treats a
        // symlinked directory as a directory — following it would delete ForgeKit's
        // database, plugins and job history along with the runtime.
        Files.deleteIfExists(targets.termuxRoot.resolve(REDIRECT_LINK))
        for (dir in listOf(targets.prefix, targets.home, targets.tmp)) {
            dir.toFile().deleteRecursively()
        }
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * Repairs a broken embedded runtime (ARCHITECTURE §32):
 * wipe → re-verify → re-extract → re-initialize. Never requires app reinstall.
 */
public class RuntimeRepairer(
    private val orchestrator: BootstrapOrchestrator,
    private val logger: ForgeLogger = ForgeLogger(LogCategory.RUNTIME, {}),
) {
    public suspend fun repair(
        descriptor: BootstrapDescriptor,
        initializer: BootstrapOrchestrator.EnvironmentInitializer,
        arch: String,
    ): com.forgekit.runtime.api.RuntimeRepairResult {
        val actions = mutableListOf<String>()
        val wiped = orchestrator.wipeRuntime()
        actions += if (wiped) "wiped termux area" else "wipe failed (continuing with overwrite)"
        val boot = orchestrator.verifyAndExtract(descriptor)
        when (boot) {
            is BootstrapOrchestrator.BootResult.VerificationFailed -> {
                return com.forgekit.runtime.api.RuntimeRepairResult(
                    repaired = false, actions = actions + "verification failed: ${boot.reason}",
                    resultingState = com.forgekit.runtime.api.RuntimeState.FAILED,
                )
            }
            is BootstrapOrchestrator.BootResult.InitializationFailed -> {
                return com.forgekit.runtime.api.RuntimeRepairResult(
                    repaired = false, actions = actions + boot.reason,
                    resultingState = com.forgekit.runtime.api.RuntimeState.FAILED,
                )
            }
            is BootstrapOrchestrator.BootResult.Ready -> actions += "re-extracted ${boot.extraction.filesWritten} files"
        }
        orchestrator.initializeEnvironment(arch, descriptor)?.let {
            return com.forgekit.runtime.api.RuntimeRepairResult(
                repaired = false, actions = actions + it,
                resultingState = com.forgekit.runtime.api.RuntimeState.FAILED,
            )
        }
        orchestrator.initializePackageManager(descriptor, initializer)?.let {
            return com.forgekit.runtime.api.RuntimeRepairResult(
                repaired = false, actions = actions + it,
                resultingState = com.forgekit.runtime.api.RuntimeState.DEGRADED,
            )
        }
        actions += "package manager re-initialized"
        return com.forgekit.runtime.api.RuntimeRepairResult(
            repaired = true, actions = actions,
            resultingState = com.forgekit.runtime.api.RuntimeState.READY,
        )
    }
}
