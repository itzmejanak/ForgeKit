package com.forgekit.app.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.forgekit.app.ForgeViewModel
import com.forgekit.app.screens.component.stripAnsi
import com.forgekit.job.api.JobEvent
import com.forgekit.job.api.JobId
import com.forgekit.plugin.api.PluginDescriptor
import com.forgekit.plugin.manifest.PluginManifest
import com.forgekit.ui.design.ForgeLazyPage
import com.forgekit.ui.design.ForgeNoticeBox
import com.forgekit.ui.design.ForgePageHeader
import com.forgekit.ui.design.StatusTone
import com.forgekit.ui.plugins.ForgeUiHost
import com.forgekit.ui.plugins.ForgeUiRenderer
import com.forgekit.ui.plugins.UiDocument
import com.forgekit.ui.plugins.UiDocumentParser
import com.forgekit.ui.plugins.UiState
/**
 * Hosts one plugin's declarative `forgekit.ui/v1` document (M6 renderer, now
 * reachable from PluginDetailScreen → "Open interface").
 *
 * The document is parsed from the INSTALLED tree (no import-time copies);
 * action blocks invoke through [ForgeViewModel.runAction] exactly like the
 * run draft — the renderer NEVER executes anything itself (Rule 1).
 *
 * File/directory blocks reuse the app's standard picker (cache copy).
 */
@Composable
public fun PluginUiHostScreen(
    descriptor: PluginDescriptor,
    viewModel: ForgeViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val manifest = remember(descriptor.id) { viewModel.pluginManager.manifest(descriptor.id) }

    val parseError = remember(descriptor.id, manifest) {
        if (manifest == null) {
            "plugin ${descriptor.id.raw} has no installed manifest — re-import it"
        } else if (manifest.ui == null) {
            "plugin declares no ui entry"
        } else {
            val root = descriptor.installedPath
            if (root.isNullOrBlank()) {
                "plugin is not installed yet — approve the import first"
            } else {
                try {
                    val target = java.nio.file.Paths.get(root).resolve(manifest.ui!!.entry).normalize()
                    if (!java.nio.file.Files.isRegularFile(target)) {
                        "ui entry missing: ${manifest.ui!!.entry}"
                    } else {
                        null // parsable below
                    }
                } catch (e: Exception) {
                    "ui entry unreadable: ${e.message}"
                }
            }
        }
    }

    val bundle: Pair<PluginManifest, UiDocument>? = remember(descriptor.id, manifest, parseError) {
        val ok = manifest != null && manifest.ui != null &&
            parseError == null && !descriptor.installedPath.isNullOrBlank()
        if (!ok) {
            return@remember null
        }
        runCatching {
            val target = java.nio.file.Paths.get(descriptor.installedPath!!)
                .resolve(manifest!!.ui!!.entry).normalize()
            val document: UiDocument = UiDocumentParser.parse(java.nio.file.Files.readAllBytes(target), manifest)
            Pair(manifest, document)
        }.getOrElse { null }
    }

    val header: @Composable () -> Unit = {
        ForgePageHeader(
            title = descriptor.name,
            subtitle = "${descriptor.id.raw} · ${descriptor.version.raw}",
            onBack = onBack,
        )
    }

    if (bundle == null) {
        ForgeLazyPage(modifier = modifier, header = header) {
            item {
                ForgeNoticeBox(
                    title = "Interface unavailable",
                    text = parseError ?: "ui document rejected by the validator",
                    tone = StatusTone.ERROR,
                )
            }
        }
        return
    }

    val (manifestDoc, document) = bundle
    val uiState = remember(document) { UiState(manifestDoc, document) }
    var pendingPick by remember(document) { mutableStateOf<Pair<String, String>?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val pending = pendingPick
        pendingPick = null
        if (uri != null && pending != null) {
            val (blockId, inputId) = pending
            val target = context.cacheDir.toPath()
                .resolve("ui-input-${System.currentTimeMillis()}-$inputId")
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    java.nio.file.Files.copy(input, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
                uiState.setValue(blockId, target.toString())
                uiState.setFilePending(blockId, false)
            }.onFailure {
                uiState.setFilePending(blockId, false)
            }
        }
    }

    // The latest run started from this interface feeds its progress and log blocks.
    val currentDescriptor by rememberUpdatedState(descriptor)
    var activeJob by remember(document) { mutableStateOf<JobId?>(null) }
    var progress by remember(document) { mutableStateOf<Float?>(null) }
    val logLines = remember(document) { mutableStateListOf<String>() }
    LaunchedEffect(activeJob) {
        val jobId = activeJob ?: return@LaunchedEffect
        progress = null
        logLines.clear()
        viewModel.jobEvents(jobId).collect { event ->
            when (event) {
                is JobEvent.Progress -> progress = event.value.toFloat()
                is JobEvent.Completed -> progress = 1f
                // forgekit.ui/v1 `log` blocks render protocol log lines (channel plugin:<level>).
                is JobEvent.LogLine -> if (event.channel.startsWith("plugin")) logLines.add(stripAnsi(event.line))
                else -> Unit
            }
        }
    }

    val host = remember(uiState, document) {
        object : ForgeUiHost {
            override fun onInvoke(actionId: String) {
                runCatching {
                    val input = uiState.inputPayload(actionId)
                    viewModel.runAction(currentDescriptor, actionId, input) { jobId -> activeJob = jobId }
                }.onFailure { throwable ->
                    // inputPayload gates itself; failures surface as a toast via runAction
                }
            }

            override fun onPickFile(blockId: String, inputId: String, kind: String) {
                uiState.setFilePending(blockId, true)
                pendingPick = blockId to inputId
                picker.launch("*/*")
            }

            override fun progressOf(blockId: String): Float? = progress

            override fun logLinesOf(blockId: String): List<String> = logLines.toList()
        }
    }

    ForgeLazyPage(modifier = modifier, header = header) {
        item {
            ForgeUiRenderer(document = document, state = uiState, host = host)
        }
    }
}
