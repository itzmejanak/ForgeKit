package com.forgekit.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.forgekit.ui.design.ForgeToast
import com.forgekit.ui.design.ForgeToastVisuals
import com.forgekit.ui.design.ForgeInteractionDock
import com.forgekit.ui.design.ForgePromptKind
import com.forgekit.ui.design.ForgePromptVisual
import com.forgekit.app.navigation.Overlay
import com.forgekit.app.navigation.Tab
import com.forgekit.app.navigation.rememberForgeNavState
import com.forgekit.app.screens.ActionRunScreen
import com.forgekit.app.screens.HomeScreen
import com.forgekit.app.screens.JobDetailScreen
import com.forgekit.app.screens.JobsScreen
import com.forgekit.app.screens.PluginDetailScreen
import com.forgekit.app.screens.PluginListScreen
import com.forgekit.app.screens.PluginUiHostScreen
import com.forgekit.app.screens.SettingsScreen
import com.forgekit.app.screens.importflow.ImportScreen
import com.forgekit.app.terminal.TerminalScreen
import com.forgekit.plugin.api.PluginDescriptor
import com.forgekit.ui.design.ForgeBackground
import com.forgekit.ui.design.ForgeLazyPage
import com.forgekit.ui.design.ForgeNoticeBox
import com.forgekit.ui.design.ForgePageHeader
import com.forgekit.ui.design.ForgePalette
import com.forgekit.ui.design.ForgeShapes
import com.forgekit.ui.design.ForgeSpacing
import com.forgekit.ui.design.StatusTone
import java.nio.file.Path

/**
 * The ForgeKit app shell: five tabs plus an overlay back stack ([ForgeNavState]).
 * Runtime-gated; all state comes from [ForgeViewModel] (no screen invents data).
 *
 * Navigation contract:
 *  - the visible screen is the top overlay, else the selected tab;
 *  - tapping a tab clears the overlay stack (tab content always shows);
 *  - system Back pops one overlay before leaving the app;
 *  - the terminal goes full-screen (nav hidden) while its keyboard is up.
 */
@Composable
public fun ForgeApp(
    bootContent: @Composable () -> Unit,
    viewModel: ForgeViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val messages by viewModel.messages.collectAsState(initial = ForgeMessage("", StatusTone.NEUTRAL))
    val snackbar = remember { SnackbarHostState() }
    val nav = rememberForgeNavState()
    val context = LocalContext.current
    val promptVisuals = state.pendingPrompts.map { pending ->
        ForgePromptVisual(
            key = "${pending.jobId.raw}:${pending.prompt.id}",
            tabLabel = state.plugins.firstOrNull { it.id.raw == pending.pluginId }?.name
                ?: pending.pluginId,
            title = pending.prompt.title,
            context = "${pending.pluginId} · ${pending.action} · ${pending.trust}",
            kind = when (pending.prompt.kind) {
                com.forgekit.job.api.JobPromptKind.CONFIRM -> ForgePromptKind.CONFIRM
                com.forgekit.job.api.JobPromptKind.TEXT -> ForgePromptKind.TEXT
                com.forgekit.job.api.JobPromptKind.PASSWORD -> ForgePromptKind.PASSWORD
                com.forgekit.job.api.JobPromptKind.SELECT -> ForgePromptKind.SELECT
            },
            message = pending.prompt.message,
            required = pending.prompt.required,
            choices = pending.prompt.choices,
            default = pending.prompt.default,
            placeholder = pending.prompt.placeholder,
        )
    }

    var reviewPlans by remember { mutableStateOf<List<com.forgekit.plugin.resolver.DependencyPlan>?>(null) }
    val currentReview = (nav.current as? Overlay.Import)?.review

    LaunchedEffect(messages) {
        messages.takeIf { it.text.isNotBlank() }?.let {
            snackbar.showSnackbar(ForgeToastVisuals(message = it.text, tone = it.tone))
        }
    }
    // Live presence for the review (real runtime facts, not static tags); refresh when it clears.
    LaunchedEffect(currentReview, state.pendingImports.size) {
        if (currentReview == null) {
            viewModel.refreshPlugins()
            reviewPlans = null
        } else {
            reviewPlans = viewModel.reviewDependencyPlans(currentReview)
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            // copy the picked archive into app storage — content URIs are not seekable
            // paths, and the import gate requires a real file
            val target: Path = context.cacheDir.toPath().resolve("import-${System.currentTimeMillis()}.forge")
            context.contentResolver.openInputStream(uri)?.use { input ->
                java.nio.file.Files.copy(input, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            if (java.nio.file.Files.exists(target)) {
                // Quarantine copy done — the open Import page inspects the path itself
                // (Idle → Inspecting → Review) so the whole flow stays on one page.
                nav.replaceTop(Overlay.Import(pendingPath = target))
            }
        }
    }

    // Runtime boot screen until the embedded runtime is READY (§70)
    if (state.runtimeState != com.forgekit.runtime.api.RuntimeState.READY) {
        bootContent()
        return
    }

    // In-app Back pops one overlay before the system handles it.
    BackHandler(enabled = nav.canPop) { nav.pop() }

    val manifestOf = { descriptor: PluginDescriptor -> viewModel.pluginManager.manifest(descriptor.id) }
    // Overlays carry the descriptor they were opened with; screens always render the live
    // registry record so status changes (e.g. provisioning → READY) show without re-opening.
    val liveDescriptor = { opened: PluginDescriptor -> state.plugins.firstOrNull { it.id == opened.id } ?: opened }

    // Terminal is full-screen (no tab bar) only while its soft keyboard is up, so the bar is
    // never shoved upward yet stays reachable when the keyboard is down.
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val hideNav = nav.tab == Tab.TERMINAL && nav.current == null && imeVisible
    // Anything that sits flush on the nav bar — the terminal's extra-keys bar, a toast, or the
    // prompt dock — squares its top corners so no canvas notches show between the two surfaces;
    // otherwise the nav keeps its soft top round.
    val flushAboveNav = (nav.tab == Tab.TERMINAL && nav.current == null) ||
        snackbar.currentSnackbarData != null ||
        promptVisuals.isNotEmpty()
    val navCorner by animateDpAsState(
        targetValue = if (flushAboveNav) 0.dp else ForgeShapes.navBarCorner,
        label = "nav-corner",
    )

    ForgeBackground {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = {
                Column(Modifier.fillMaxWidth()) {
                    SnackbarHost(snackbar) { data -> ForgeToast(data) }
                    ForgeInteractionDock(
                        prompts = promptVisuals,
                        onSubmit = { visual, value ->
                            state.pendingPrompts.firstOrNull {
                                "${it.jobId.raw}:${it.prompt.id}" == visual.key
                            }?.let { pending ->
                                viewModel.submitPrompt(pending.jobId, pending.prompt.id, value)
                            }
                        },
                        onCancel = { visual ->
                            state.pendingPrompts.firstOrNull {
                                "${it.jobId.raw}:${it.prompt.id}" == visual.key
                            }?.let { pending ->
                                viewModel.dismissPrompt(pending.jobId, pending.prompt.id)
                            }
                        },
                    )
                }
            },
            bottomBar = {
                if (!hideNav) {
                    NavigationBar(
                        modifier = Modifier.clip(RoundedCornerShape(topStart = navCorner, topEnd = navCorner)),
                        containerColor = ForgePalette.surface,
                        tonalElevation = 0.dp,
                    ) {
                        for (t in Tab.entries) {
                            ForgeNavItem(t, selected = nav.tab == t && nav.current == null) { nav.selectTab(t) }
                        }
                    }
                }
            },
        ) { padding ->
            val screenModifier = Modifier.padding(padding)
            // Pages with the bottom tab bar get one, app-wide clear strip between the
            // last card/row and the bar so every screen keeps the same 16.dp benchmark.
            // The terminal is deliberately excluded: its output + extra-keys bar are a
            // full-bleed surface that should sit flush against the tab bar.
            val contentModifier = screenModifier.padding(bottom = ForgeSpacing.listBottomClear)
            when (val overlay = nav.current) {
                is Overlay.Import -> ImportScreen(
                    overlay = overlay,
                    livePlans = reviewPlans,
                    provisioning = overlay.review?.descriptor?.id?.raw?.let { id -> state.provisioning[id] },
                    onSelectDocument = { picker.launch("*/*") },
                    onApprove = { done ->
                        overlay.review?.let { review -> viewModel.approveImport(review.descriptor.id, onInstalled = done) } ?: false
                    },
                    onReject = {
                        val removed = overlay.review?.let { review -> viewModel.removePlugin(review.descriptor.id) } ?: true
                        if (removed) nav.pop()
                    },
                    onOpenPlugin = { descriptor -> nav.replaceTop(Overlay.PluginDetail(descriptor)) },
                    onOpenJob = { jobId -> nav.push(Overlay.JobDetail(jobId)) },
                    onInspect = { path ->
                        viewModel.importArchive(
                            path,
                            onReviewed = { review -> nav.replaceTop(Overlay.Import(review = review)) },
                            onOpenFailed = { nav.replaceTop(Overlay.Import()) },
                        )
                    },
                    onBack = { nav.pop() },
                    modifier = contentModifier,
                )
                is Overlay.PluginUiHost -> PluginUiHostScreen(
                    descriptor = liveDescriptor(overlay.descriptor),
                    viewModel = viewModel,
                    onBack = { nav.pop() },
                    modifier = contentModifier,
                )
                is Overlay.ActionDraft -> {
                    val manifest = manifestOf(overlay.descriptor)
                    val action = manifest?.actions?.firstOrNull { it.id == overlay.actionId }
                    if (action == null) {
                        ForgeLazyPage(
                            modifier = contentModifier,
                            header = { ForgePageHeader(title = "Action not found", onBack = { nav.pop() }) },
                        ) {
                            item {
                                ForgeNoticeBox(
                                    text = "${overlay.descriptor.id.raw}/${overlay.actionId} is not declared by the installed manifest",
                                    tone = StatusTone.ERROR,
                                )
                            }
                        }
                    } else {
                        ActionRunScreen(
                            descriptor = liveDescriptor(overlay.descriptor),
                            action = action,
                            onRun = { input ->
                                // Accepted runs open their own live job log; Back returns to this draft.
                                viewModel.runAction(liveDescriptor(overlay.descriptor), overlay.actionId, input) { jobId ->
                                    if (nav.current == overlay) nav.push(Overlay.JobDetail(jobId))
                                }
                            },
                            onBack = { nav.pop() },
                            modifier = contentModifier,
                        )
                    }
                }
                is Overlay.PluginDetail -> PluginDetailScreen(
                    descriptor = liveDescriptor(overlay.descriptor),
                    actionCounts = { capability ->
                        when (capability.kind) {
                            "ui" -> "declarative ui"
                            else -> {
                                val manifest = manifestOf(overlay.descriptor)
                                val a = manifest?.actions?.firstOrNull { it.id == capability.name }
                                "${a?.inputs?.size ?: 0} inputs · ${a?.options?.size ?: 0} options"
                            }
                        }
                    },
                    viewModel = viewModel,
                    onProvision = { viewModel.provision(overlay.descriptor.id) },
                    onOpenJob = { jobId -> nav.push(Overlay.JobDetail(jobId)) },
                    onRunAction = { capability ->
                        nav.push(Overlay.ActionDraft(overlay.descriptor, capability.name))
                    },
                    onRemove = {
                        if (viewModel.removePlugin(overlay.descriptor.id)) nav.dropOverlaysFor(overlay.descriptor.id)
                    },
                    onBack = { nav.pop() },
                    onOpenUi = manifestOf(overlay.descriptor)?.ui?.let {
                        { nav.push(Overlay.PluginUiHost(overlay.descriptor)) }
                    },
                    modifier = contentModifier,
                )
                is Overlay.JobDetail -> JobDetailScreen(
                    viewModel = viewModel,
                    jobId = overlay.jobId,
                    onBack = { nav.pop() },
                    modifier = contentModifier,
                )
                null -> when (nav.tab) {
                    Tab.HOME -> HomeScreen(
                        state = state,
                        onImport = { nav.push(Overlay.Import()) },
                        onOpenJobs = { nav.selectTab(Tab.JOBS) },
                        onOpenPlugin = { nav.push(Overlay.PluginDetail(it)) },
                        modifier = contentModifier,
                    )
                    Tab.PLUGINS -> PluginListScreen(
                        plugins = state.plugins,
                        pendingImports = state.pendingImports.size,
                        runtimeState = state.runtimeState,
                        provisioning = state.provisioning,
                        onImport = { nav.push(Overlay.Import()) },
                        onOpenPlugin = { nav.push(Overlay.PluginDetail(it)) },
                        modifier = contentModifier,
                    )
                    Tab.JOBS -> JobsScreen(
                        jobs = state.jobs,
                        provisioning = state.provisioning,
                        onCancel = { viewModel.cancelJob(it.id) },
                        onClear = { viewModel.clearJobs() },
                        onOpenJob = { nav.push(Overlay.JobDetail(it.id)) },
                        onDelete = { viewModel.deleteJob(it.id) },
                        modifier = contentModifier,
                    )
                    Tab.TERMINAL -> TerminalScreen(
                        viewModel = viewModel,
                        modifier = screenModifier,
                    )
                    Tab.SETTINGS -> SettingsScreen(
                        viewModel = viewModel,
                        modifier = contentModifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.ForgeNavItem(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    val indicator by animateColorAsState(
        targetValue = if (selected) ForgePalette.primary.copy(alpha = 0.14f) else Color.Transparent,
        label = "nav-indicator",
    )
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            // Own indicator (soft rectangle) instead of Material's full pill.
            Box(
                modifier = Modifier
                    .size(width = 56.dp, height = 32.dp)
                    .background(indicator, ForgeShapes.navIndicator),
                contentAlignment = Alignment.Center,
            ) {
                Icon(tab.icon, contentDescription = tab.label)
            }
        },
        label = { Text(tab.label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = ForgePalette.primary,
            selectedTextColor = ForgePalette.textPrimary,
            unselectedIconColor = ForgePalette.textMuted,
            unselectedTextColor = ForgePalette.textMuted,
            indicatorColor = Color.Transparent,
        ),
    )
}
