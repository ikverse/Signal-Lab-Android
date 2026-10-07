package com.ikverse.signallab.ui

import androidx.compose.animation.core.CubicBezierEasing
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
 * The look: near-black, plain lists split by thin lines, and a raised surface only where things belong together (a trade's
 * card, a group of settings). Numbers carry the colour; labels stay neutral. Near-black rather than black, because pure black
 * smears on an OLED screen when it scrolls. TradingView's muted green and red for up and down. Roboto is bundled, because many
 * phones (Samsung's among them) ship a different font.
 */
object Palette {
    val Background = Color(0xFF050505)
    val Raised = Color(0xFF0E1014)
    val Rule = Color(0xFF15181E)
    val Text = Color(0xFFCDD1D9)
    val Strong = Color(0xFFF4F5F7)
    // About 6.8:1 on the background, so the secondary lines read without effort.
    val Muted = Color(0xFF8F95A1)
    /** Only for marks that say nothing on their own, such as a row's chevron. */
    val Faint = Color(0xFF5F6570)
    // About 6.5:1 on the background.
    val Accent = Color(0xFF5B8DFF)
    /** Behind the chosen tab's icon and a filled button, with [OnAccentTint] on it. */
    val AccentTint = Color(0xFF16213A)
    val OnAccentTint = Color(0xFF9DBBFF)
    /** A chart tag ("15m") and a count. */
    val TagFill = Color(0xFF171A21)
    val TagText = Color(0xFFB7BCC6)
    /** A chosen chip, and the edge of one that is not. */
    val ChipFill = Color(0xFF1A1E27)
    val ChipEdge = Color(0xFF2C3240)
    val Up = Color(0xFF089981)
    val Down = Color(0xFFF23645)
    val Warn = Color(0xFFFF9800)
    /** Behind a change in a pill: the same green and red, faint. */
    val UpTint = Up.copy(alpha = 0.14f)
    val DownTint = Down.copy(alpha = 0.14f)
}

val Roboto = FontFamily(
    Font(R.font.roboto_regular, FontWeight.Normal),
    Font(R.font.roboto_medium, FontWeight.Medium),
    Font(R.font.roboto_bold, FontWeight.Bold),
)

/** The text styles in use. Sizes are in sp, so they follow the phone's text-size setting. */
object Type {
    // Large text sits tighter (leading and tracking shrink as it grows); small text opens up a little to stay legible.
    private fun style(
        size: Int, weight: FontWeight = FontWeight.Normal, color: Color = Palette.Text, tabular: Boolean = false,
        leading: Double = 1.35, tracking: Double = 0.0,
    ) = TextStyle(
        fontFamily = Roboto, fontSize = size.sp, fontWeight = weight, color = color, lineHeight = (size * leading).sp,
        letterSpacing = (size * tracking).sp, fontFeatureSettings = if (tabular) "tnum" else null,
    )

    val Title = style(22, FontWeight.Medium, Palette.Strong, leading = 1.25, tracking = -0.01)
    /** Also a coin's name in a list. */
    val Heading = style(17, FontWeight.Medium, Palette.Strong, leading = 1.25)
    val Body = style(15)
    val BodyStrong = style(15, FontWeight.Medium, Palette.Strong)
    val Small = style(13, color = Palette.Muted, tracking = 0.01)
    /** A section's name, in sentence case. */
    val Section = style(13, FontWeight.Medium, Palette.Muted, tracking = 0.01)
    val Label = style(12, FontWeight.Medium, Palette.Muted, tracking = 0.01)
    val Number = style(15, tabular = true)
    val NumberStrong = style(15, FontWeight.Medium, Palette.Strong, tabular = true)
    /** A figure that is the point of its screen: a coin's price over its chart, a trade's result. */
    val Big = style(24, FontWeight.Medium, Palette.Strong, tabular = true, leading = 1.2)
    /** The numbers in a strip of totals. */
    val Kpi = style(19, FontWeight.Medium, Palette.Strong, tabular = true, leading = 1.25)
}

/** The few moves the app makes: quick and ease-out, because a dense screen should answer a touch at once and never make anyone wait. */
object Motion {
    val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
    const val PRESS_MS = 120
    const val ENTER_MS = 200
    const val EXIT_MS = 150
    const val FADE_MS = 120
    const val PRESSED_SCALE = 0.97f
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
