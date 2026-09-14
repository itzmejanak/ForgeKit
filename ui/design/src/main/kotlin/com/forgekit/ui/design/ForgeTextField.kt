package com.forgekit.ui.design

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

/** The single text-field color contract (app screens, prompt dock, plugin UI renderer). */
@Composable
public fun forgeTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = ForgePalette.textPrimary,
    unfocusedTextColor = ForgePalette.textPrimary,
    focusedBorderColor = ForgePalette.primary,
    unfocusedBorderColor = ForgePalette.outline,
    focusedContainerColor = ForgePalette.surfaceInput,
    unfocusedContainerColor = ForgePalette.surfaceInput,
    cursorColor = ForgePalette.primary,
    focusedLabelColor = ForgePalette.primary,
    unfocusedLabelColor = ForgePalette.textSecondary,
    focusedPlaceholderColor = ForgePalette.textMuted,
    unfocusedPlaceholderColor = ForgePalette.textMuted,
    focusedLeadingIconColor = ForgePalette.textSecondary,
    unfocusedLeadingIconColor = ForgePalette.textMuted,
)

/**
 * The one text field. [mono] uses the identifier/value face (paths, ids, secrets);
 * prose inputs such as search pass `mono = false`. [password] masks input and asks
 * the keyboard for a password layout.
 */
@Composable
public fun ForgeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    password: Boolean = false,
    mono: Boolean = true,
    leadingIcon: ImageVector? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val textStyle = (if (mono) ForgeTypography.monoValue else ForgeTypography.body).copy(color = ForgePalette.textPrimary)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it, style = textStyle) } },
        leadingIcon = leadingIcon?.let { { Icon(it, contentDescription = null) } },
        singleLine = singleLine,
        textStyle = textStyle,
        shape = ForgeShapes.control,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType),
        colors = forgeTextFieldColors(),
    )
}
