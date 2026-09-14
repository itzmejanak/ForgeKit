package com.forgekit.app

import android.app.Application
import android.util.Log
import com.forgekit.core.logging.ForgeLogger
import com.forgekit.core.logging.LogCategory
import com.forgekit.core.logging.LogEntry
import com.forgekit.core.logging.LogSink
import com.forgekit.core.model.ForgeError
import com.forgekit.platform.android.AndroidEnvironment
import com.forgekit.platform.android.ForgeAndroidBootstrap
import com.forgekit.runtime.api.ForgeRuntime
import com.forgekit.runtime.api.RuntimeState
import kotlinx.coroutines.flow.asStateFlow
import com.forgekit.runtime.bootstrap.BootstrapDescriptor
import com.forgekit.runtime.termux.EmbeddedTermuxRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ForgeKit composition root (STRUCTURE.md §2: composition lives in `app`,
 * constructor DI — no DI framework, no service locator).
 *
 * Owns the process-wide runtime instance and the exact §69 first-run flow:
 *   check ABI → verify bundled bootstrap → extract → initialize environment →
 *   initialize package manager → health checks → READY.
 *
 * The runtime is created eagerly (cheap object graph) but initialized on a
 * background supervisor scope: the UI observes [runtime] state changes and
 * never blocks the main thread on bootstrap work.
 */
class ForgeKitApplication : Application() {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Bridge sink: structured ForgeKit entries → logcat (production diagnostics). */
    private val logcatSink = LogSink { entry ->
        val tag = "ForgeKit/${entry.category.name.lowercase()}"
        when (entry.level) {
            com.forgekit.core.logging.LogLevel.DEBUG -> Log.d(tag, "${entry.tag}: ${entry.message}")
            com.forgekit.core.logging.LogLevel.INFO -> Log.i(tag, "${entry.tag}: ${entry.message}")
            com.forgekit.core.logging.LogLevel.WARN -> Log.w(tag, "${entry.tag}: ${entry.message}")
            com.forgekit.core.logging.LogLevel.ERROR -> Log.e(tag, "${entry.tag}: ${entry.message}")
        }
    }

    private val logger = ForgeLogger(LogCategory.RUNTIME, logcatSink)

    /** The single embedded runtime instance for this process. */
    val runtime: ForgeRuntime by lazy { buildRuntime() }

    /** Human-readable failure detail of the last initialize attempt (UI surface). */
    @Volatile
    var lastInitError: String? = null
        private set

    /** Flow version of [lastInitError] for Compose collection. */
    private val _lastInitErrorFlow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    public val lastInitErrorFlow: kotlinx.coroutines.flow.StateFlow<String?> = _lastInitErrorFlow.asStateFlow()

    /** Plugin trees root (filesDir/forge/plugins — §11.1 layout). */
    public fun pluginsRoot(): java.nio.file.Path =
        java.nio.file.Paths.get(filesDir.absolutePath, "forge", "plugins")

    /** The single Android environment view (the only layer that resolves platform paths). */
    public val environment: AndroidEnvironment by lazy { AndroidEnvironment(this) }

    /** Split developer-guide assets → one public Downloads document. */
    public val developerDocsExporter: com.forgekit.platform.android.DeveloperDocsExporter by lazy {
        com.forgekit.platform.android.DeveloperDocsExporter(this)
    }

    /**
     * Creates the user-visible output directory once storage permission is granted.
     * Safe to call repeatedly — logs the outcome so the path is visible in diagnostics.
     */
    public fun ensureOutputDirectory() {
        val target = environment.publicOutputDirectory()
        if (!environment.canWriteOutputDirectory()) {
            logger.warn("output", "storage permission not granted — $target is unavailable")
            return
        }
        val created = environment.ensureOutputDirectory()
        if (created != null) {
            logger.info("output", "job output directory ready: $created")
        } else {
            logger.warn("output", "could not create output directory: $target")
        }
    }

    override fun onCreate() {
        super.onCreate()
        appScope.launch { initializeRuntime() }
    }

    /**
     * Runs the real bootstrap flow; safe to call repeatedly (initialize is
     * idempotent — READY returns immediately, per the frozen contract).
     */
    suspend fun initializeRuntime(): RuntimeState = try {
        lastInitError = null
        _lastInitErrorFlow.value = null
        runtime.initialize()
    } catch (e: ForgeError) {
        lastInitError = "${e.message}${e.detail?.let { " — $it" } ?: ""}"
        _lastInitErrorFlow.value = lastInitError
        logger.error("init", "runtime initialization failed: $lastInitError")
        runtime.stateChanges.first()
    } catch (e: Throwable) {
        lastInitError = e.message ?: e.toString()
        _lastInitErrorFlow.value = lastInitError
        logger.error("init", "unexpected runtime failure: ${e.message}")
        runtime.stateChanges.first()
    }

    /** Retry entry point for the boot screen (also drives repair for DEGRADED states). */
    fun retryInitialization() {
        appScope.launch {
            val state = runtime.stateChanges.first()
            if (state == RuntimeState.FAILED || state == RuntimeState.DEGRADED) {
                runCatching { runtime.repair() }
            }
            initializeRuntime()
        }
    }

    private fun buildRuntime(): ForgeRuntime {
        if (!environment.isSupportedAbi()) {
            logger.warn(
                "init",
                "device ABI not in bundled set {${ForgeAndroidBootstrap.ABI_ARM64_V8A}}: " +
                    environment.supportedAbis().joinToString(),
            )
        }
        if (!ForgeAndroidBootstrap.isBundled(this)) {
            logger.error("init", "bundled bootstrap asset missing — runtime cannot install")
        }
        val descriptor = BootstrapDescriptor(
            abi = ForgeAndroidBootstrap.ABI_ARM64_V8A,
            source = ForgeAndroidBootstrap.assetSource(this),
            expectedSha256 = ForgeAndroidBootstrap.SHA256_ARM64,
            expectedSizeBytes = ForgeAndroidBootstrap.SIZE_ARM64,
            termuxSuite = ForgeAndroidBootstrap.TERMUX_SUITE,
        )
        return EmbeddedTermuxRuntime(
            paths = environment.forgePaths(),
            bootstrapDescriptor = descriptor,
            logger = logger,
        )
    }
}
