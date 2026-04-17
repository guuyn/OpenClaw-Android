package ai.openclaw.android.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Sci-Fi 暗色主题色板 — 深海蓝 + 青色霓虹
 */
private val SciFiDarkColorScheme = darkColorScheme(
    primary = SciFiPrimary,
    onPrimary = SciFiOnPrimary,
    secondary = SciFiSecondary,
    tertiary = SciFiTertiary,
    error = SciFiError,
    background = SciFiBackground,
    surface = SciFiSurface,
    surfaceVariant = SciFiSurfaceVariant,
    outline = SciFiOutline,
    outlineVariant = SciFiOutlineVariant,
    onBackground = SciFiOnBackground,
    onSurface = SciFiOnSurface,
    onSurfaceVariant = SciFiOnSurfaceVariant
)

/**
 * 亮色模式色板（可选，默认关闭）
 */
private val SciFiLightColorScheme = lightColorScheme(
    primary = SciFiLightPrimary,
    onPrimary = SciFiLightOnPrimary,
    secondary = SciFiSecondary,
    tertiary = SciFiTertiary,
    error = SciFiError,
    background = SciFiLightBackground,
    surface = SciFiLightSurface,
    surfaceVariant = SciFiSurfaceVariant,
    outline = SciFiOutline,
    outlineVariant = SciFiOutlineVariant,
    onBackground = SciFiLightOnSurface,
    onSurface = SciFiLightOnSurface,
    onSurfaceVariant = SciFiOnSurfaceVariant
)

/**
 * 兼容旧版 Material 动态配色（可切换回紫色主题）
 */
private val LegacyDarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LegacyLightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

@Composable
fun OpenClawTheme(
    darkTheme: Boolean = true,  // 默认深色
    dynamicColor: Boolean = false,  // 默认关闭动态配色
    sciFiTheme: Boolean = true,  // 是否使用科幻主题
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        sciFiTheme -> {
            if (darkTheme) SciFiDarkColorScheme else SciFiLightColorScheme
        }
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> LegacyDarkColorScheme
        else -> LegacyLightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // 状态栏使用深色背景
            window.statusBarColor = if (sciFiTheme) SciFiBackground.toArgb() else colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
