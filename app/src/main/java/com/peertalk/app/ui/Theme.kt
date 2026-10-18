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
    val Blue = Color(0xFF1F6BFF)
    val Cyan = Color(0xFF29C6F5)
    val Green = Color(0xFF22C55E)
    val Amber = Color(0xFFF59E0B)
    val Red = Color(0xFFEF4444)
    val CallTop = Color(0xFF0B1B3F)
    val CallBottom = Color(0xFF030712)
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F6BFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF00205C),
    secondary = Color(0xFF0891B2),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDF1FA),
    onSecondaryContainer = Color(0xFF00323F),
    background = Color(0xFFF4F7FC),
    onBackground = Color(0xFF14151F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14151F),
    surfaceVariant = Color(0xFFE9EEF8),
    onSurfaceVariant = Color(0xFF4A5568),
    surfaceContainer = Color(0xFFEDF1F9),
    surfaceContainerHigh = Color(0xFFE6ECF7),
    outline = Color(0xFFAEB9CF),
    outlineVariant = Color(0xFFDCE3F0),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFDE2E2),
    onErrorContainer = Color(0xFF7A1010),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6AA6FF),
    onPrimary = Color(0xFF002A66),
    primaryContainer = Color(0xFF12388A),
    onPrimaryContainer = Color(0xFFDCE8FF),
    secondary = Color(0xFF4DD4F7),
    onSecondary = Color(0xFF00323F),
    secondaryContainer = Color(0xFF0B4A5C),
    onSecondaryContainer = Color(0xFFCDF1FA),
    background = Color(0xFF050B18),
    onBackground = Color(0xFFE6E6F2),
    surface = Color(0xFF0C1526),
    onSurface = Color(0xFFE6E6F2),
    surfaceVariant = Color(0xFF16223A),
    onSurfaceVariant = Color(0xFF9FB0CC),
    surfaceContainer = Color(0xFF111C31),
    surfaceContainerHigh = Color(0xFF18253E),
    outline = Color(0xFF55698F),
    outlineVariant = Color(0xFF1F2E4D),
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
