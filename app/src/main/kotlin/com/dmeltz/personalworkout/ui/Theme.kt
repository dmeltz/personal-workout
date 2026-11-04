package com.dmeltz.personalworkout.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

// Brighter than the usual Material greens and reds, so they read well on the dark background.
val Good = Color(0xFF7BD88F)
val Bad = Color(0xFFFF7A7A)
val Brand = Color(0xFF8FB4FF)

/** Always dark, with near-white text, whatever the phone's own theme is. */
private val Colors = darkColorScheme(
    primary = Brand,
    onPrimary = Color(0xFF0B1B3A),
    primaryContainer = Color(0xFF203A6B),
    onPrimaryContainer = Color(0xFFE3EBFF),
    secondary = Color(0xFFB9C6E4),
    background = Color(0xFF121418),
    onBackground = Color(0xFFF2F4F8),
    surface = Color(0xFF121418),
    onSurface = Color(0xFFF2F4F8),
    surfaceVariant = Color(0xFF2A2F38),
    onSurfaceVariant = Color(0xFFD5DAE3),
    surfaceContainer = Color(0xFF1E222A),
    surfaceContainerHigh = Color(0xFF242932),
    surfaceContainerHighest = Color(0xFF2B313B),
    outline = Color(0xFF8E96A4),
    outlineVariant = Color(0xFF3A404B),
    errorContainer = Color(0xFF4A1F22),
    onErrorContainer = Color(0xFFFFDAD8),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}

/** A score from 0 to 1 as a color from red through amber to green. */
fun scoreColor(score: Double): Color = when {
    score >= 0.8 -> Good
    score >= 0.6 -> Color(0xFFD9D46A)
    score >= 0.45 -> Color(0xFFFFB066)
    else -> Bad
}
