package org.orynnx.outerview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** App-specific content only; controls and dialogs use native Miuix components. */
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

@Composable
internal fun AppInitial(name: String) {
    Surface(
        modifier = Modifier.size(48.dp),
        shape = RoundedCornerShape(15.dp),
        color = MiuixTheme.colorScheme.secondaryContainer,
        contentColor = MiuixTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = name.trim().let { if (it.isEmpty()) "应" else String(Character.toChars(it.codePointAt(0))) },
                style = MiuixTheme.textStyles.title2,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
