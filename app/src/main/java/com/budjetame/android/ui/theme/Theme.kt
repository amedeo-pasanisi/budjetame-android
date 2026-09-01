package com.budjetame.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The web app's palette (Tailwind indigo-600 / slate scale), mirrored so the
// Android app reads like the same product. No dynamic color: the brand is
// the brand.
private val Indigo600 = Color(0xFF4F46E5)
private val Indigo100 = Color(0xFFE0E7FF)
private val Indigo900 = Color(0xFF312E81)
private val Slate50 = Color(0xFFF8FAFC)
private val Slate200 = Color(0xFFE2E8F0)
private val Slate300 = Color(0xFFCBD5E1)
private val Slate500 = Color(0xFF64748B)
private val Slate600 = Color(0xFF475569)
private val Slate900 = Color(0xFF0F172A)
private val Red600 = Color(0xFFDC2626)

private val LightColors = lightColorScheme(
    primary = Indigo600,
    onPrimary = Color.White,
    primaryContainer = Indigo100,
    onPrimaryContainer = Indigo900,
    secondary = Slate500,
    onSecondary = Color.White,
    background = Slate50,
    onBackground = Slate900,
    surface = Color.White,
    onSurface = Slate900,
    surfaceVariant = Slate200,
    onSurfaceVariant = Slate600,
    outline = Slate300,
    error = Red600,
    onError = Color.White,
)

@Composable
fun BudjetameTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content,
    )
}
