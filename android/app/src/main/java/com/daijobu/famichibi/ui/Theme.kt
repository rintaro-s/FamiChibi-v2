package com.daijobu.famichibi.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daijobu.famichibi.Graph

val Peach = Color(0xFFE8745A)
val PeachSoft = Color(0xFFFFE3D8)
val Cream = Color(0xFFFFF8F2)
val AiBlue = Color(0xFF3FA9E8)
val AiSoft = Color(0xFFE2F4FF)
val Urgent = Color(0xFFFF5A5F)
val Mint = Color(0xFF2EC4B6)

private val Light = lightColorScheme(
    primary = Peach, onPrimary = Color.White, primaryContainer = PeachSoft, onPrimaryContainer = Color(0xFF5A2012),
    secondary = AiBlue, onSecondary = Color.White, secondaryContainer = AiSoft, onSecondaryContainer = Color(0xFF0B3A57),
    tertiary = Mint, background = Cream, onBackground = Color(0xFF2B2B33), surface = Color.White, onSurface = Color(0xFF2B2B33),
    surfaceVariant = Color(0xFFF6ECE6), onSurfaceVariant = Color(0xFF6B6B78), outline = Color(0xFFE5D6CE), error = Urgent,
    surfaceContainer = Color(0xFFFFF3EC), surfaceContainerHigh = Color(0xFFFCEBE2), surfaceContainerLow = Color(0xFFFFFBF8),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFF9A80), onPrimary = Color(0xFF4A1508), primaryContainer = Color(0xFF6B2E1E), onPrimaryContainer = Color(0xFFFFDBD0),
    secondary = Color(0xFF8FD3FF), onSecondary = Color(0xFF00344F), secondaryContainer = Color(0xFF0E4A6B),
    tertiary = Mint, background = Color(0xFF1B1A1F), onBackground = Color(0xFFEDE7E3), surface = Color(0xFF242329),
    onSurface = Color(0xFFEDE7E3), surfaceVariant = Color(0xFF34313A), onSurfaceVariant = Color(0xFFBDB6BF), outline = Color(0xFF4A4550),
    error = Color(0xFFFF8A8E), surfaceContainer = Color(0xFF2A2830), surfaceContainerHigh = Color(0xFF322F38),
)

/** Global text scale (grandparents mode makes everything bigger). */
val LocalScale = staticCompositionLocalOf { 1f }

@Composable
fun FamiTheme(content: @Composable () -> Unit) {
    val settings by Graph.prefs.settings.collectAsStateWithLifecycle()
    val k = (if (settings.bigMode) 1.3f else 1f) * settings.fontScale.coerceIn(0.85f, 1.6f)
    val base = Typography()
    fun TextStyle.s() = copy(fontSize = (fontSize.value * k).sp, lineHeight = if (lineHeight == TextUnit.Unspecified) lineHeight else (lineHeight.value * k).sp)
    val typo = Typography(
        displaySmall = base.displaySmall.s(), headlineLarge = base.headlineLarge.s().copy(fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.s().copy(fontWeight = FontWeight.Bold), headlineSmall = base.headlineSmall.s().copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.s().copy(fontWeight = FontWeight.Bold), titleMedium = base.titleMedium.s().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.s(), bodyLarge = base.bodyLarge.s(), bodyMedium = base.bodyMedium.s(), bodySmall = base.bodySmall.s(),
        labelLarge = base.labelLarge.s(), labelMedium = base.labelMedium.s(), labelSmall = base.labelSmall.s(),
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalScale provides k) {
        MaterialTheme(
            colorScheme = if (isSystemInDarkTheme()) Dark else Light,
            typography = typo,
            shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp)),
            content = content,
        )
    }
}
