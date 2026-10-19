package com.peertalk.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/** Status colours only (online dot, accept / decline, warnings). Everything else is black and white. */
object Brand {
    val Green = Color(0xFF3FAE6A)
    val Amber = Color(0xFFE0A100)
    val Red = Color(0xFFD9534F)
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF111111),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDEDED),
    onPrimaryContainer = Color(0xFF111111),
    secondary = Color(0xFF404040),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E8E8),
    onSecondaryContainer = Color(0xFF111111),
    background = Color.White,
    onBackground = Color(0xFF111111),
    surface = Color.White,
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFF2F2F2),
    onSurfaceVariant = Color(0xFF5C5C5C),
    surfaceContainer = Color(0xFFF5F5F5),
    surfaceContainerHigh = Color(0xFFEEEEEE),
    outline = Color(0xFFBDBDBD),
    outlineVariant = Color(0xFFE3E3E3),
    error = Color(0xFFC62828),
    onError = Color.White,
    errorContainer = Color(0xFFF5E1E1),
    onErrorContainer = Color(0xFF5E1212),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF5F5F5),
    onPrimary = Color(0xFF0A0A0A),
    primaryContainer = Color(0xFF2A2A2A),
    onPrimaryContainer = Color(0xFFF5F5F5),
    secondary = Color(0xFFBDBDBD),
    onSecondary = Color(0xFF0A0A0A),
    secondaryContainer = Color(0xFF262626),
    onSecondaryContainer = Color(0xFFF5F5F5),
    background = Color.Black,
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF0B0B0B),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFFA6A6A6),
    surfaceContainer = Color(0xFF121212),
    surfaceContainerHigh = Color(0xFF1C1C1C),
    outline = Color(0xFF5A5A5A),
    outlineVariant = Color(0xFF262626),
    error = Color(0xFFEF5350),
    onError = Color(0xFF2B0000),
    errorContainer = Color(0xFF3B1414),
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
