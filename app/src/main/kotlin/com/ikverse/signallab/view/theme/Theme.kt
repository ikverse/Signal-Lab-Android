package com.ikverse.signallab.view.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Signal Lab 2's look, "Night desk" (the approved mockup): a graphite ground, one amber accent for what you act on, mint
 * for gains and coral for losses, which also differ in lightness so they never rely on hue alone. Words in the phone's sans
 * serif; every number in a monospaced face with figures that line up.
 */
object Ink {
    val Ground = Color(0xFF0A0C0E)
    val Bar = Color(0xFF0E1114)
    val Surface = Color(0xFF14181C)
    val Raised = Color(0xFF1C2127)
    val Rule = Color(0xFF232A31)
    val Outline = Color(0xFF2A3038)
    val Text = Color(0xFFEEF1F4)
    val Strong = Color(0xFFD7DDE3)
    val Body = Color(0xFFC9D1D9)
    val Muted = Color(0xFF9AA4AF)
    val Faint = Color(0xFF7D8792)
    val Accent = Color(0xFFF2B544)
    val OnAccent = Color(0xFF1A1305)
    val AccentSoft = Color(0x26F2B544)
    val Gain = Color(0xFF6FE3B0)
    val GainDot = Color(0xFF4FD69C)
    val GainGround = Color(0xFF123A2A)
    val Loss = Color(0xFFFF8A78)
    val Info = Color(0xFF9CC4FF)
    val InfoLine = Color(0xFF3B5C86)
    val Watch = Color(0xFFE8C27A)
    val WatchLine = Color(0xFF5A4A2A)
    val UrgentGround = Color(0xFF2A1E10)
    val UrgentText = Color(0xFFE6D3B3)
}

object Type {
    private val sans = FontFamily.SansSerif
    val Numbers: FontFamily = FontFamily.Monospace

    val Display = TextStyle(fontFamily = sans, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em, color = Ink.Text)
    val Title = TextStyle(fontFamily = sans, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em, color = Ink.Text)
    val Heading = TextStyle(fontFamily = sans, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink.Text)
    val Subhead = TextStyle(fontFamily = sans, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink.Text)
    val Body = TextStyle(fontFamily = sans, fontSize = 15.sp, lineHeight = 22.sp, color = Ink.Strong)
    val Small = TextStyle(fontFamily = sans, fontSize = 13.sp, lineHeight = 18.sp, color = Ink.Muted)
    val Caption = TextStyle(fontFamily = sans, fontSize = 12.sp, lineHeight = 16.sp, color = Ink.Muted)
    val Overline = TextStyle(fontFamily = sans, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.06.em, color = Ink.Muted)
    val Number = TextStyle(fontFamily = Numbers, fontSize = 15.sp, color = Ink.Text, fontFeatureSettings = "tnum")
    val BigNumber = TextStyle(fontFamily = Numbers, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Ink.Text, fontFeatureSettings = "tnum")
}

@Composable
fun SignalLabTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ink.Accent, onPrimary = Ink.OnAccent, background = Ink.Ground, onBackground = Ink.Text,
            surface = Ink.Surface, onSurface = Ink.Text, surfaceVariant = Ink.Raised, onSurfaceVariant = Ink.Muted,
            outline = Ink.Outline, error = Ink.Loss,
        ),
        content = content,
    )
}
