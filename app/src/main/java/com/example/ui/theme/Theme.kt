package com.example.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = PureWhite,
    onPrimary = OLEDBlack,
    secondary = TextSecondary,
    onSecondary = PureWhite,
    background = OLEDBlack,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceElevated,
    onSurfaceVariant = TextPrimary,
    outline = BorderColor,
    error = AccentRed
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Force dark theme only
    dynamicColor: Boolean = false, // Disable dynamic colors to keep layout minimalist
    content: @Composable () -> Unit,
) {
    // Force our beautiful custom dark color scheme
    val colorScheme = DarkColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
