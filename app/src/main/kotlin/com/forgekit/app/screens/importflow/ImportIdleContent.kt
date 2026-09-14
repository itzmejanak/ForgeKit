package com.forgekit.app.screens.importflow

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.forgekit.ui.design.ForgeEmptyState
import com.forgekit.ui.design.ForgePalette
import com.forgekit.ui.design.ForgePrimaryButton
import com.forgekit.ui.design.ForgeSpacing
import com.forgekit.ui.design.ForgeTypography

/** Step 1: the document picker hero. */
@Composable
internal fun ImportIdleContent(onSelectDocument: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        ForgeEmptyState(
            icon = Icons.Outlined.FolderOpen,
            title = "Choose a .forge package",
            message = "ForgeKit copies the selected document into private quarantine, validates its " +
                "archive and canonical manifest, verifies any signature evidence, then shows the " +
                "exact facts before registration.",
            tint = ForgePalette.primary,
            action = {
                ForgePrimaryButton(
                    text = "Select document",
                    icon = Icons.Outlined.FolderOpen,
                    onClick = onSelectDocument,
                )
            },
        )
        Text(
            "The system picker may list all document types because .forge is a custom extension. " +
                "Validation accepts only a non-empty .forge filename.",
            style = ForgeTypography.monoValue,
            color = ForgePalette.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = ForgeSpacing.gutter),
        )
    }
}
