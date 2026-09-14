package com.forgekit.app.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forgekit.ui.design.ForgePalette
import com.forgekit.ui.design.ForgeTypography
import com.forgekit.ui.terminal.ExtraModifier
import com.forgekit.ui.terminal.TerminalEmulator
import com.forgekit.ui.terminal.TerminalExtraKeys
import com.forgekit.ui.terminal.TerminalSelection
import com.forgekit.ui.terminal.TerminalView
import com.forgekit.ui.terminal.rememberTerminalMetrics
import kotlinx.coroutines.delay

/**
 * The live terminal surface: a full-bleed VT view with a Termux-style extra-keys bar,
 * pinch-to-zoom, sticky CTRL/ALT, and a long-press action menu (no header chrome).
 *
 * Layout is a [Column] that owns the IME inset: output (weight 1) then the extra-keys bar,
 * so the bar always sits directly above the soft keyboard and the output fills the rest.
 * Real rows/cols follow the measured size and font (SIGWINCH to bash via [TerminalEmulator.resize]).
 */
private val TERMINAL_HORIZONTAL_PAD = 12.dp

@Composable
internal fun TerminalSurface(
    emulator: TerminalEmulator,
    modifier: Modifier,
    onRestart: () -> Unit,
) {
    DisposableEffect(emulator) {
        emulator.start()
        onDispose {
            emulator.stop()
            emulator.killSession()
        }
    }

    val screen by emulator.screen.collectAsState()
    var fontSize by remember { mutableIntStateOf(DEFAULT_FONT_SIZE) }
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    val (cellWidth, cellHeight) = rememberTerminalMetrics(fontSize)
    // Fresh cell height for the long-lived scroll gesture (pointerInput keeps its lambda
    // across recompositions, so a captured `val` would go stale after a zoom).
    val cellHeightLatest by rememberUpdatedState(cellHeight)
    // Side breathing room so long lines never touch the screen edge; the grid columns
    // and gesture→cell mapping both subtract this inset so everything stays aligned.
    val terminalPadPx = with(LocalDensity.current) { TERMINAL_HORIZONTAL_PAD.toPx() }

    var ctrlArmed by remember(emulator) { mutableStateOf(false) }
    var altArmed by remember(emulator) { mutableStateOf(false) }
    var zoomAccum by remember { mutableFloatStateOf(1f) }
    var selection by remember(emulator) { mutableStateOf<TerminalSelection?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    val imeFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current
    var imeValue by remember(emulator) { mutableStateOf(TextFieldValue("")) }

    val requestKeyboard: () -> Unit = {
        imeFocus.requestFocus()
        keyboard?.show()
    }

    // Single outgoing path: any user input returns the view to the live grid (Termux
    // behavior), applies armed sticky modifiers, then clears them.
    val send: (ByteArray) -> Unit = { raw ->
        emulator.scrollToBottom()
        emulator.write(applyModifiers(raw, ctrlArmed, altArmed))
        if (ctrlArmed) ctrlArmed = false
        if (altArmed) altArmed = false
    }

    // Pixel offset → (row, col) cell in the visible grid; safe when metrics aren't ready.
    fun cellAt(x: Float, y: Float): Pair<Int, Int> {
        val col = if (cellWidth > 0f)
            ((x - terminalPadPx) / cellWidth).toInt().coerceAtLeast(0) else 0
        val row = if (cellHeight > 0f) (y / cellHeight).toInt() else 0
        return row.coerceIn(0, (screen.rows - 1).coerceAtLeast(0)) to
            col.coerceIn(0, (screen.columns - 1).coerceAtLeast(0))
    }

    // Real rows/cols follow the measured size and current font (SIGWINCH to bash).
    // Coalesced: a pinch changes font/metrics continuously, and each step restarts this
    // effect — the short delay absorbs the churn so the pty sees one resize per zoom,
    // while the reflowing buffer (and the live-metric renderer) stay consistent meanwhile.
    LaunchedEffect(cellWidth, cellHeight, surfaceSize) {
        if (surfaceSize != IntSize.Zero && cellWidth > 0f && cellHeight > 0f) {
            val usableWidth = (surfaceSize.width - terminalPadPx * 2).coerceAtLeast(0f)
            val columns = (usableWidth / cellWidth).toInt().coerceAtLeast(10)
            val rows = (surfaceSize.height / cellHeight).toInt().coerceAtLeast(3)
            delay(ZOOM_RESIZE_DEBOUNCE_MS)
            emulator.resize(columns, rows)
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds() // never let a glyph run spill past the terminal edge
                .onSizeChanged { surfaceSize = it }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (selection != null) selection = null else requestKeyboard()
                        },
                        onLongPress = { pos ->
                            val (r, c) = cellAt(pos.x, pos.y)
                            selection = TerminalSelection(r, c, r, c)
                        },
                    )
                }
                .pointerInput(selection != null) {
                    // While selecting, a drag extends the selection's focus cell.
                    if (selection != null) {
                        detectDragGestures { change, _ ->
                            val (r, c) = cellAt(change.position.x, change.position.y)
                            selection = selection?.copy(endRow = r, endCol = c)
                        }
                    }
                }
                .pointerInput(Unit) {
                    // One detector for both gestures (they never conflict — zoom needs two
                    // fingers, scroll one): pinch → font zoom; vertical pan → scrollback.
                    var scrollAccum = 0f
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (zoom != 1f) {
                            zoomAccum *= zoom
                            when {
                                zoomAccum > 1.15f -> {
                                    fontSize = (fontSize + 1).coerceAtMost(MAX_FONT_SIZE); zoomAccum = 1f
                                }
                                zoomAccum < 0.87f -> {
                                    fontSize = (fontSize - 1).coerceAtLeast(MIN_FONT_SIZE); zoomAccum = 1f
                                }
                            }
                        }
                        // Drag (not while selecting) scrolls the transcript. Finger down =
                        // pull older lines into view (viewport grows); accumulate sub-cell px.
                        val rowH = cellHeightLatest
                        if (selection == null && rowH > 0f && pan.y != 0f) {
                            scrollAccum += pan.y
                            val rowDelta = (scrollAccum / rowH).toInt()
                            if (rowDelta != 0) {
                                emulator.scrollTo(emulator.viewport + rowDelta)
                                scrollAccum -= rowDelta * rowH
                            }
                        }
                    }
                }
                .onPreviewKeyEvent { event ->
                    val bytes = mapHardwareKey(
                        event = event,
                        alternateScreen = screen.alternateScreen,
                        screenLines = screen.rows,
                        onScroll = { delta -> emulator.scrollTo(emulator.viewport + delta) },
                    ) ?: return@onPreviewKeyEvent false
                    send(bytes)
                    true
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = TERMINAL_HORIZONTAL_PAD)
                    .clipToBounds(),
            ) {
                TerminalView(
                    screen = screen,
                    modifier = Modifier.fillMaxSize(),
                    fontSize = fontSize,
                    selection = selection,
                    // Same metrics the resize used → columns and glyph advance can't diverge.
                    cellWidthOverride = cellWidth,
                    cellHeightOverride = cellHeight,
                )
            }

            // Invisible IME host: owns the soft-keyboard connection; committed text → bytes.
            BasicTextField(
                value = imeValue,
                onValueChange = { incoming ->
                    val old = imeValue.text
                    imeValue = TextFieldValue("")
                    val added = if (incoming.text.length > old.length) incoming.text.substring(old.length) else ""
                    if (added.isNotEmpty()) {
                        val normalized = added.replace("\r\n", "\r").replace("\n", "\r")
                        if (normalized.length == 1) {
                            send(normalized.toByteArray(Charsets.UTF_8))
                        } else {
                            // multi-char (paste / prediction): send raw, no per-byte ctrl
                            emulator.scrollToBottom()
                            emulator.write(normalized.toByteArray(Charsets.UTF_8))
                            ctrlArmed = false; altArmed = false
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(1.dp)
                    .alpha(0f)
                    .focusRequester(imeFocus),
                textStyle = TextStyle(fontSize = 1.sp, color = Color.Transparent),
                cursorBrush = SolidColor(Color.Transparent),
                singleLine = false,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
            )

            // Selection action bar (Termux-style): appears while text is selected.
            if (selection != null) {
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(ForgePalette.surfaceElevated)
                        .padding(horizontal = 4.dp),
                ) {
                    TerminalActionButton("Copy") {
                        val text = selection?.extractText(screen).orEmpty()
                        if (text.isNotEmpty()) clipboard.setText(AnnotatedString(text))
                        selection = null
                    }
                    TerminalActionButton("All") {
                        selection = TerminalSelection(0, 0, screen.rows - 1, screen.columns - 1)
                    }
                    TerminalActionButton("Paste") {
                        clipboard.getText()?.takeIf { it.isNotBlank() }?.let {
                            emulator.write(it.toString().toByteArray(Charsets.UTF_8))
                        }
                        selection = null
                    }
                    TerminalActionButton("✕") { selection = null }
                }
            }

            // Minimal session control (no header): a subtle top-right overflow for the
            // actions a shell can't do itself — clear scrollback view and restart.
            if (selection == null) {
                Box(Modifier.align(Alignment.TopEnd)) {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "Terminal menu",
                            tint = ForgePalette.textMuted,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (emulator.isScrolledBack) "Scroll to bottom" else "Clear screen") },
                            onClick = {
                                menuOpen = false
                                if (emulator.isScrolledBack) emulator.scrollToBottom()
                                else emulator.write("[2J[H".toByteArray())
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Restart shell") },
                            onClick = { menuOpen = false; onRestart() },
                        )
                    }
                }
            }
        }

        TerminalExtraKeys(
            ctrlArmed = ctrlArmed,
            altArmed = altArmed,
            onSend = send,
            onToggle = { mod ->
                when (mod) {
                    ExtraModifier.CTRL -> ctrlArmed = !ctrlArmed
                    ExtraModifier.ALT -> altArmed = !altArmed
                }
            },
        )
    }
}

/** One button on the selection action bar. */
@Composable
private fun TerminalActionButton(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = ForgePalette.textPrimary,
        style = ForgeTypography.labelLarge,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
