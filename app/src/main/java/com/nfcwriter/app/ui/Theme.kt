package com.nfcwriter.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Dark = darkColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF0A3F73),
    onPrimaryContainer = Color(0xFFD6E9FF),
    secondary = Color(0xFF64D2FF),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF174B5E),
    onSecondaryContainer = Color(0xFFD5F4FF),
    background = Color.Black,
    onBackground = Color(0xFFF2F2F7),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFF2F2F7),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFFAEAEB2),
    outline = Color(0xFF48484A),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val Light = lightColorScheme(
    primary = Color(0xFF007AFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8EAFF),
    onPrimaryContainer = Color(0xFF002C59),
    secondary = Color(0xFF5AC8FA),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8F3FF),
    onSecondaryContainer = Color(0xFF003548),
    background = Color(0xFFF2F2F7),
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFE5E5EA),
    onSurfaceVariant = Color(0xFF636366),
    outline = Color(0xFFC6C6C8),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun NfcWriterTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = if (mode == ThemeMode.DARK || mode == ThemeMode.SYSTEM && isSystemInDarkTheme()) Dark else Light,
    typography = Typography(),
    shapes = AppShapes,
    content = content,
)
