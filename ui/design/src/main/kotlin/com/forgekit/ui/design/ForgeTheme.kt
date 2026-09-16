package com.forgekit.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.forgekit.ui.design.R

/**
 * ForgeKit design language: a charcoal-green canvas with an ember accent.
 * Surfaces are green-tinted darks; the ember orange is reserved for "act now",
 * amber for warnings, terminal-green for success/console. Typography pairs
 * Chakra Petch (display, headings, controls) with IBM Plex Mono (states,
 * identifiers, console) over a quiet default body.
 *
 * ForgeKit is a tool surface, not a content feed: contrast comes from
 * hierarchy, not decoration. One accent color means one semantic.
 */
public object ForgePalette {
    // ground
    public val background: Color = Color(0xFF0A0D0B)
    public val backgroundDeep: Color = Color(0xFF060807)
    // surfaces
    public val surface: Color = Color(0xFF131812)
    public val surfaceElevated: Color = Color(0xFF1C231B)
    public val surfaceInput: Color = Color(0xFF131812)
    public val track: Color = Color(0xFF232B22)
    public val outline: Color = Color(0xFF2A332A)
    public val outlineFocused: Color = Color(0xFFFF6B2C)

    // ember accent (primary action)
    public val primary: Color = Color(0xFFFF6B2C)
    public val primaryContainer: Color = Color(0xFFB34417)
    public val onPrimary: Color = Color(0xFF1F0D04)
    public val onPrimaryContainer: Color = Color(0xFFE9EFE7)

    // secondary (amber) + tertiary (terminal green)
    public val secondary: Color = Color(0xFFFFC24B)
    public val onSecondary: Color = Color(0xFF1F1600)
    public val secondaryContainer: Color = Color(0xFF332609)
    public val onSecondaryContainer: Color = Color(0xFFFFE4A8)
    public val tertiary: Color = Color(0xFF43D9A3)
    public val onTertiary: Color = Color(0xFF00200F)
    public val tertiaryContainer: Color = Color(0xFF0F291D)
    public val onTertiaryContainer: Color = Color(0xFF8FF0CC)

    // text
    public val textPrimary: Color = Color(0xFFE9EFE7)
    public val textSecondary: Color = Color(0xFF9AA69A)
    public val textMuted: Color = Color(0xFF5F6C60)

    // status
    public val success: Color = Color(0xFF43D9A3)
    public val warning: Color = Color(0xFFFFC24B)
    public val error: Color = Color(0xFFFF5449)
    public val info: Color = Color(0xFF6AA9FF)

    public val warningContainer: Color = Color(0xFF332609)
    public val errorContainer: Color = Color(0xFF2B0503)
    public val successContainer: Color = Color(0xFF0F291D)
    public val infoContainer: Color = Color(0xFF10233D)
}

/**
 * One spacing scale for the whole app. Screens, cards, lists and the tab bar all
 * consume these tokens so vertical rhythm, gutters and bottom clear amount stay
 * identical across pages instead of drifting between ad-hoc `.dp` literals.
 */
public object ForgeSpacing {
    /** Outer page gutter — every screen maintains this horizontal breathing room (20.dp). */
    public val gutter: Dp = 20.dp

    /** Rhythm between list rows / cards, and between a header and the first block. */
    public val rowGap: Dp = 12.dp

    /** Padding used to set a page header/title block off the top of the screen. */
    public val headerInset: Dp = 8.dp

    /** Toasts never span the whole screen — cap so full-screen terminal stays readable. */
    public val toastMax: Dp = 360.dp

    /** Inner padding of a card (between a card edge and its first content row). */
    public val cardInner: Dp = 16.dp

    /** Tight grouping inside a row (icon/metadata pairs, small in-line columns). */
    public val compact: Dp = 8.dp

    /** Fine gaps (label-chip spacing, tiny metadata dividers). */
    public val micro: Dp = 4.dp

    /** Hairline/near-invisible gaps (separator line insets, 1.dp-scale detail). */
    public val hairline: Dp = 2.dp

    /** New: the clear strip between the last list card and the bottom tab bar. */
    public val listBottomClear: Dp = 16.dp

    /** Bottom navigation bar of the app (matches Material3's default height). */
    public val navBarHeight: Dp = 80.dp

    /** Vertical rhythm between rows inside one card. */
    public val cardGap: Dp = 10.dp

    /** Height of every full-width button. */
    public val buttonHeight: Dp = 52.dp

    /** Minimum size of any tappable control (Material accessibility floor). */
    public val touchTarget: Dp = 48.dp

    /** Height of a log console embedded in a scrolling page (plugin detail, plugin UI log block). */
    public val consoleEmbeddedHeight: Dp = 240.dp
}

/** Headings, controls and display type — Chakra Petch. */
public val ChakraPetch: FontFamily = FontFamily(
    Font(R.font.chakra_petch_regular, FontWeight.Normal),
    Font(R.font.chakra_petch_semibold, FontWeight.SemiBold),
    Font(R.font.chakra_petch_bold, FontWeight.Bold),
)

/** Identifiers, states, metadata and console output — IBM Plex Mono. */
public val PlexMono: FontFamily = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
)

/** Typography contract: letterspaced mono micro-labels, PlexMono metadata, ChakraPetch headings. */
public object ForgeTypography {
    public val sectionHeader: TextStyle = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 2.sp,
    )
    public val badge: TextStyle = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 1.2.sp,
    )
    public val monoValue: TextStyle = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        letterSpacing = 0.2.sp,
    )
    public val labelSmall: TextStyle = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 1.2.sp,
    )
    public val labelLarge: TextStyle = TextStyle(
        fontFamily = ChakraPetch,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
    )
    public val titleLarge: TextStyle = TextStyle(
        fontFamily = ChakraPetch,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
    )
    public val headlineMedium: TextStyle = TextStyle(
        fontFamily = ChakraPetch,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
    )
    public val displaySmall: TextStyle = TextStyle(
        fontFamily = ChakraPetch,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        letterSpacing = (-0.3).sp,
    )
    /** A machine state shown as the main value of a card (runtime state on the boot screen). */
    public val stateValue: TextStyle = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
    )
    public val body: TextStyle = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
    )
    public val caption: TextStyle = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
    )

    /** Log / console lines: mono with a fixed line height so tails stay stable while streaming. */
    public val console: TextStyle = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Normal,
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
    )
}

/** Shape contract: cards are large-radius, controls are comfortable. */
public object ForgeShapes {
    public val card = RoundedCornerShape(18.dp)
    public val chip = RoundedCornerShape(50)
    public val control = RoundedCornerShape(14.dp)
    public val toast = RoundedCornerShape(10.dp)
    /** Top-corner radius of the bottom navigation bar (squared where a bar sits flush above it). */
    public val navBarCorner: Dp = 16.dp
    /** Active bottom-navigation destination: a soft rectangle, not a full pill. */
    public val navIndicator = RoundedCornerShape(10.dp)
}

/**
 * The one theme composable. ForgeKit is dark-only by design: the runtime
 * surface (terminals, logs, hexdumps) assumes a dark ground, and a tool
 * surface should not re-think itself per system setting.
 */
@Composable
public fun ForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ForgePalette.primary,
            onPrimary = ForgePalette.onPrimary,
            primaryContainer = ForgePalette.primaryContainer,
            onPrimaryContainer = ForgePalette.onPrimaryContainer,
            secondary = ForgePalette.secondary,
            onSecondary = ForgePalette.onSecondary,
            secondaryContainer = ForgePalette.secondaryContainer,
            onSecondaryContainer = ForgePalette.onSecondaryContainer,
            tertiary = ForgePalette.tertiary,
            onTertiary = ForgePalette.onTertiary,
            tertiaryContainer = ForgePalette.tertiaryContainer,
            onTertiaryContainer = ForgePalette.onTertiaryContainer,
            background = ForgePalette.background,
            onBackground = ForgePalette.textPrimary,
            surface = ForgePalette.surface,
            onSurface = ForgePalette.textPrimary,
            surfaceVariant = ForgePalette.surfaceElevated,
            onSurfaceVariant = ForgePalette.textSecondary,
            surfaceTint = Color.Transparent,
            outline = ForgePalette.outline,
            outlineVariant = ForgePalette.track,
            error = ForgePalette.error,
            onError = ForgePalette.onPrimary,
        ),
        content = content,
    )
}
