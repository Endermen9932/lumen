package app.lumen.photos.ui.theme

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.lumen.photos.data.settings.AppSettings
import app.lumen.photos.data.settings.ThemeMode

/** Seed colours offered when dynamic colour is switched off. */
val SeedColors = listOf(
    0xFF6750A4, // Violett
    0xFF0B57D0, // Blau
    0xFF006A6A, // Petrol
    0xFF386A20, // Grün
    0xFF8C5000, // Bernstein
    0xFFB3261E, // Rot
    0xFF984061, // Rosa
    0xFF4A4458, // Schiefer
)

private fun schemeFromSeed(seed: Long, dark: Boolean): ColorScheme {
    val c = Color(seed)
    // A small tonal approximation: good enough for a fallback when dynamic colour is off.
    fun tone(color: Color, amount: Float) = Color(
        red = (color.red + (1 - color.red) * amount).coerceIn(0f, 1f),
        green = (color.green + (1 - color.green) * amount).coerceIn(0f, 1f),
        blue = (color.blue + (1 - color.blue) * amount).coerceIn(0f, 1f),
    )
    fun shade(color: Color, amount: Float) = Color(
        red = color.red * (1 - amount), green = color.green * (1 - amount), blue = color.blue * (1 - amount)
    )
    return if (dark) {
        darkColorScheme(
            primary = tone(c, 0.55f),
            onPrimary = shade(c, 0.7f),
            primaryContainer = shade(c, 0.35f),
            onPrimaryContainer = tone(c, 0.85f),
            secondary = tone(c, 0.45f),
            secondaryContainer = shade(c, 0.5f),
            onSecondaryContainer = tone(c, 0.85f),
            tertiary = tone(Color(0xFF7D5260), 0.5f),
            tertiaryContainer = shade(Color(0xFF7D5260), 0.3f),
            surface = Color(0xFF141218),
            surfaceContainerLowest = Color(0xFF0F0D13),
            surfaceContainerLow = Color(0xFF1D1B20),
            surfaceContainer = Color(0xFF211F26),
            surfaceContainerHigh = Color(0xFF2B2930),
            surfaceContainerHighest = Color(0xFF36343B),
            background = Color(0xFF141218),
        )
    } else {
        lightColorScheme(
            primary = c,
            onPrimary = Color.White,
            primaryContainer = tone(c, 0.8f),
            onPrimaryContainer = shade(c, 0.6f),
            secondary = shade(c, 0.15f),
            secondaryContainer = tone(c, 0.85f),
            onSecondaryContainer = shade(c, 0.6f),
            tertiary = Color(0xFF7D5260),
            tertiaryContainer = Color(0xFFFFD8E4),
        )
    }
}

/**
 * True black for everything that forms the app's surfaces (pages, bars, sheets, cards, tonal
 * overlays) so OLED pixels stay off. Only controls that need to stand out from the page – the
 * search field, chips, image placeholders – keep a barely visible dark grey.
 */
private fun ColorScheme.amoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceTint = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color.Black,
    surfaceContainer = Color.Black,
    surfaceContainerHigh = Color(0xFF141416),
    surfaceContainerHighest = Color(0xFF1E1E22),
    surfaceBright = Color(0xFF1E1E22),
    surfaceVariant = Color(0xFF1E1E22),
)

/** Cards on a pure black page need a hairline to stay visible. */
@Composable
fun cardOutline(container: Color): BorderStroke? =
    if (container == Color.Black) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null

private val LumenTypography: Typography
    get() {
        val base = Typography()
        return base.copy(
            displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp),
            displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.25).sp),
            displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Medium),
            headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
            headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
            headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        )
    }

val EmphasizedNumber = TextStyle(fontWeight = FontWeight.Bold, fontSize = 44.sp, letterSpacing = (-1).sp)

private val LumenShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(34.dp),
)

@Composable
fun LumenTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme = remember(dark, settings.dynamicColor, settings.seedColor, settings.amoledBlack) {
        val base = if (settings.dynamicColor) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            schemeFromSeed(settings.seedColor, dark)
        }
        if (dark && settings.amoledBlack) base.amoled() else base
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
            window.decorView.setBackgroundColor(scheme.background.toArgb())
        }
    }

    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = if (settings.reduceMotion) MotionScheme.standard() else MotionScheme.expressive(),
        shapes = LumenShapes,
        typography = LumenTypography,
        content = content,
    )
}
