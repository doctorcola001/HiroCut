package com.hirocut.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.hirocut.app.data.settings.ColorTheme

private val Colors = darkColorScheme(
    primary = Color(0xFFA0E8CE), onPrimary = Color(0xFF07382A),
    primaryContainer = Color(0xFF203D33), onPrimaryContainer = Color(0xFFB7F5DC),
    secondary = Color(0xFFF6C980), onSecondary = Color(0xFF422E0A),
    secondaryContainer = Color(0xFF273E36), onSecondaryContainer = Color(0xFFB7F5DC),
    background = Color(0xFF0D1214), onBackground = Color(0xFFE4EAE7),
    surface = Color(0xFF131B1D), onSurface = Color(0xFFE4EAE7),
    surfaceVariant = Color(0xFF263133), onSurfaceVariant = Color(0xFFADBCB7),
    outline = Color(0xFF566660),
)

@Composable
fun HiroCutTheme(theme: ColorTheme = ColorTheme.Forest, content: @Composable () -> Unit) {
    val colors = when (theme) {
        ColorTheme.Forest -> Colors
        ColorTheme.Ocean -> Colors.copy(primary = Color(0xFF9CCAFF), onPrimary = Color(0xFF082B52),
            primaryContainer = Color(0xFF17354D), onPrimaryContainer = Color(0xFFD3E7FF),
            secondaryContainer = Color(0xFF223749), onSecondaryContainer = Color(0xFFD3E7FF),
            background = Color(0xFF0C121A), surface = Color(0xFF121B26), surfaceVariant = Color(0xFF263544))
        ColorTheme.Amber -> Colors.copy(primary = Color(0xFFFFCA85), onPrimary = Color(0xFF432B0B),
            primaryContainer = Color(0xFF49351C), onPrimaryContainer = Color(0xFFFFE1B3),
            secondaryContainer = Color(0xFF403321), onSecondaryContainer = Color(0xFFFFE1B3),
            background = Color(0xFF16110C), surface = Color(0xFF211B13), surfaceVariant = Color(0xFF3B3226))
    }
    MaterialTheme(colorScheme = colors, content = content)
}
