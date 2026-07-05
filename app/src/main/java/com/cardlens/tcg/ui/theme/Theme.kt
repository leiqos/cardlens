package com.cardlens.tcg.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Markenfarben: sattes Violett + Tuerkis-Akzent, dunkel-erst.
private val Violet = Color(0xFF7C5CFF)
private val VioletDim = Color(0xFF5B3FD6)
private val Teal = Color(0xFF4ED8C3)
private val Amber = Color(0xFFFFC66E)

private val DarkColors = darkColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = VioletDim,
    onPrimaryContainer = Color(0xFFE9E1FF),
    secondary = Teal,
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF0E4A3F),
    onSecondaryContainer = Color(0xFFB6F5E7),
    tertiary = Amber,
    onTertiary = Color(0xFF3F2B00),
    background = Color(0xFF14121F),
    onBackground = Color(0xFFE7E3F1),
    surface = Color(0xFF1B1826),
    onSurface = Color(0xFFE7E3F1),
    surfaceVariant = Color(0xFF272336),
    onSurfaceVariant = Color(0xFFB6B0C6),
    outline = Color(0xFF4A4560),
    error = Color(0xFFFF7A85)
)

private val LightColors = lightColorScheme(
    primary = VioletDim,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7DEFF),
    onPrimaryContainer = Color(0xFF20005E),
    secondary = Color(0xFF00755F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFBFF2E4),
    onSecondaryContainer = Color(0xFF00201A),
    tertiary = Color(0xFF8A5A00),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1B1725),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1725),
    surfaceVariant = Color(0xFFEAE4F4),
    onSurfaceVariant = Color(0xFF544E66),
    outline = Color(0xFF7B7590)
)

val CardLensShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val CardLensTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(letterSpacing = 0.2.sp)
    )
}

@Composable
fun CardLensTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // eigene Markenfarben als Standard
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = CardLensTypography,
        shapes = CardLensShapes,
        content = content
    )
}
