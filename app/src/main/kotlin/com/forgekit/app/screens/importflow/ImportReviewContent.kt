package com.forgekit.app.screens.importflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.forgekit.app.screens.component.toDependencyState
import com.forgekit.app.screens.component.toStepVisual
import com.forgekit.core.security.PermissionCatalog
import com.forgekit.core.security.TrustLevel
import com.forgekit.plugin.manager.PluginManager
import com.forgekit.plugin.resolver.DependencyPlan
import com.forgekit.ui.design.ForgeButtonRow
import com.forgekit.ui.design.ForgeCard
import com.forgekit.ui.design.ForgeDangerButton
import com.forgekit.ui.design.ForgeDivider
import com.forgekit.ui.design.ForgeNoticeBox
import com.forgekit.ui.design.ForgePalette
import com.forgekit.ui.design.ForgePrimaryButton
import com.forgekit.ui.design.ForgeSecondaryButton
import com.forgekit.ui.design.ForgeSpacing
import com.forgekit.ui.design.ForgeStepList
import com.forgekit.ui.design.ForgeTypography
import com.forgekit.ui.design.MetadataLine
import com.forgekit.ui.design.MonoText
import com.forgekit.ui.design.SectionHeader
import com.forgekit.ui.design.StatusChip
import com.forgekit.ui.design.StatusTone

/** Step 2: the §72 facts, one card per concern; rejected packages explain themselves inline. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ImportReviewContent(
    review: PluginManager.ImportReview,
    livePlans: List<DependencyPlan>?,
    modifier: Modifier = Modifier,
) {
    val report = review.report
    val descriptor = review.descriptor
    val classification = report.permissionClassification
    val dependencySteps = remember(livePlans, report.dependencyFacts) {
        (livePlans?.map { it.toDependencyState() } ?: report.dependencyFacts.map { it.toDependencyState() })
            .map { it.toStepVisual() }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ForgeSpacing.rowGap),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(ForgeSpacing.micro)) {
                Text(descriptor.name, style = ForgeTypography.titleLarge, color = ForgePalette.textPrimary)
                MonoText("${descriptor.id.raw} · ${descriptor.version.raw}")
                FlowRow(
                    Modifier.padding(top = ForgeSpacing.micro),
                    horizontalArrangement = Arrangement.spacedBy(ForgeSpacing.compact),
                    verticalArrangement = Arrangement.spacedBy(ForgeSpacing.micro),
                ) {
                    StatusChip(
                        label = report.trustLevel.name,
                        tone = if (report.trustLevel == TrustLevel.TRUSTED) StatusTone.READY else StatusTone.WARNING,
                    )
                    StatusChip(
                        label = if (report.signaturePresent) "SIGNED" else "UNSIGNED",
                        tone = if (report.signaturePresent) StatusTone.INFO else StatusTone.WARNING,
                    )
                    if (!report.valid) StatusChip(label = "REJECTED", tone = StatusTone.ERROR)
                }
            }
        }

        if (!report.valid) {
            item {
                ForgeNoticeBox(
                    title = "Package rejected",
                    text = report.errors.joinToString("\n") { "${it.code}: ${it.message}" },
                    tone = StatusTone.ERROR,
                )
            }
        }

        item {
            ForgeCard {
                SectionHeader("identity")
                report.publisher?.let {
                    MetadataLine("author", it.displayName ?: it.keyFingerprint.take(24))
                    MetadataLine("key", it.keyFingerprint.take(32))
                }
                MetadataLine("package sha-256", report.packageSha256)
                MetadataLine("manifest sha-256", report.manifestSha256)
                MetadataLine("runtime", "${descriptor.runtimeType}${descriptor.runtimeVersionRequirement?.let { " $it" } ?: ""}")
            }
        }

        if (classification != null && (classification.known.isNotEmpty() || classification.unknown.isNotEmpty())) {
            item {
                ForgeCard {
                    val provision = classification.known.filter { it.risk != PermissionCatalog.Risk.NORMAL }
                    val runtime = classification.known.filter { it.risk == PermissionCatalog.Risk.NORMAL }
                    PermissionGroup("provision permissions", provision.map { it.id to it.risk.name.lowercase() })
                    if (provision.isNotEmpty() && runtime.isNotEmpty()) ForgeDivider()
                    PermissionGroup("runtime permissions", runtime.map { it.id to it.risk.name.lowercase() })
                    classification.unknown.forEach {
                        Text("$it — UNKNOWN PERMISSION", style = ForgeTypography.monoValue, color = ForgePalette.error)
                    }
                }
            }
        }

        if (dependencySteps.isNotEmpty()) {
            item {
                ForgeCard {
                    SectionHeader("dependencies")
                    ForgeStepList(dependencySteps)
                }
            }
        }

        item {
            ForgeCard {
                SectionHeader("actions")
                val actionTitles = descriptor.capabilities.filter { it.kind == "action" }.map { it.title ?: it.name }
                if (actionTitles.isEmpty()) {
                    MonoText("No actions declared", color = ForgePalette.textMuted)
                } else {
                    actionTitles.forEach { MonoText("• $it", maxLines = 2) }
                }
            }
        }

        if (report.contentWarnings.isNotEmpty()) {
            item {
                ForgeNoticeBox(
                    title = "Warnings",
                    text = report.contentWarnings.joinToString("\n") { "${it.code} — ${it.message}" },
                    tone = StatusTone.WARNING,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.PermissionGroup(title: String, rows: List<Pair<String, String>>) {
    if (rows.isEmpty()) return
    SectionHeader(title)
    rows.forEach { (id, risk) -> MetadataLine(id, risk) }
}

/** Pinned review decision: approve first, then the two ways out. */
@Composable
internal fun ImportReviewActions(
    valid: Boolean,
    onApprove: () -> Unit,
    onChooseAnother: () -> Unit,
    onReject: () -> Unit,
) {
    ForgePrimaryButton(
        text = if (valid) "Approve and install" else "Cannot install",
        enabled = valid,
        onClick = onApprove,
    )
    ForgeButtonRow {
        ForgeSecondaryButton(text = "Choose file", onClick = onChooseAnother, modifier = Modifier.weight(1f))
        ForgeDangerButton(text = "Reject", onClick = onReject, modifier = Modifier.weight(1f))
    }
}
