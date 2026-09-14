package com.forgekit.app.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.graphics.vector.ImageVector
import com.forgekit.job.api.JobId
import com.forgekit.plugin.api.PluginDescriptor
import com.forgekit.plugin.api.PluginId
import com.forgekit.plugin.manager.PluginManager
import java.nio.file.Path

/** Bottom-tab destinations. The tab bar always resets any overlay stack (see [ForgeNavState.selectTab]). */
public enum class Tab(public val label: String, public val icon: ImageVector) {
    HOME("home", Icons.Filled.Bolt),
    PLUGINS("plugins", Icons.Filled.Widgets),
    JOBS("jobs", Icons.Filled.History),
    TERMINAL("terminal", Icons.Filled.Terminal),
    SETTINGS("settings", Icons.Filled.Settings),
}

/**
 * A screen pushed on top of the current [Tab]. Overlays form a real back stack, so
 * detail → run-draft → back returns to detail, and tapping any tab clears the stack.
 * This replaces the old order-dependent boolean routing (detail/draft/uiHost/review).
 */
public sealed interface Overlay {
    public data class PluginDetail(val descriptor: PluginDescriptor) : Overlay
    public data class ActionDraft(val descriptor: PluginDescriptor, val actionId: String) : Overlay
    public data class PluginUiHost(val descriptor: PluginDescriptor) : Overlay

    /**
     * The dedicated import flow page. Phase is derived from its fields:
     * `review == null && pendingPath == null` is the Idle picker;
     * `pendingPath != null` is Inspecting; `review != null` is the Review
     * (which renders rejected packages inline). Installing/Installed are
     * local to the screen, after the user approves.
     */
    public data class Import(
        public val review: PluginManager.ImportReview? = null,
        public val pendingPath: Path? = null,
    ) : Overlay

    public data class JobDetail(val jobId: JobId) : Overlay
}

/**
 * The single source of truth for what the shell shows: one selected [tab] plus a stack of
 * [overlays]. The visible screen is the top overlay, or the tab when the stack is empty.
 */
public class ForgeNavState(initialTab: Tab, overlays: List<Overlay>) {
    public var tab: Tab by mutableStateOf(initialTab)
        private set

    private val stack: SnapshotStateList<Overlay> = overlays.toMutableStateList()

    /** Top overlay, or null when a bare tab is showing. */
    public val current: Overlay? get() = stack.lastOrNull()

    /** True when there is an overlay to pop (drives in-app Back). */
    public val canPop: Boolean get() = stack.isNotEmpty()

    /** Switch tabs and drop every overlay so the tab's own content is shown (fixes dead tab taps). */
    public fun selectTab(next: Tab) {
        tab = next
        stack.clear()
    }

    /** Push an overlay on top of the current screen. */
    public fun push(overlay: Overlay) {
        stack.add(overlay)
    }

    /** Swap the top overlay (used to advance the import flow without growing the stack). */
    public fun replaceTop(overlay: Overlay) {
        if (stack.isEmpty()) {
            stack.add(overlay)
        } else {
            stack[stack.lastIndex] = overlay
        }
    }

    /** Pop the top overlay. Returns false when nothing was popped (let the system handle Back). */
    public fun pop(): Boolean = stack.removeLastOrNull() != null

    /** Remove any PluginDetail/ActionDraft/UiHost overlays referencing [pluginId] (after removal). */
    public fun dropOverlaysFor(pluginId: PluginId) {
        stack.removeAll { overlay ->
            when (overlay) {
                is Overlay.PluginDetail -> overlay.descriptor.id == pluginId
                is Overlay.ActionDraft -> overlay.descriptor.id == pluginId
                is Overlay.PluginUiHost -> overlay.descriptor.id == pluginId
                else -> false
            }
        }
    }
}

@Composable
public fun rememberForgeNavState(initialTab: Tab = Tab.HOME): ForgeNavState {
    // Overlays hold live descriptors (not Parcelable), so the stack is intentionally not
    // restored across process death — a cold start lands on a bare tab, which is correct.
    return remember { ForgeNavState(initialTab = initialTab, overlays = emptyList()) }
}
