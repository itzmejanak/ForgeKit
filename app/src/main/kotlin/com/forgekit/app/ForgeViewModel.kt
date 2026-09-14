package com.forgekit.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.forgekit.app.data.AndroidSqliteJobStore
import com.forgekit.core.model.ForgeError
import com.forgekit.core.logging.SecretRedactor
import com.forgekit.core.security.PermissionManager
import com.forgekit.job.api.JobEvent
import com.forgekit.job.api.JobId
import com.forgekit.job.api.JobPrompt
import com.forgekit.job.api.JobPromptResponse
import com.forgekit.job.api.JobPromptResponseStatus
import com.forgekit.job.api.JobRecord
import com.forgekit.job.manager.JobManager
import com.forgekit.plugin.api.PluginDescriptor
import com.forgekit.plugin.api.PluginId
import com.forgekit.plugin.api.PluginStatus
import com.forgekit.plugin.manager.PluginManager
import com.forgekit.plugin.manifest.PluginManifest
import com.forgekit.plugin.resolver.DependencyPlan
import com.forgekit.plugin.resolver.ResolutionEvent
import com.forgekit.runtime.api.InstallPhase
import com.forgekit.ui.design.StatusTone
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.nio.file.Paths

/** A surfaced user-facing message with the severity that drives its toast styling. */
public data class ForgeMessage(
    public val text: String,
    public val tone: StatusTone,
)

/**
 * The screen-facing composition root (§2: the app layer is where every
 * layer meets). Owns the plugin manager, job manager, permission engine
 * and the runtime — the UI only ever talks to this and to pure state.
 */
public class ForgeViewModel(app: Application) : AndroidViewModel(app) {

    private val forgeApp = app as ForgeKitApplication

    public val runtime get() = forgeApp.runtime

    public val permissionManager: PermissionManager = PermissionManager()

    private val store = AndroidSqliteJobStore(app)

    public val jobManager: JobManager = JobManager(
        runtime = forgeApp.runtime,
        store = store,
    )

    public val pluginManager: PluginManager = PluginManager(
        pluginsRoot = forgeApp.pluginsRoot(),
        runtime = forgeApp.runtime,
        validationContext = com.forgekit.plugin.validator.ValidationContext(
            trustStore = com.forgekit.core.security.TrustStore(),
        ),
    )

    /** REAL dependency resolution against the live runtime (pkg/pip/npm). */
    private val resolver = com.forgekit.plugin.resolver.DependencyResolver(forgeApp.runtime)

    // ---- state ----------------------------------------------------------------

    public data class UiState(
        val plugins: List<PluginDescriptor> = emptyList(),
        val pendingImports: List<PluginManager.ImportReview> = emptyList(),
        val jobs: List<JobRecord> = emptyList(),
        val runtimeState: com.forgekit.runtime.api.RuntimeState? = null,
        val runtimeError: String? = null,
        val provisioning: Map<String, ProvisioningState> = emptyMap(),
        val documentation: DocumentationExportState = DocumentationExportState(),
        val pendingPrompts: List<PendingJobPrompt> = emptyList(),
    )

    public data class DocumentationExportState(
        val exporting: Boolean = false,
        val lastPath: String? = null,
        val error: String? = null,
    )

    /** Screen-safe context for one protocol prompt. The response is never kept here. */
    public data class PendingJobPrompt(
        val jobId: JobId,
        val pluginId: String,
        val action: String,
        val trust: String,
        val prompt: JobPrompt,
    )

    private val _state = MutableStateFlow(UiState())
    public val state: StateFlow<UiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<ForgeMessage>(extraBufferCapacity = 16)
    public val messages: SharedFlow<ForgeMessage> = _messages.asSharedFlow()

    // ---- provisioning model -----------------------------------------------------

    /**
     * Per-dependency provisioning state, fed from REAL [ResolutionEvent]s while a
     * provisioning job runs (approve / provision / run). Nothing here is invented:
     * presence comes from the package database, phases from parsed apt/pip output.
     */
    public data class ProvisioningState(
        public val jobId: String? = null,
        public val label: String = "",
        public val active: Boolean = false,
        public val dependencies: List<DependencyState> = emptyList(),
        /** Dependencies that failed to install during the last provisioning run. */
        public val missingAfter: List<String> = emptyList(),
        public val failedReason: String? = null,
        /** Bounded, ordered provider events rendered by import/plugin detail. */
        public val events: List<ProvisioningLogEntry> = emptyList(),
    ) {
        public val fraction: Float
            get() {
                if (dependencies.isEmpty()) return 1f
                // real markers only: completed deps count full, the active dep counts as in-flight.
                val completed = dependencies.count { it.status == DepStatus.DONE || it.status == DepStatus.PRESENT }
                val activeNow = dependencies.count { it.status == DepStatus.INSTALLING }
                return (completed + activeNow * 0.5f) / dependencies.size
            }
    }

    public enum class DepStatus { PENDING, MISSING, PRESENT, INSTALLING, DONE, FAILED }

    public enum class ProvisionEventKind { PLAN, PHASE, OUTPUT, RESULT, FAILURE }

    public data class ProvisioningLogEntry(
        public val sequence: Long,
        public val timestampMillis: Long,
        public val kind: ProvisionEventKind,
        public val manager: String?,
        public val dependency: String?,
        public val message: String,
    )

    public data class DependencyState(
        public val manager: String,
        public val name: String,
        public val version: String?,
        public val status: DepStatus,
        public val phase: InstallPhase? = null,
        public val message: String? = null,
        public val detail: String? = null,
    ) {
        public val label: String get() = "$manager/$name" + (version?.let { " $it" } ?: "")
    }

    private val provisioningBusy: Boolean
        get() = _state.value.provisioning.values.any { it.active }

    private val provisionRedactor = SecretRedactor.default()

    init {
        viewModelScope.launch {
            forgeApp.runtime.stateChanges.collect { runtimeState ->
                _state.update { it.copy(runtimeState = runtimeState) }
            }
        }
        viewModelScope.launch {
            forgeApp.lastInitErrorFlow.collect { error ->
                _state.update { it.copy(runtimeError = error) }
            }
        }
        viewModelScope.launch {
            jobManager.events.collect { event ->
                onJobEvent(event)
            }
        }
        // §31 recovery: any job left RUNNING by a killed process is stale — fail it
        // before the UI shows history. SQLite access, so off the main thread.
        viewModelScope.launch(Dispatchers.IO) {
            val reconciled = runCatching { jobManager.reconcileOrphans() }.getOrDefault(0)
            if (reconciled > 0) _messages.tryEmit(ForgeMessage("recovered $reconciled interrupted job(s)", StatusTone.INFO))
            refreshJobs()
        }
        refreshPlugins()
    }

    /** Rescans installed + pending plugins (disk truth, §72). */
    public fun refreshPlugins() {
        viewModelScope.launch(Dispatchers.IO) {
            val plugins = pluginManager.all()
            _state.update {
                it.copy(plugins = plugins, pendingImports = pluginManager.pendingImports())
            }
        }
    }

    /** Builds the guide from its ordered split assets and publishes one public Docs.md. */
    public fun downloadDeveloperDocs() {
        if (_state.value.documentation.exporting) return
        _state.update {
            it.copy(documentation = it.documentation.copy(exporting = true, error = null))
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { forgeApp.developerDocsExporter.export() }
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            documentation = DocumentationExportState(
                                exporting = false,
                                lastPath = result.displayPath,
                            ),
                        )
                    }
                    _messages.emit(
                        ForgeMessage(
                            "Developer guide saved to ${result.displayPath}",
                            StatusTone.READY,
                        ),
                    )
                }
                .onFailure { failure ->
                    val reason = failure.message ?: failure::class.simpleName ?: "unknown error"
                    _state.update {
                        it.copy(
                            documentation = it.documentation.copy(
                                exporting = false,
                                error = reason,
                            ),
                        )
                    }
                    _messages.emit(ForgeMessage("Docs export failed: ${reason.take(120)}", StatusTone.ERROR))
                }
        }
    }

    /** §72 phase 1: import + validate, producing the review. */
    public fun importArchive(
        path: Path,
        onReviewed: (PluginManager.ImportReview) -> Unit = {},
        onOpenFailed: () -> Unit = {},
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val review = pluginManager.import(path)
                refreshPlugins()
                onReviewed(review)
            } catch (e: ForgeError) {
                refreshPlugins() // rejected packages stay reviewable per §72
                // Rejected packages are still stored as pending reviews by the manager;
                // surface them so the import page renders the errors inline instead of
                // only a snackbar. Fall back to the message if none is pending.
                val rejected = pluginManager.pendingImports().lastOrNull { !it.report.valid }
                if (rejected != null) {
                    onReviewed(rejected)
                } else {
                    _messages.tryEmit(ForgeMessage("Import rejected: ${e.message}", StatusTone.ERROR))
                    onOpenFailed()
                }
            }
        }
    }

    /**
     * LIVE dependency plan for a review — real runtime presence at review time,
     * never the static "not installed" tags the validator hardcodes.
     */
    public suspend fun liveDependencyPlans(manifest: PluginManifest): List<DependencyPlan> =
        withContext(Dispatchers.IO) { resolver.plan(manifest) }

    /** LIVE dependency plan for a pending [PluginManager.ImportReview]. */
    public suspend fun reviewDependencyPlans(review: PluginManager.ImportReview): List<DependencyPlan> =
        withContext(Dispatchers.IO) { pluginManager.liveDependencyPlan(review) }

    /** LIVE dependency plan for an installed plugin (detail "dependencies" card). */
    public suspend fun dependencyPlans(pluginId: PluginId): List<DependencyPlan>? =
        withContext(Dispatchers.IO) { pluginManager.manifest(pluginId)?.let { resolver.plan(it) } }

    /**
     * §72 phase 2: user approval → install + provision, with LIVE provisioning progress.
     *
     * Returns false (and toasts why) when approval is refused up front because another
     * provisioning run is active; nothing starts and [onInstalled] is never called.
     * Otherwise [onInstalled] is called exactly once on the main thread: with the
     * registered descriptor (READY or UNRESOLVED), or null when the install threw.
     */
    public fun approveImport(
        pluginId: PluginId,
        onInstalled: (PluginDescriptor?) -> Unit = {},
    ): Boolean {
        if (provisioningBusy) {
            _messages.tryEmit(ForgeMessage("another provisioning is already running", StatusTone.WARNING))
            return false
        }
        viewModelScope.launch(Dispatchers.IO) {
            var jobId: JobId? = null
            var installed: PluginDescriptor? = null
            try {
                // Provisioning is a persisted job: auditable, cancellable, survives
                // process death (§30 INSTALLING_DEPENDENCIES). The JobSpec carries no
                // execution — this is a provisioning-only job.
                jobId = jobManager.enqueue(
                    JobManager.JobSpec(
                        pluginId = pluginId.raw,
                        action = PROVISION_ACTION,
                        input = emptyMap(),
                    ),
                ).id
                beginProvisioning(jobId, pluginId.raw)
                val descriptor = pluginManager.approveImportStreaming(pluginId) { event ->
                    onResolutionEvent(jobId, pluginId.raw, event)
                }
                // sandbox defaults follow installation; dangerous permissions
                // stay opt-in per §25 (the review screen granted them):
                grantApprovedPermissions(descriptor)
                terminalizeProvisioning(jobId, pluginId.raw, descriptor.status == PluginStatus.READY)
                _messages.tryEmit(
                    ForgeMessage(
                        "${descriptor.id.raw} ${descriptor.status}",
                        if (descriptor.status == PluginStatus.READY) StatusTone.READY else StatusTone.WARNING,
                    ),
                )
                installed = descriptor
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = e.message ?: e::class.java.simpleName
                jobId?.let { terminalizeProvisioning(it, pluginId.raw, false, reason) }
                _messages.tryEmit(ForgeMessage("Install failed: $reason", StatusTone.ERROR))
            }
            refreshPlugins()
            withContext(Dispatchers.Main) { onInstalled(installed) }
        }
        return true
    }

    /**
     * Re-attempts dependency provisioning for an UNRESOLVED installed plugin,
     * through the same persisted provisioning job + live progress UI.
     */
    public fun provision(pluginId: PluginId) {
        if (provisioningBusy) {
            _messages.tryEmit(ForgeMessage("another provisioning is already running", StatusTone.WARNING))
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            var jobId: JobId? = null
            try {
                jobId = jobManager.enqueue(
                    JobManager.JobSpec(
                        pluginId = pluginId.raw,
                        action = PROVISION_ACTION,
                        input = emptyMap(),
                    ),
                ).id
                beginProvisioning(jobId, pluginId.raw)
                val descriptor = pluginManager.provisionStreaming(pluginId) { event ->
                    onResolutionEvent(jobId, pluginId.raw, event)
                }
                terminalizeProvisioning(jobId, pluginId.raw, descriptor.status == PluginStatus.READY)
                _messages.tryEmit(
                    ForgeMessage(
                        "${descriptor.id.raw} ${descriptor.status}",
                        if (descriptor.status == PluginStatus.READY) StatusTone.READY else StatusTone.WARNING,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = e.message ?: e::class.java.simpleName
                jobId?.let { terminalizeProvisioning(it, pluginId.raw, false, reason) }
                _messages.tryEmit(ForgeMessage("Provisioning failed: $reason", StatusTone.ERROR))
            }
            refreshPlugins()
        }
    }

    private fun grantApprovedPermissions(descriptor: PluginDescriptor) {
        permissionManager.grantDefaults(descriptor.id.raw)
        // every KNOWN declared permission the user saw in the review:
        val known = descriptor.requestedPermissions.filter {
            com.forgekit.core.security.PermissionCatalog.find(it) != null
        }
        permissionManager.approve(descriptor.id.raw, known)
    }

    /**
     * Removes an installed plugin (or a pending import). Refused — returns false with a
     * toast — while the plugin has a live provisioning run or unfinished jobs, so no
     * running process loses its package tree.
     */
    public fun removePlugin(pluginId: PluginId): Boolean {
        val current = _state.value
        val busy = current.provisioning[pluginId.raw]?.active == true ||
            current.jobs.any { it.pluginId == pluginId.raw && !it.state.terminal }
        if (busy) {
            _messages.tryEmit(ForgeMessage("${pluginId.raw} has running work — cancel or wait for it first", StatusTone.WARNING))
            return false
        }
        viewModelScope.launch(Dispatchers.IO) {
            permissionManager.revokeAll(pluginId.raw)
            val removed = pluginManager.remove(pluginId)
            if (removed) _messages.tryEmit(ForgeMessage("${pluginId.raw} removed", StatusTone.READY))
            refreshPlugins()
        }
        return true
    }

    // ---- terminal ----------------------------------------------------------------

    /**
     * Opens a REAL interactive pty shell session inside the embedded runtime
     * (bash with the Termux environment). Requires READY state.
     */
    public suspend fun openShellSession(
        columns: Int = 80,
        rows: Int = 24,
    ): com.forgekit.runtime.bridge.TerminalSession = withContext(Dispatchers.IO) {
        val termux = runtime as? com.forgekit.runtime.termux.EmbeddedTermuxRuntime
            ?: error("interactive sessions require the embedded Termux runtime")
        // stateChanges is a StateFlow: read its current value directly (collecting it
        // never completes). The shell only opens on a live runtime.
        val current = termux.stateChanges.value
        check(current == com.forgekit.runtime.api.RuntimeState.READY) {
            "runtime not READY — the shell opens only on a live runtime (state: $current)"
        }
        // fork+exec is blocking; this whole function runs on Dispatchers.IO (no more
        // runBlocking on the caller's thread — that risked an ANR from composition).
        val handle = runtime.execute(termux.interactiveShellRequest(columns, rows))
        termux.terminalSession(handle.processId)
            ?: error("session registry lost the shell process")
    }

    // ---- jobs ------------------------------------------------------------------

    /**
     * The §21 run path: manifest-driven execution with REAL dependency
     * provisioning. Resolves the plugin's declared dependencies against the
     * live runtime (installing the missing ones through pkg/apt/pip), then
     * assembles the interpreter + entrypoint execution and queues the job.
     *
     * Returns false (with a toast) when the run is refused up front; nothing is queued.
     * Otherwise [onQueued] receives the new job id on the main thread once it is persisted.
     */
    public fun runAction(
        descriptor: PluginDescriptor,
        actionId: String,
        input: Map<String, String>,
        onQueued: (JobId) -> Unit = {},
    ): Boolean {
        val manifest = pluginManager.manifest(descriptor.id)
        if (manifest == null) {
            _messages.tryEmit(ForgeMessage("plugin ${descriptor.id.raw} has no installed manifest — re-import it", StatusTone.ERROR))
            return false
        }
        val action = manifest.actions.firstOrNull { it.id == actionId }
        if (action == null) {
            _messages.tryEmit(ForgeMessage("action '$actionId' is not declared by ${manifest.id}", StatusTone.ERROR))
            return false
        }
        val missingRequired = action.inputs
            .filter { it.required }
            .filter { input[it.id].isNullOrBlank() }
            .map { it.id }
        if (missingRequired.isNotEmpty()) {
            _messages.tryEmit(ForgeMessage("missing required input(s): ${missingRequired.joinToString()}", StatusTone.ERROR))
            return false
        }

        if (provisioningBusy) {
            _messages.tryEmit(ForgeMessage("another provisioning is already running", StatusTone.WARNING))
            return false
        }

        // Dependency resolution (pkg/pip installs) and the job run do blocking process
        // I/O. viewModelScope is Dispatchers.Main, so this MUST run on IO or it freezes
        // the UI thread and triggers an ANR while packages download.
        viewModelScope.launch(Dispatchers.IO) {
            // Manifest-driven execution assembly (interpreter + entrypoint + env).
            val installedPath = descriptor.installedPath
                ?: pluginManager.descriptor(descriptor.id)?.installedPath
            if (installedPath.isNullOrBlank()) {
                _messages.tryEmit(ForgeMessage("plugin ${descriptor.id.raw} is not installed yet — approve the import first", StatusTone.ERROR))
                return@launch
            }
            val pluginRoot = Paths.get(installedPath)
            val entrypoint = pluginRoot.resolve(manifest.entrypoint).toString()

            val termux = runtime as? com.forgekit.runtime.termux.EmbeddedTermuxRuntime
            val prefix = termux?.paths?.termuxPrefix
            val home = termux?.paths?.termuxHome
            val execution = when (manifest.runtime.type) {
                "binary" -> com.forgekit.runtime.api.ExecutionRequest(
                    executable = entrypoint,
                    workingDirectory = pluginRoot.toString(),
                    environment = executionEnvironment(home, pluginRoot),
                    label = "plugin-${descriptor.id.raw}-${action.id}",
                )
                else -> {
                    val interpreter = prefix?.resolve("bin/${manifest.runtime.type}")?.toString()
                        ?: manifest.runtime.type
                    com.forgekit.runtime.api.ExecutionRequest(
                        executable = interpreter,
                        args = listOf(entrypoint),
                        workingDirectory = pluginRoot.toString(),
                        environment = executionEnvironment(home, pluginRoot),
                        label = "plugin-${descriptor.id.raw}-${action.id}",
                    )
                }
            }

            // queue + run through the job manager (§53 lifecycle, persisted history).
            // The job FIRST passes through INSTALLING_DEPENDENCIES with live progress
            // (real pkg/pip installs streamed over the persisted job event log), then
            // only proceeds to STARTING/RUNNING when provisioning actually succeeded.
            val record = jobManager.enqueue(
                JobManager.JobSpec(
                    pluginId = manifest.id,
                    action = action.id,
                    input = input,
                    execution = execution,
                ),
            )
            _state.update { it.copy(jobs = listOf(record) + it.jobs.filter { j -> j.id != record.id }) }
            _messages.tryEmit(ForgeMessage("${action.title ?: action.id} queued", StatusTone.INFO))
            withContext(Dispatchers.Main) { onQueued(record.id) }
            beginProvisioning(record.id, record.pluginId, label = "run ${descriptor.name}/${action.title ?: action.id}")
            jobManager.runWithProvisioning(record.id) {
                val report = resolver.resolveStreaming(manifest) { event ->
                    onResolutionEvent(record.id, manifest.id, event)
                }
                if (report.missingAfterResolution.isNotEmpty()) {
                    jobManager.emitProvisionLog(
                        record.id,
                        "missing: " + report.missingAfterResolution.joinToString { "${it.dependency.name} — ${it.detail.take(60)}" },
                        "stderr",
                    )
                }
                _state.update { state ->
                    val current = state.provisioning[manifest.id] ?: return@update state
                    state.copy(
                        provisioning = state.provisioning + (
                            manifest.id to current.copy(active = false)
                        ),
                    )
                }
                report.missingAfterResolution.isEmpty()
            }
            // leave a terminal snapshot on the plugin card (active=false, result intact)
            _state.update { s ->
                val prev = s.provisioning[manifest.id] ?: return@update s
                s.copy(provisioning = s.provisioning + (manifest.id to prev.copy(active = false)))
            }
            refreshJobs()
            refreshPlugins()
        }
        return true
    }

    // ---- provisioning plumbing -----------------------------------------------

    private fun beginProvisioning(jobId: JobId, pluginId: String, label: String? = null) {
        jobManager.beginProvisioning(jobId)
        _state.update { s ->
            val prev = s.provisioning[pluginId]
            s.copy(
                provisioning = s.provisioning + (pluginId to (prev ?: ProvisioningState()).copy(
                    jobId = jobId.raw,
                    label = label ?: "provision $pluginId",
                    active = true,
                    missingAfter = emptyList(),
                    failedReason = null,
                    events = emptyList(),
                )),
            )
        }
        refreshJobs()
    }

    /**
     * Maps ONE REAL resolution event to (a) the persisted job's [JobEvent.Progress]/
     * LogLine stream and (b) the live [ProvisioningState] the UI renders.
     */
    private fun onResolutionEvent(jobId: JobId, pluginId: String, event: ResolutionEvent) {
        when (event) {
            is ResolutionEvent.Planned -> _state.update { s ->
                val prev = s.provisioning[pluginId] ?: return@update s
                val deps = event.plans.map { plan ->
                    DependencyState(
                        manager = plan.manager,
                        name = plan.name,
                        version = plan.versionRequirement,
                        status = if (plan.alreadyPresent) DepStatus.PRESENT else DepStatus.PENDING,
                        message = if (plan.alreadyPresent) "present in runtime" else null,
                    )
                }
                val entries = event.plans.map { plan ->
                    provisionEntry(
                        prev,
                        ProvisionEventKind.PLAN,
                        plan.manager,
                        plan.name,
                        if (plan.alreadyPresent) "present — no install needed" else "queued from ${plan.source}",
                    )
                }
                s.copy(
                    provisioning = s.provisioning + (
                        pluginId to prev.copy(
                            dependencies = deps,
                            events = appendProvisionEntries(prev.events, entries),
                        )
                    ),
                )
            }

            is ResolutionEvent.Resolving -> _state.update { s ->
                val prev = s.provisioning[pluginId] ?: return@update s
                val deps = updateDependency(prev.dependencies, event.dependency, event.phase) {
                    it.copy(status = DepStatus.INSTALLING, phase = event.phase, message = event.message, detail = null)
                }
                val next = prev.copy(
                    dependencies = deps,
                    events = appendProvisionEntries(
                        prev.events,
                        listOf(
                            provisionEntry(
                                prev,
                                ProvisionEventKind.PHASE,
                                managerLabel(event.dependency.kind),
                                event.dependency.name,
                                buildString {
                                    append(event.phase.name.lowercase())
                                    event.message?.takeIf(String::isNotBlank)?.let { append(" — ").append(it) }
                                },
                            ),
                        ),
                    ),
                )
                jobManager.emitProvisionProgress(jobId, next.fraction.toDouble(), activeLabel(next))
                s.copy(provisioning = s.provisioning + (pluginId to next))
            }

            is ResolutionEvent.Line -> {
                val safeLine = provisionRedactor.redact(event.text).take(400)
                jobManager.emitProvisionLog(jobId, safeLine, "provision")
                _state.update { s ->
                    val prev = s.provisioning[pluginId] ?: return@update s
                    val idx = prev.dependencies.indexOfFirst { d ->
                        d.manager == managerLabel(event.dependency.kind) && d.name == event.dependency.name
                    }
                    if (idx < 0) return@update s
                    val deps = prev.dependencies.toMutableList().apply {
                        set(idx, get(idx).copy(message = safeLine.take(160)))
                    }
                    val entry = provisionEntry(
                        prev,
                        ProvisionEventKind.OUTPUT,
                        managerLabel(event.dependency.kind),
                        event.dependency.name,
                        safeLine,
                    )
                    s.copy(
                        provisioning = s.provisioning + (
                            pluginId to prev.copy(
                                dependencies = deps,
                                events = appendProvisionEntries(prev.events, listOf(entry)),
                            )
                        ),
                    )
                }
            }

            is ResolutionEvent.Completed -> _state.update { s ->
                val prev = s.provisioning[pluginId] ?: return@update s
                val deps = updateDependency(prev.dependencies, event.dependency, null) { current ->
                    current.copy(
                        status = if (event.result.installed) DepStatus.DONE else DepStatus.FAILED,
                        phase = if (event.result.installed) InstallPhase.VERIFYING else current.phase,
                        message = if (event.result.installed) "installed" else "failed",
                        detail = if (!event.result.installed) event.result.detail.take(200) else null,
                    )
                }
                val missing = if (event.result.installed) {
                    prev.missingAfter
                } else {
                    prev.missingAfter + "${managerLabel(event.dependency.kind)}/${event.dependency.name}"
                }
                val entry = provisionEntry(
                    prev,
                    if (event.result.installed) ProvisionEventKind.RESULT else ProvisionEventKind.FAILURE,
                    managerLabel(event.dependency.kind),
                    event.dependency.name,
                    event.result.detail,
                )
                s.copy(
                    provisioning = s.provisioning + (
                        pluginId to prev.copy(
                            dependencies = deps,
                            missingAfter = missing,
                            events = appendProvisionEntries(prev.events, listOf(entry)),
                        )
                    ),
                )
            }
        }
    }

    /** Terminal step for provisioning-only jobs (approve/provision); the job becomes
     *  COMPLETED on success or FAILED carrying the reason, and the card snapshot
     *  stops being live but keeps its result. Verified by the JobStateMachine. */
    private fun terminalizeProvisioning(jobId: JobId, pluginId: String, success: Boolean, reason: String? = null) {
        jobManager.finishProvision(jobId, success, reason)
        _state.update { s ->
            val prev = s.provisioning[pluginId] ?: return@update s
            val failure = reason ?: prev.failedReason
            val finalEvents = if (success) {
                prev.events
            } else {
                appendProvisionEntries(
                    prev.events,
                    listOf(provisionEntry(prev, ProvisionEventKind.FAILURE, null, null, failure ?: "provisioning failed")),
                )
            }
            s.copy(
                provisioning = s.provisioning + (pluginId to prev.copy(
                    active = false,
                    failedReason = if (success) null else failure,
                    events = finalEvents,
                )),
            )
        }
        refreshJobs()
    }

    private fun updateDependency(
        deps: List<DependencyState>,
        dependency: com.forgekit.runtime.api.RuntimeDependency,
        phase: InstallPhase?,
        transform: (DependencyState) -> DependencyState,
    ): List<DependencyState> {
        val idx = deps.indexOfFirst { d -> d.manager == managerLabel(dependency.kind) && d.name == dependency.name }
        if (idx < 0) return deps
        return deps.toMutableList().apply { set(idx, transform(get(idx).copy(phase = phase))) }
    }

    private fun managerLabel(kind: com.forgekit.runtime.api.RuntimeDependency.Kind): String = when (kind) {
        com.forgekit.runtime.api.RuntimeDependency.Kind.TERMUX_PACKAGE -> "TERMUX"
        com.forgekit.runtime.api.RuntimeDependency.Kind.PYTHON_PACKAGE -> "PIP"
        com.forgekit.runtime.api.RuntimeDependency.Kind.NODE_PACKAGE -> "NPM"
    }

    private fun provisionEntry(
        state: ProvisioningState,
        kind: ProvisionEventKind,
        manager: String?,
        dependency: String?,
        message: String,
    ): ProvisioningLogEntry = ProvisioningLogEntry(
        sequence = (state.events.lastOrNull()?.sequence ?: 0L) + 1L,
        timestampMillis = System.currentTimeMillis(),
        kind = kind,
        manager = manager,
        dependency = dependency,
        message = provisionRedactor.redact(message).take(400),
    )

    private fun appendProvisionEntries(
        current: List<ProvisioningLogEntry>,
        additions: List<ProvisioningLogEntry>,
    ): List<ProvisioningLogEntry> {
        if (additions.isEmpty()) return current
        var nextSequence = (current.lastOrNull()?.sequence ?: 0L) + 1L
        val normalized = additions.map { entry -> entry.copy(sequence = nextSequence++) }
        return (current + normalized).takeLast(MAX_PROVISION_EVENTS)
    }

    private fun activeLabel(state: ProvisioningState): String {
        val active = state.dependencies.firstOrNull { it.status == DepStatus.INSTALLING } ?: return state.label
        return "${active.label} · ${active.phase?.name?.lowercase() ?: "installing"}${active.message?.let { " — ${it.take(60)}" } ?: ""}"
    }

    public companion object {
        /** Job action marker for provisioning-only jobs (no execution). */
        public const val PROVISION_ACTION: String = "forge://provision"

        private const val MAX_PROVISION_EVENTS = 160
    }

    private fun executionEnvironment(
        home: java.nio.file.Path?,
        pluginRoot: java.nio.file.Path,
    ): Map<String, String> =
        buildMap {
            put("FORGE_PACKAGE", pluginRoot.toString())
            home?.let { put("FORGE_HOME", it.toString()) }
            put("TERM", "xterm-256color")
        }

    public fun cancelJob(jobId: JobId) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { jobManager.cancel(jobId) }
            refreshJobs()
        }
    }

    /** Sends a submitted prompt value without retaining it in application state. */
    public fun submitPrompt(jobId: JobId, promptId: String, value: String?) {
        respondToPrompt(jobId, promptId, JobPromptResponseStatus.SUBMITTED, value)
    }

    /** Answers a prompt as cancelled; this does not automatically cancel its job. */
    public fun dismissPrompt(jobId: JobId, promptId: String) {
        respondToPrompt(jobId, promptId, JobPromptResponseStatus.CANCELLED, null)
    }

    private fun respondToPrompt(
        jobId: JobId,
        promptId: String,
        status: JobPromptResponseStatus,
        value: String?,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                jobManager.respondToPrompt(jobId, promptId, JobPromptResponse(status, value))
            }.onFailure { failure ->
                _messages.tryEmit(
                    ForgeMessage(
                        "Prompt response failed: ${(failure.message ?: "unknown error").take(100)}",
                        StatusTone.ERROR,
                    ),
                )
            }
            refreshJobs()
        }
    }

    public fun refreshJobs() {
        viewModelScope.launch(Dispatchers.IO) {
            val jobs = store.query(com.forgekit.job.api.JobQuery(limit = 50))
            _state.update { it.copy(jobs = jobs) }
        }
    }

    // ---- job detail (§75 observability: per-job live log console) ----------------

    /** Current record for [jobId] (live state first, then persisted store). */
    public fun jobRecord(jobId: JobId): JobRecord? =
        _state.value.jobs.firstOrNull { it.id == jobId } ?: store.find(jobId)

    /** Persisted event history for [jobId] (state changes, logs, progress) — oldest first. */
    public suspend fun jobHistory(jobId: JobId): List<JobEvent> =
        withContext(Dispatchers.IO) { store.events(jobId) }

    /** Live event stream for [jobId] only (append to the loaded history for a live console). */
    public fun jobEvents(jobId: JobId): kotlinx.coroutines.flow.Flow<JobEvent> =
        jobManager.events.filter { it.jobId == jobId }

    /**
     * Wipes the persisted job history (records + event log) and refreshes the
     * live list. Runs on IO — [AndroidSqliteJobStore.clear] is blocking SQLite.
     */
    /**
     * Deletes one finished job (record + event log). Live jobs are refused with a toast.
     * The row leaves the list immediately; if the store refuses, the list is reloaded.
     * A finished provisioning snapshot that pointed at the job stops linking to its log.
     */
    public fun deleteJob(jobId: JobId) {
        val record = _state.value.jobs.firstOrNull { it.id == jobId }
        if (record != null && !record.state.terminal) {
            _messages.tryEmit(ForgeMessage("cancel the job before deleting it", StatusTone.WARNING))
            return
        }
        _state.update { s ->
            s.copy(
                jobs = s.jobs.filter { it.id != jobId },
                provisioning = s.provisioning.mapValues { (_, run) ->
                    if (!run.active && run.jobId == jobId.raw) run.copy(jobId = null) else run
                },
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { jobManager.delete(jobId) }
                .onSuccess { _messages.tryEmit(ForgeMessage("job deleted", StatusTone.READY)) }
                .onFailure { error ->
                    _messages.tryEmit(ForgeMessage(error.message ?: "job could not be deleted", StatusTone.WARNING))
                    refreshJobs()
                }
        }
    }

    public fun clearJobs() {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.IO) {
                runCatching { store.clear() }
            }
            _state.update { it.copy(jobs = emptyList()) }
            _messages.tryEmit(ForgeMessage("job history cleared", StatusTone.READY))
        }
    }

    private fun onJobEvent(event: JobEvent) {
        when (event) {
            is JobEvent.PromptRequested -> {
                val record = jobManager.get(event.jobId) ?: return
                _state.update { state ->
                    val pending = PendingJobPrompt(
                        jobId = event.jobId,
                        pluginId = record.pluginId,
                        action = record.action,
                        trust = state.plugins.firstOrNull { it.id.raw == record.pluginId }
                            ?.trust?.name ?: "UNKNOWN_TRUST",
                        prompt = event.prompt,
                    )
                    state.copy(
                        pendingPrompts = state.pendingPrompts
                            .filterNot { it.jobId == event.jobId }
                            .plus(pending),
                    )
                }
            }
            is JobEvent.PromptResolved -> _state.update { state ->
                state.copy(pendingPrompts = state.pendingPrompts.filterNot { it.jobId == event.jobId })
            }
            is JobEvent.Completed -> {
                clearPrompt(event.jobId)
                _messages.tryEmit(ForgeMessage("Job ${event.jobId.raw.take(13)}… completed", StatusTone.READY))
            }
            is JobEvent.Failed -> {
                clearPrompt(event.jobId)
                _messages.tryEmit(ForgeMessage("Job failed: ${event.reason.take(80)}", StatusTone.ERROR))
            }
            is JobEvent.Cancelled -> {
                clearPrompt(event.jobId)
                _messages.tryEmit(ForgeMessage("Job cancelled", StatusTone.INFO))
            }
            is JobEvent.StateChanged -> refreshJobs() // live job rows (QUEUED→RUNNING→terminal)
            else -> Unit
        }
    }

    private fun clearPrompt(jobId: JobId) {
        _state.update { state ->
            state.copy(pendingPrompts = state.pendingPrompts.filterNot { it.jobId == jobId })
        }
    }

    // ---- settings (§77/§78: runtime facts, diagnostics, repair) ----------------

    /** Immutable-in-composition runtime facts for the Settings screen. */
    public data class RuntimeFacts(
        val packageName: String,
        val versionName: String,
        val architecture: String?,
        val prefix: String?,
        val home: String?,
        val pluginsRoot: String?,
        val outputRoot: String?,
        val jobsRoot: String?,
        val pluginCount: Int,
        val activeJobCount: Int,
        val pendingImportCount: Int,
    )

    /** Real device/runtime facts — every value is read, never invented. */
    public fun runtimeFacts(): RuntimeFacts {
        val termux = runtime as? com.forgekit.runtime.termux.EmbeddedTermuxRuntime
        val app = getApplication<ForgeKitApplication>()
        return RuntimeFacts(
            packageName = app.packageName,
            versionName = runCatching {
                app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
            }.getOrDefault("?"),
            architecture = android.os.Build.SUPPORTED_ABIS?.firstOrNull(),
            prefix = termux?.paths?.termuxPrefix?.toString(),
            home = termux?.paths?.termuxHome?.toString(),
            pluginsRoot = forgeApp.pluginsRoot().toString(),
            outputRoot = forgeApp.environment.publicOutputDirectory().toString(),
            jobsRoot = "app database: forgekit-jobs.db",
            pluginCount = _state.value.plugins.size,
            activeJobCount = _state.value.jobs.count { !it.terminal },
            pendingImportCount = _state.value.pendingImports.size,
        )
    }

    /** §71 health-check battery; results surface through the messages stream. */
    public fun runDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { runtime.healthCheck() }
                .onSuccess { health ->
                    _messages.tryEmit(
                        ForgeMessage(
                            "health ${health.state}: " +
                                health.checks.joinToString { "${it.name}=${if (it.passed) "ok" else "FAIL"}" },
                            StatusTone.INFO,
                        ),
                    )
                }
                .onFailure { _messages.tryEmit(ForgeMessage("diagnostics failed: ${it.message ?: it::class.simpleName}", StatusTone.ERROR)) }
        }
    }

    /** §32 runtime repair, then re-initialize (initialize is idempotent per contract). */
    public fun repairRuntime() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { runtime.repair() }
                .onSuccess { result ->
                    _messages.tryEmit(
                        ForgeMessage(
                            if (result.repaired) {
                                "repair ok (${result.actions.size} actions) → ${result.resultingState}"
                            } else {
                                "repair finished, nothing to fix (${result.resultingState})"
                            },
                            if (result.repaired) StatusTone.READY else StatusTone.INFO,
                        ),
                    )
                }
                .onFailure { _messages.tryEmit(ForgeMessage("repair failed: ${it.message ?: it::class.simpleName}", StatusTone.ERROR)) }
            forgeApp.initializeRuntime()
        }
    }

    override fun onCleared() {
        store.close()
        super.onCleared()
    }
}
