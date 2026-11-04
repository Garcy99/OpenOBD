package com.openobd.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openobd.R
import com.openobd.data.ThemeMode

// Brand colours: an electric cyan-to-violet signature over deep midnight surfaces.
val Cyan = Color(0xFF4FD8FF)
val Blue = Color(0xFF3B82F6)
val Violet = Color(0xFF8B5CF6)
val CyanDark = Color(0xFF0A7FC0)
val Orange = Color(0xFFFF8A3D)
val Good = Color(0xFF34D399)
val Warn = Color(0xFFFBBF24)
val Bad = Color(0xFFF87171)

/** Inter (SIL Open Font License), bundled so the app looks the same on every phone. */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** Extra colours the Material scheme has no slot for. */
@Immutable
data class Extras(
    val brand: Brush,
    val brandSoft: Brush,
    val hairline: Color,
    val glow: Color,
    val isDark: Boolean,
)

val LocalExtras = staticCompositionLocalOf {
    Extras(Brush.linearGradient(listOf(Cyan, Violet)), Brush.linearGradient(listOf(Cyan, Violet)), Color(0x14FFFFFF), Cyan, true)
}

private val Dark = darkColorScheme(
    primary = Cyan, onPrimary = Color(0xFF00202C), primaryContainer = Color(0xFF0B3446), onPrimaryContainer = Color(0xFFC4F1FF),
    secondary = Violet, onSecondary = Color.White, secondaryContainer = Color(0xFF103446), onSecondaryContainer = Color(0xFFC4F1FF),
    tertiary = Good, onTertiary = Color(0xFF00281A),
    background = Color(0xFF070A12), onBackground = Color(0xFFE8EDF5),
    surface = Color(0xFF070A12), onSurface = Color(0xFFE8EDF5),
    surfaceVariant = Color(0xFF1A2232), onSurfaceVariant = Color(0xFF8E9BB3),
    surfaceContainerLowest = Color(0xFF05070D), surfaceContainerLow = Color(0xFF0C111B), surfaceContainer = Color(0xFF101722),
    surfaceContainerHigh = Color(0xFF151E2C), surfaceContainerHighest = Color(0xFF1C2638),
    outline = Color(0xFF2C3850), outlineVariant = Color(0xFF1A2232), error = Bad, onError = Color(0xFF2D0505),
    inverseSurface = Color(0xFFE8EDF5), inverseOnSurface = Color(0xFF0C111B),
)

private val Light = lightColorScheme(
    primary = CyanDark, onPrimary = Color.White, primaryContainer = Color(0xFFD5F1FF), onPrimaryContainer = Color(0xFF00344C),
    secondary = Color(0xFF6D4AE0), onSecondary = Color.White, secondaryContainer = Color(0xFFD5F1FF), onSecondaryContainer = Color(0xFF00344C),
    tertiary = Color(0xFF0E9F6E), onTertiary = Color.White,
    background = Color(0xFFF3F5F9), onBackground = Color(0xFF0B1220),
    surface = Color(0xFFF3F5F9), onSurface = Color(0xFF0B1220),
    surfaceVariant = Color(0xFFE6EAF1), onSurfaceVariant = Color(0xFF5B6678),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFAFBFD), surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0F3F8), surfaceContainerHighest = Color(0xFFE7EBF2),
    outline = Color(0xFFC9D1DE), outlineVariant = Color(0xFFE3E8EF), error = Color(0xFFDC2626),
)

/** Pure black for OLED screens: saves battery at night and blends into the car's dashboard. */
private val Amoled = Dark.copy(
    background = Color.Black, surface = Color.Black,
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF07090D), surfaceContainer = Color(0xFF0B0E14),
    surfaceContainerHigh = Color(0xFF11151D), surfaceContainerHighest = Color(0xFF181D27), surfaceVariant = Color(0xFF141922),
)

private fun TextStyle.inter(size: Int, weight: FontWeight, line: Int, spacing: Double = 0.0) =
    copy(fontFamily = Inter, fontSize = size.sp, fontWeight = weight, lineHeight = line.sp, letterSpacing = spacing.sp)

private val AppTypography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.inter(54, FontWeight.Bold, 60, -1.5),
        displayMedium = t.displayMedium.inter(44, FontWeight.Bold, 50, -1.2),
        displaySmall = t.displaySmall.inter(36, FontWeight.Bold, 42, -0.9),
        headlineLarge = t.headlineLarge.inter(30, FontWeight.Bold, 36, -0.7),
        headlineMedium = t.headlineMedium.inter(26, FontWeight.Bold, 32, -0.5),
        headlineSmall = t.headlineSmall.inter(22, FontWeight.SemiBold, 28, -0.3),
        titleLarge = t.titleLarge.inter(20, FontWeight.SemiBold, 26, -0.2),
        titleMedium = t.titleMedium.inter(16, FontWeight.SemiBold, 22, -0.1),
        titleSmall = t.titleSmall.inter(14, FontWeight.SemiBold, 20),
        bodyLarge = t.bodyLarge.inter(16, FontWeight.Normal, 24),
        bodyMedium = t.bodyMedium.inter(14, FontWeight.Normal, 20),
        bodySmall = t.bodySmall.inter(12, FontWeight.Normal, 17),
        labelLarge = t.labelLarge.inter(14, FontWeight.SemiBold, 20),
        labelMedium = t.labelMedium.inter(12, FontWeight.Medium, 16, 0.1),
        labelSmall = t.labelSmall.inter(11, FontWeight.Medium, 14, 0.4),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(28.dp),
)

private fun extrasFor(scheme: ColorScheme, dark: Boolean) = Extras(
    brand = Brush.linearGradient(listOf(Cyan, Blue, Violet)),
    brandSoft = Brush.linearGradient(
        if (dark) listOf(Color(0xFF0E2A3C), Color(0xFF14183A), Color(0xFF1D1440)) else listOf(Color(0xFFDDF3FF), Color(0xFFE5E9FF), Color(0xFFEEE6FF)),
    ),
    hairline = if (dark) Color(0x17FFFFFF) else Color(0x14000000),
    glow = scheme.primary,
    isDark = dark,
)

@Composable
fun OpenObdTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val (scheme, dark) = when (mode) {
        ThemeMode.SYSTEM -> if (systemDark) Dark to true else Light to false
        ThemeMode.DARK -> Dark to true
        ThemeMode.LIGHT -> Light to false
        ThemeMode.AMOLED -> Amoled to true
    }
    CompositionLocalProvider(LocalExtras provides extrasFor(scheme, dark)) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
