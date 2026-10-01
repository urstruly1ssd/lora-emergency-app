package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val EmergencyColorScheme = darkColorScheme(
    primary = EmergencyRed,
    onPrimary = TextWhite,
    primaryContainer = DarkSirenRed,
    onPrimaryContainer = TextWhite,
    secondary = BrightWarningRed,
    onSecondary = PitchBlack,
    tertiary = TextDim,
    onTertiary = TextWhite,
    background = PitchBlack,
    onBackground = TextWhite,
    surface = SurfaceDark,
    onSurface = TextWhite,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextWhite,
    error = BrightWarningRed,
    onError = PitchBlack,
    outline = TextDim
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Force dark theme for emergency app environment
    dynamicColor: Boolean = false, // Force consistent branding and maximum contrast
    content: @Composable () -> Unit,
) {
    // We use EmergencyColorScheme always to give a tactical emergency look
    MaterialTheme(
        colorScheme = EmergencyColorScheme,
        typography = Typography,
        content = content
    )
}
