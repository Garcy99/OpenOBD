package com.openobd.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.openobd.data.ThemeMode

val Cyan = Color(0xFF22D3EE)
val CyanDark = Color(0xFF0E7490)
val Orange = Color(0xFFF97316)
val Good = Color(0xFF22C55E)
val Warn = Color(0xFFF59E0B)
val Bad = Color(0xFFEF4444)

private val Dark = darkColorScheme(
    primary = Cyan, onPrimary = Color(0xFF00222A), primaryContainer = Color(0xFF0E3A47), onPrimaryContainer = Color(0xFFBDF4FF),
    secondary = Orange, onSecondary = Color(0xFF2B1100), secondaryContainer = Color(0xFF4A2205), onSecondaryContainer = Color(0xFFFFDCC6),
    background = Color(0xFF0B1220), onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF0F172A), onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1E293B), onSurfaceVariant = Color(0xFF94A3B8),
    surfaceContainer = Color(0xFF131C2F), surfaceContainerHigh = Color(0xFF1A2438), surfaceContainerLow = Color(0xFF111A2B),
    surfaceContainerHighest = Color(0xFF223047), surfaceContainerLowest = Color(0xFF080E1A),
    outline = Color(0xFF334155), outlineVariant = Color(0xFF1F2A3D), error = Bad,
)

private val Light = lightColorScheme(
    primary = CyanDark, onPrimary = Color.White, primaryContainer = Color(0xFFCCF4FC), onPrimaryContainer = Color(0xFF00363F),
    secondary = Color(0xFFC2410C), onSecondary = Color.White, secondaryContainer = Color(0xFFFFE1CC), onSecondaryContainer = Color(0xFF3A1400),
    background = Color(0xFFF7F9FC), onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE8EDF4), onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1), error = Color(0xFFDC2626),
)

/** Pure black for OLED screens: saves battery at night and looks like part of the dashboard. */
private val Amoled = Dark.copy(
    background = Color.Black, surface = Color.Black,
    surfaceContainer = Color(0xFF0B0B0B), surfaceContainerLow = Color(0xFF060606), surfaceContainerLowest = Color.Black,
    surfaceContainerHigh = Color(0xFF121212), surfaceContainerHighest = Color(0xFF1A1A1A), surfaceVariant = Color(0xFF161616),
)

@Composable
fun OpenObdTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val scheme = when (mode) {
        ThemeMode.SYSTEM -> if (isSystemInDarkTheme()) Dark else Light
        ThemeMode.DARK -> Dark
        ThemeMode.LIGHT -> Light
        ThemeMode.AMOLED -> Amoled
    }
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
