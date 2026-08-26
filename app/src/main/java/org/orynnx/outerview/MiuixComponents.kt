package org.orynnx.outerview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardDefaults as MiuixCardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox as MiuixCheckbox
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults as MiuixTextFieldDefaults
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.LocalContentColor
import androidx.compose.ui.state.ToggleableState

/** Small compatibility surface for the existing page slots, backed entirely by Miuix widgets. */
data class MiuixCardColorsCompat(
    val containerColor: Color,
    val contentColor: Color,
)

object CardDefaults {
    @Composable
    fun cardColors(
        containerColor: Color = MiuixTheme.colorScheme.surfaceContainer,
        contentColor: Color = MiuixTheme.colorScheme.onSurfaceContainer,
    ) = MiuixCardColorsCompat(containerColor, contentColor)

    @Composable
    fun elevatedCardColors(
        containerColor: Color = MiuixTheme.colorScheme.surfaceContainer,
        contentColor: Color = MiuixTheme.colorScheme.onSurfaceContainer,
    ) = MiuixCardColorsCompat(containerColor, contentColor)
}

@Composable
fun Card(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    colors: MiuixCardColorsCompat = CardDefaults.cardColors(),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    MiuixCard(
        modifier = modifier,
        cornerRadius = cornerRadius,
        insideMargin = PaddingValues(0.dp),
        colors = MiuixCardDefaults.defaultColors(
            color = colors.containerColor,
            contentColor = colors.contentColor,
        ),
        onClick = onClick,
        showIndication = onClick != null,
        content = content,
    )
}

@Composable
fun ElevatedCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    colors: MiuixCardColorsCompat = CardDefaults.elevatedCardColors(),
    content: @Composable ColumnScope.() -> Unit,
) = Card(modifier = modifier, cornerRadius = cornerRadius, colors = colors, content = content)

data class MiuixTextButtonColorsCompat(
    val containerColor: Color,
    val contentColor: Color,
    val disabledContainerColor: Color,
    val disabledContentColor: Color,
)

object ButtonDefaults {
    @Composable
    fun textButtonColors(
        containerColor: Color = MiuixTheme.colorScheme.secondaryVariant,
        contentColor: Color = MiuixTheme.colorScheme.onSecondaryVariant,
        disabledContainerColor: Color = MiuixTheme.colorScheme.disabledSecondaryVariant,
        disabledContentColor: Color = MiuixTheme.colorScheme.disabledOnSecondaryVariant,
    ) = MiuixTextButtonColorsCompat(
        containerColor = containerColor,
        contentColor = contentColor,
        disabledContainerColor = disabledContainerColor,
        disabledContentColor = disabledContentColor,
    )
}

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: MiuixTextButtonColorsCompat = ButtonDefaults.textButtonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    MiuixButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = MiuixButtonDefaults.buttonColors(
            color = colors.containerColor,
            disabledColor = colors.disabledContainerColor,
            contentColor = colors.contentColor,
            disabledContentColor = colors.disabledContentColor,
        ),
        cornerRadius = MiuixButtonDefaults.CornerRadius,
        insideMargin = MiuixButtonDefaults.InsideMargin,
        content = content,
    )
}

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = MiuixButton(
    onClick = onClick,
    modifier = modifier.border(
        BorderStroke(
            width = 1.dp,
            color = if (enabled) MiuixTheme.colorScheme.outline else {
                MiuixTheme.colorScheme.outline.copy(alpha = 0.38f)
            },
        ),
        RoundedCornerShape(MiuixButtonDefaults.CornerRadius),
    ),
    enabled = enabled,
    cornerRadius = MiuixButtonDefaults.CornerRadius,
    colors = MiuixButtonDefaults.buttonColors(
        color = MiuixTheme.colorScheme.secondaryVariant,
        disabledColor = MiuixTheme.colorScheme.disabledSecondaryVariant,
        contentColor = MiuixTheme.colorScheme.onSecondaryVariant,
        disabledContentColor = MiuixTheme.colorScheme.disabledOnSecondaryVariant,
    ),
    content = content,
)

@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = MiuixButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    colors = MiuixButtonDefaults.buttonColors(
        color = MiuixTheme.colorScheme.secondaryContainer,
        disabledColor = MiuixTheme.colorScheme.surfaceVariant,
        contentColor = MiuixTheme.colorScheme.onSecondaryContainer,
        disabledContentColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    ),
    content = content,
)

@Composable
fun ExtendedFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: @Composable () -> Unit,
    text: @Composable () -> Unit,
    expanded: Boolean = true,
    containerColor: Color = MiuixTheme.colorScheme.primary,
    contentColor: Color = MiuixTheme.colorScheme.onPrimary,
) {
    MiuixButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        cornerRadius = 20.dp,
        colors = MiuixButtonDefaults.buttonColors(
            color = containerColor,
            disabledColor = MiuixTheme.colorScheme.disabledPrimaryButton,
            contentColor = contentColor,
            disabledContentColor = MiuixTheme.colorScheme.disabledOnPrimaryButton,
        ),
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    ) {
        icon()
        if (expanded) {
            Spacer(Modifier.width(8.dp))
            text()
        }
    }
}

@Composable
fun Checkbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = MiuixCheckbox(
    state = if (checked) ToggleableState.On else ToggleableState.Off,
    onClick = onCheckedChange?.let { callback -> { callback(!checked) } },
    modifier = modifier,
    enabled = enabled,
)

@Composable
fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    label: String = "",
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
) {
    Column(modifier = modifier) {
        MiuixTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            colors = MiuixTextFieldDefaults.textFieldColors(
                labelColor = if (isError) MiuixTheme.colorScheme.error else {
                    MiuixTheme.colorScheme.onSecondaryContainer
                },
                borderColor = if (isError) MiuixTheme.colorScheme.error else {
                    MiuixTheme.colorScheme.primary
                },
            ),
            label = label,
            singleLine = singleLine,
            maxLines = maxLines,
            minLines = minLines,
        )
        if (supportingText != null) {
            CompositionLocalProvider(
                LocalContentColor provides if (isError) {
                    MiuixTheme.colorScheme.error
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            ) {
                supportingText()
            }
        }
    }
}

@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
) {
    OverlayDialog(
        show = true,
        modifier = modifier,
        onDismissRequest = onDismissRequest,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                icon?.let {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { it() }
                }
                title?.invoke()
                text?.invoke()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        },
    )
}
