package com.antenna.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun AntennaTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    // Pure black or white behind everything, like YouTube, so the top and bottom bars blend
    // into the page. Menus, the search box and the like keep their slightly raised greys.
    val base = if (dark) Color.Black else Color.White
    val colors = scheme.copy(background = base, surface = base)
    MaterialTheme(colorScheme = colors) {
        // Text outside a Surface or Scaffold (the player screen) would otherwise default to
        // black, which is unreadable in dark mode.
        CompositionLocalProvider(LocalContentColor provides colors.onBackground, content = content)
    }
}
