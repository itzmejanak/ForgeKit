package com.forgekit.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Page scaffolding shared by every screen: one header shape, one gutter, one
 * vertical rhythm. Screens pick [ForgeLazyPage] for scrolling content or
 * [ForgePage] when a region (console, action bar) must stay pinned.
 */

/**
 * The one page header. Overlay pages pass [onBack]; tab roots may pass a
 * [leadingIcon] instead. [trailing] hosts a status chip or icon action.
 */
@Composable
public fun ForgePageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    leadingIcon: ImageVector? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ForgeSpacing.touchTarget)
            .padding(top = ForgeSpacing.headerInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            onBack != null -> IconButton(onClick = onBack, modifier = Modifier.padding(end = ForgeSpacing.micro)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = ForgePalette.textPrimary)
            }
            leadingIcon != null -> {
                Icon(leadingIcon, contentDescription = null, tint = ForgePalette.primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(ForgeSpacing.rowGap))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = ForgeTypography.titleLarge,
                color = ForgePalette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let { MonoText(it) }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(ForgeSpacing.compact),
            verticalAlignment = Alignment.CenterVertically,
            content = trailing,
        )
    }
}

/** Scrolling page: gutter + row rhythm; [header] is the first item, never re-styled per screen. */
@Composable
public fun ForgeLazyPage(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    bottomPadding: Dp = 0.dp,
    header: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxSize().padding(horizontal = ForgeSpacing.gutter),
        contentPadding = PaddingValues(bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(ForgeSpacing.rowGap),
    ) {
        if (header != null) item(key = "forge-page-header") { header() }
        content()
    }
}

/**
 * Fixed page: header on top, [content] fills the remaining height (give one child
 * `Modifier.weight(1f)`), and an optional pinned [footer] action bar.
 */
@Composable
public fun ForgePage(
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().padding(horizontal = ForgeSpacing.gutter)) {
        header()
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = ForgeSpacing.compact),
            verticalArrangement = Arrangement.spacedBy(ForgeSpacing.rowGap),
            content = content,
        )
        if (footer != null) ForgeActionBar(content = footer)
    }
}

/** Vertical stack of page actions with the standard spacing (primary first). */
@Composable
public fun ForgeActionBar(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(top = ForgeSpacing.rowGap),
        verticalArrangement = Arrangement.spacedBy(ForgeSpacing.compact),
        content = content,
    )
}

/** Two or more equal-width buttons side by side (give each child `Modifier.weight(1f)`). */
@Composable
public fun ForgeButtonRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ForgeSpacing.compact),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
