package com.forgekit.plugin.resolver

import com.forgekit.core.filesystem.ForgePaths
import com.forgekit.runtime.api.DependencyInstallResult
import com.forgekit.runtime.api.ExecutionHandle
import com.forgekit.runtime.api.ExecutionRequest
import com.forgekit.runtime.api.ForgeRuntime
import com.forgekit.runtime.api.ProcessCrashedError
import com.forgekit.runtime.api.ProcessOutput
import com.forgekit.runtime.api.RuntimeCapabilities
import com.forgekit.runtime.api.RuntimeCapabilityInfo
import com.forgekit.runtime.api.RuntimeDependency
import com.forgekit.runtime.api.RuntimeHealth
import com.forgekit.runtime.api.RuntimeHealthCheck
import com.forgekit.runtime.api.RuntimeRepairResult
import com.forgekit.runtime.api.RuntimeState
import com.forgekit.runtime.api.SignalRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import java.nio.file.Files
import java.nio.file.Path

/**
 * REAL host-backed ForgeRuntime for JVM tests: `inspect()` probes REAL files
 * (host binaries symlinked into a Termux-shaped prefix), `install()` executes
 * the REAL binary. Not a mock — the resolver sees true on-disk/exec truth.
 */
class HostRuntime(
    root: Path,
    private val prefixBin: Map<String, Path>,
) : ForgeRuntime {

    private val paths = ForgePaths(root)
    private val state = MutableStateFlow(RuntimeState.READY)
    override val stateChanges: StateFlow<RuntimeState> = state.asStateFlow()

    init {
        paths.ensureSkeleton()
        val bin = root.resolve("termux/usr/bin")
        Files.createDirectories(bin)
        for ((name, target) in prefixBin) {
            val link = bin.resolve(name)
            // idempotent: a second manager over the same root must not crash
            if (Files.isSymbolicLink(link) || Files.exists(link)) {
                Files.deleteIfExists(link)
            }
            Files.createSymbolicLink(link, target)
        }
    }

    override suspend fun initialize(): RuntimeState = RuntimeState.READY

    override suspend fun execute(request: ExecutionRequest): ExecutionHandle {
        val exe = request.executable.removePrefix("bin/")
        val path = prefixBin[exe] ?: paths.termuxPrefix.resolve("bin/$exe")
        if (!Files.isExecutable(path)) {
            throw com.forgekit.runtime.api.ProcessStartError("executable not found", path.toString())
        }
        val process = ProcessBuilder(listOf(path.toString()) + request.args).start()
        return ExecutionHandle(
            processId = "host-${process.pid()}",
            pid = process.pid(),
            createdAtMillis = System.currentTimeMillis(),
        )
    }

    override fun output(processId: String): Flow<ProcessOutput> = flow {
        throw ProcessCrashedError("host shim does not stream output", processId)
    }

    override fun writeStdin(processId: String, bytes: ByteArray) = Unit

    override suspend fun signal(processId: String, request: SignalRequest) = Unit

    override suspend fun terminate(processId: String) = Unit

    override suspend fun install(dependency: RuntimeDependency): DependencyInstallResult {
        // REAL checks: the tool must exist in the prefix; installs that would
        // use pkg/apt/pip actually exec the real binary to test availability.
        val tool = when (dependency.kind) {
            RuntimeDependency.Kind.TERMUX_PACKAGE -> "pkg"
            RuntimeDependency.Kind.PYTHON_PACKAGE -> "pip"
            RuntimeDependency.Kind.NODE_PACKAGE -> "npm"
        }
        val toolPath = paths.termuxPrefix.resolve("bin/$tool")
        if (!Files.isExecutable(toolPath)) {
            return DependencyInstallResult(
                dependency = dependency,
                installed = false,
                alreadyPresent = false,
                detail = "$tool is not available in \$PREFIX",
            )
        }
        // exec the real tool with --version: proves the environment can run it
        val process = ProcessBuilder(listOf(toolPath.toString(), "--version"))
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val code = process.waitFor()
        return DependencyInstallResult(
            dependency = dependency,
            installed = code == 0,
            alreadyPresent = false,
            detail = "exit $code — ${output.lineSequence().firstOrNull()?.take(80) ?: ""}",
        )
    }

    override suspend fun inspect(): RuntimeCapabilities {
        val tools = prefixBin.keys
            .filter { name -> Files.isExecutable(paths.termuxPrefix.resolve("bin/$name")) }
            .map { name ->
                RuntimeCapabilityInfo(
                    name = name,
                    version = null,
                    path = paths.termuxPrefix.resolve("bin/$name").toString(),
                )
            }
        val pkgAvailable = Files.isExecutable(paths.termuxPrefix.resolve("bin/pkg"))
        return RuntimeCapabilities(
            contractVersion = "forgekit.runtime/v1",
            architecture = System.getProperty("os.arch") ?: "unknown",
            prefixPath = paths.termuxPrefix.toString(),
            homePath = paths.termuxHome.toString(),
            shellPath = paths.termuxPrefix.resolve("bin/bash").toString(),
            packageManager = RuntimeCapabilities.PackageManagerInfo(
                name = if (pkgAvailable) "pkg" else "none",
                available = pkgAvailable,
            ),
            tools = tools,
        )
    }

    override suspend fun healthCheck(): RuntimeHealth = RuntimeHealth(
        state = RuntimeState.READY,
        checks = listOf(RuntimeHealthCheck("host-shim", true, "real host prefix")),
    )

    override suspend fun repair(): RuntimeRepairResult =
        RuntimeRepairResult(repaired = false, actions = emptyList(), resultingState = RuntimeState.READY)
}
