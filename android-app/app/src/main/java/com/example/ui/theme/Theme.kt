package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(primary = Purple80, secondary = PurpleGrey80, tertiary = Pink80)

private val LightColorScheme =
  lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF4F46E5), // Indigo/Violet
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFEEF2FF),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF3730A3),
    secondary = androidx.compose.ui.graphics.Color(0xFF0D9488), // Crisp Teal
    onSecondary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFFF0FDFA),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF115E59),
    background = androidx.compose.ui.graphics.Color(0xFFF8FAFC), // Very elegant soft slate/white light background
    onBackground = androidx.compose.ui.graphics.Color(0xFF0F172A),
    surface = androidx.compose.ui.graphics.Color.White,
    onSurface = androidx.compose.ui.graphics.Color(0xFF0F172A),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFF1F5F9),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF475569),
    outline = androidx.compose.ui.graphics.Color(0xFFCBD5E1)
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = false, // Forced to false by default for light theme
  dynamicColor: Boolean = false, // Forced to false by default to use our custom polished color scheme
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
