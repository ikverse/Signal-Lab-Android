package com.ikverse.signallab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ikverse.signallab.R

/**
 * The look: TradingView's. Plain text on near-black, separated by thin lines, with no boxes or pills around labels, and
 * TradingView's own muted green and red for up and down. Near-black rather than black, because pure black smears on an
 * OLED screen when it scrolls. Roboto is bundled, because many phones (Samsung's among them) ship a different font.
 */
object Palette {
    val Background = Color(0xFF050505)
    val Raised = Color(0xFF0D0F14)
    val Rule = Color(0xFF1E222D)
    val Text = Color(0xFFD1D4DC)
    val Strong = Color(0xFFF0F3FA)
    val Muted = Color(0xFF868993)
    val Accent = Color(0xFF2962FF)
    val Up = Color(0xFF089981)
    val Down = Color(0xFFF23645)
    val Warn = Color(0xFFFF9800)
}

val Roboto = FontFamily(
    Font(R.font.roboto_regular, FontWeight.Normal),
    Font(R.font.roboto_medium, FontWeight.Medium),
    Font(R.font.roboto_bold, FontWeight.Bold),
)

/** The text styles in use. Sizes are in sp, so they follow the phone's text-size setting. */
object Type {
    private fun style(size: Int, weight: FontWeight = FontWeight.Normal, color: Color = Palette.Text, tabular: Boolean = false) = TextStyle(
        fontFamily = Roboto, fontSize = size.sp, fontWeight = weight, color = color, lineHeight = (size * 1.35).sp,
        fontFeatureSettings = if (tabular) "tnum" else null,
    )

    val Title = style(20, FontWeight.Medium, Palette.Strong)
    val Heading = style(16, FontWeight.Medium, Palette.Strong)
    val Body = style(14)
    val BodyStrong = style(14, FontWeight.Medium, Palette.Strong)
    val Small = style(12, color = Palette.Muted)
    val Label = style(11, FontWeight.Medium, Palette.Muted)
    val Number = style(14, tabular = true)
    val NumberStrong = style(14, FontWeight.Medium, Palette.Strong, tabular = true)
}

/** Whether the screens draw a WebView (the chart, the Learn pages). Tests turn it off: a WebView cannot load a page there. */
val LocalWebViews = staticCompositionLocalOf { true }

@Composable
fun SignalLabTheme(webViews: Boolean = true, content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        background = Palette.Background, surface = Palette.Background, surfaceVariant = Palette.Raised, onBackground = Palette.Text,
        onSurface = Palette.Text, onSurfaceVariant = Palette.Muted, primary = Palette.Accent, onPrimary = Palette.Strong,
        outline = Palette.Rule, error = Palette.Down,
    )
    val typography = Typography(
        bodyLarge = Type.Body, bodyMedium = Type.Body, bodySmall = Type.Small, titleLarge = Type.Title, titleMedium = Type.Heading,
        labelLarge = Type.BodyStrong, labelMedium = Type.Label, labelSmall = Type.Label,
    )
    CompositionLocalProvider(LocalWebViews provides webViews) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

/** A thin horizontal rule. */
@Composable
fun HRule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Palette.Rule))
}

/** A thin vertical rule. */
@Composable
fun VRule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxHeight().width(1.dp).background(Palette.Rule))
}
