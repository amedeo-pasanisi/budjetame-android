package com.budjetame.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The web app's palette (Tailwind indigo / slate / white scale), mirrored
// so the Android app reads like the same product. Every Material 3 role is
// set explicitly — the M3 baseline colors (pink secondaryContainer, purple
// surfaceTint, lavender outlineVariant, …) must never leak onto surfaces.
// No dynamic color: the brand is the brand.
private val Indigo600 = Color(0xFF4F46E5) // web bg-indigo-600 (primary, active pills)
private val Indigo100 = Color(0xFFE0E7FF) // web bg-indigo-100 (primary container)
private val Indigo900 = Color(0xFF312E81) // web text-indigo-900 (on primary container)
private val Slate50 = Color(0xFFF8FAFC) // web bg-slate-50 (page background)
private val Slate200 = Color(0xFFE2E8F0) // web border-slate-200 (dividers, outlines)
private val Slate300 = Color(0xFFCBD5E1) // web border-slate-300 (outlines)
private val Slate500 = Color(0xFF64748B) // web text-slate-500 (secondary)
private val Slate600 = Color(0xFF475569) // web text-slate-600 (secondary text)
private val Slate900 = Color(0xFF0F172A) // web text-slate-900 (content)
private val Red600 = Color(0xFFDC2626) // web text-red-600 (error)
private val Red100 = Color(0xFFFEE2E2) // web bg-red-100 (error container)
private val Red800 = Color(0xFF991B1B) // web text-red-800 (on error container)

private val LightColors = lightColorScheme(
    // Brand accents (web: bg-indigo-600 with text-white).
    primary = Indigo600,
    onPrimary = Color.White,
    primaryContainer = Indigo100,
    onPrimaryContainer = Indigo900,
    // Active containers (selected tab pill, segmented toggles) mirror the
    // web's solid indigo-600 active treatment instead of M3's pink
    // secondaryContainer.
    secondary = Slate500,
    onSecondary = Color.White,
    secondaryContainer = Indigo600,
    onSecondaryContainer = Color.White,
    tertiary = Indigo600,
    onTertiary = Color.White,
    tertiaryContainer = Indigo100,
    onTertiaryContainer = Indigo900,
    // Errors (web: text-red-600 on red-100 containers).
    error = Red600,
    onError = Color.White,
    errorContainer = Red100,
    onErrorContainer = Red800,
    // Surfaces (web: bg-slate-50 page with white cards, modals, menus and
    // nav bar, separated by slate-200 borders).
    background = Slate50,
    onBackground = Slate900,
    surface = Color.White,
    onSurface = Slate900,
    surfaceVariant = Slate200,
    onSurfaceVariant = Slate600,
    surfaceBright = Color.White,
    surfaceDim = Slate200,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,
    surfaceTint = Color.White, // flat web look: no tonal-elevation tint
    outline = Slate300,
    outlineVariant = Slate200,
    // Inverse surfaces (snackbars, tooltips): dark slate, not M3's purple.
    inverseSurface = Slate900,
    inverseOnSurface = Slate50,
    inversePrimary = Indigo100,
    scrim = Color.Black,
)

@Composable
fun BudjetameTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content,
    )
}
