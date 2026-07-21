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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** CardLens' own high-contrast collector-tool palette. */
val LensMint = Color(0xFF59E3C2)
val LensCyan = Color(0xFF62D8FF)
val LensViolet = Color(0xFFA78BFA)
val LensGold = Color(0xFFFFC86B)
val LensInk = Color(0xFF071018)

private val DarkColors = darkColorScheme(
    primary = LensMint,
    onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF143B36),
    onPrimaryContainer = Color(0xFFB9F5E6),
    secondary = LensCyan,
    onSecondary = Color(0xFF003544),
    secondaryContainer = Color(0xFF133B49),
    onSecondaryContainer = Color(0xFFC1F0FF),
    tertiary = LensGold,
    onTertiary = Color(0xFF422C00),
    tertiaryContainer = Color(0xFF493719),
    onTertiaryContainer = Color(0xFFFFE2AD),
    background = LensInk,
    onBackground = Color(0xFFEAF2F6),
    surface = Color(0xFF0D1822),
    onSurface = Color(0xFFEAF2F6),
    surfaceVariant = Color(0xFF172633),
    onSurfaceVariant = Color(0xFFA9BAC5),
    outline = Color(0xFF36505F),
    outlineVariant = Color(0xFF223744),
    error = Color(0xFFFF7B86),
    errorContainer = Color(0xFF55232B),
    onErrorContainer = Color(0xFFFFDADD)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B58),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC3F5E7),
    onPrimaryContainer = Color(0xFF002019),
    secondary = Color(0xFF00677F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFBCEBFA),
    onSecondaryContainer = Color(0xFF001F29),
    tertiary = Color(0xFF765514),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDEA2),
    onTertiaryContainer = Color(0xFF271900),
    background = Color(0xFFF4F8FA),
    onBackground = Color(0xFF101C23),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101C23),
    surfaceVariant = Color(0xFFE4EDF1),
    onSurfaceVariant = Color(0xFF465A65),
    outline = Color(0xFF718792),
    outlineVariant = Color(0xFFD0DDE2),
    error = Color(0xFFBA1A1A)
)

val CardLensShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

private val BaseTypography = Typography()
private val CardLensTypography = BaseTypography.copy(
    displaySmall = BaseTypography.displaySmall.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        letterSpacing = (-0.8).sp
    ),
    headlineLarge = BaseTypography.headlineLarge.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.6).sp
    ),
    headlineMedium = BaseTypography.headlineMedium.copy(
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.4).sp
    ),
    headlineSmall = BaseTypography.headlineSmall.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp
    ),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = BaseTypography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = BaseTypography.labelLarge.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.15.sp
    ),
    labelMedium = BaseTypography.labelMedium.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.35.sp
    )
)

@Composable
fun CardLensTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
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
