package com.forgekit.runtime.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class RuntimeTypesTest {
    @Test
    fun `health aggregates checks`() {
        val health = RuntimeHealth(
            state = RuntimeState.READY,
            checks = listOf(
                RuntimeHealthCheck("shell", true, "bash ok"),
                RuntimeHealthCheck("fs", false, "not writable"),
            ),
        )
        assertFalse(health.healthy)
    }

    @Test
    fun `state machine enum matches architecture names`() {
        // ARCHITECTURE §70 canonical names — renaming is a contract break.
        assertEquals(
            listOf(
                "NOT_INSTALLED", "INSTALLING", "INITIALIZING", "READY",
                "DEGRADED", "REPAIRING", "FAILED",
            ),
            RuntimeState.entries.map { it.name },
        )
    }

    @Test
    fun `signal requests expose posix names`() {
        assertEquals(listOf("SIGTERM", "SIGINT", "SIGKILL"), SignalRequest.entries.map { it.name })
    }
}

/** Contract test any ForgeRuntime implementation must satisfy (also run against TestRuntime in integration tests). */
class ForgeRuntimeContractTest {
    private class RecordingRuntime : ForgeRuntime {
        val state = MutableStateFlow(RuntimeState.NOT_INSTALLED)
        override val stateChanges: StateFlow<RuntimeState> = state
        override suspend fun initialize(): RuntimeState { state.value = RuntimeState.READY; return RuntimeState.READY }
        override suspend fun execute(request: ExecutionRequest): ExecutionHandle =
            ExecutionHandle("p1", 4242L, 0L)
        override suspend fun signal(processId: String, request: SignalRequest) = Unit
        override suspend fun terminate(processId: String) = Unit
        override suspend fun install(dependency: RuntimeDependency): DependencyInstallResult =
            DependencyInstallResult(dependency, installed = true, alreadyPresent = false, detail = "")
        override suspend fun inspect(): RuntimeCapabilities = RuntimeCapabilities(
            contractVersion = "forgekit.runtime/v1",
            architecture = "arm64-v8a",
            prefixPath = "/x/usr",
            homePath = "/x/home",
            shellPath = "/x/usr/bin/bash",
            packageManager = RuntimeCapabilities.PackageManagerInfo("pkg", true),
            tools = emptyList(),
        )
        override fun output(processId: String): Flow<ProcessOutput> =
            kotlinx.coroutines.flow.flowOf(ProcessOutput.Exited(com.forgekit.core.model.ExitStatus.Exited(0)))
        override fun writeStdin(processId: String, bytes: ByteArray) = Unit
        override suspend fun healthCheck(): RuntimeHealth =
            RuntimeHealth(RuntimeState.READY, listOf(RuntimeHealthCheck("self", true, "contract")))
        override suspend fun repair(): RuntimeRepairResult =
            RuntimeRepairResult(repaired = true, actions = listOf("noop"), resultingState = RuntimeState.READY)
    }

    @Test
    fun `initialize transitions to READY`() = runTest {
        val runtime = RecordingRuntime()
        assertEquals(RuntimeState.READY, runtime.initialize())
        assertEquals(RuntimeState.READY, runtime.stateChanges.value)
    }

    @Test
    fun `inspect reports contract version`() = runTest {
        val runtime = RecordingRuntime()
        assertEquals("forgekit.runtime/v1", runtime.inspect().contractVersion)
    }

    @Test
    fun `execute returns real pid shape`() = runTest {
        val handle = RecordingRuntime().execute(ExecutionRequest("bash"))
        assertTrue(handle.pid > 0)
        assertTrue(handle.processId.isNotBlank())
    }
}
