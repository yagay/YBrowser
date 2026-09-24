package com.yagay.ybrowser.ai.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun AIHubTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val baseColors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    val colors = if (darkTheme) {
        baseColors.copy(
            onBackground = Color(0xFFF5F5F7),
            onSurface = Color(0xFFF5F5F7),
            onSurfaceVariant = Color(0xFFD6D6DE),
            outline = Color(0xFFA8A8B2),
            outlineVariant = Color(0xFF5E5E68),
        )
    } else {
        baseColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
