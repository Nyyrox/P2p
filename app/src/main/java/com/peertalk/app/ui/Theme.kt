package com.peertalk.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

object Brand {
    val Indigo = Color(0xFF4F46E5)
    val Teal = Color(0xFF0D9488)
    val Green = Color(0xFF22C55E)
    val Amber = Color(0xFFF59E0B)
    val Red = Color(0xFFEF4444)
    val CallTop = Color(0xFF1A1B3A)
    val CallBottom = Color(0xFF0B0C16)
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E3FF),
    onPrimaryContainer = Color(0xFF1B1672),
    secondary = Color(0xFF0D9488),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCF5F0),
    onSecondaryContainer = Color(0xFF04332F),
    background = Color(0xFFF6F6FB),
    onBackground = Color(0xFF14151F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14151F),
    surfaceVariant = Color(0xFFECECF6),
    onSurfaceVariant = Color(0xFF50525F),
    surfaceContainer = Color(0xFFF0F0F8),
    surfaceContainerHigh = Color(0xFFEAEAF4),
    outline = Color(0xFFB9BACD),
    outlineVariant = Color(0xFFE1E2EE),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFDE2E2),
    onErrorContainer = Color(0xFF7A1010),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8B8DFF),
    onPrimary = Color(0xFF12114A),
    primaryContainer = Color(0xFF2D2B7A),
    onPrimaryContainer = Color(0xFFE4E3FF),
    secondary = Color(0xFF2DD4BF),
    onSecondary = Color(0xFF04332F),
    secondaryContainer = Color(0xFF0F4F49),
    onSecondaryContainer = Color(0xFFCCF5F0),
    background = Color(0xFF0D0E16),
    onBackground = Color(0xFFE6E6F2),
    surface = Color(0xFF161725),
    onSurface = Color(0xFFE6E6F2),
    surfaceVariant = Color(0xFF232537),
    onSurfaceVariant = Color(0xFFA9ABC0),
    surfaceContainer = Color(0xFF1B1C2C),
    surfaceContainerHigh = Color(0xFF222438),
    outline = Color(0xFF5A5C73),
    outlineVariant = Color(0xFF2C2E44),
    error = Color(0xFFF87171),
    onError = Color(0xFF4A0707),
    errorContainer = Color(0xFF5B1414),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val PeerTalkTypography: Typography = Typography().let { d ->
    d.copy(
        headlineLarge = d.headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = d.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = d.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = d.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = d.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = d.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun PeerTalkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = PeerTalkTypography,
        content = content,
    )
}
