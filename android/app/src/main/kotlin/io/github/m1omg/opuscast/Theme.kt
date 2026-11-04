package io.github.m1omg.opuscast

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

// from the colours of the OpusCast icon (violet to blue), for phones without wallpaper colours (before Android 12)
private val Light = lightColorScheme(primary = Color(0xFF5B3FD9), secondary = Color(0xFF2F7BFF), tertiary = Color(0xFF9A5B00))
private val Dark = darkColorScheme(primary = Color(0xFFC8BCFF), secondary = Color(0xFFAFC6FF), tertiary = Color(0xFFFFB960))

@Composable
fun OpusCastTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (Build.VERSION.SDK_INT >= 31) {
        val ctx = LocalContext.current
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else if (dark) Dark else Light
    MaterialTheme(colorScheme = colors, content = content)
}
