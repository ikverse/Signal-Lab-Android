package com.ikverse.signallab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ikverse.signallab.BuildConfig

/** The icon that stands for a place, in the bars and on More. */
val Dest.icon: ImageVector
    get() = when (this) {
        Dest.Today -> Glyphs.Today
        Dest.Lab -> Glyphs.Lab
        Dest.Markets -> Glyphs.Markets
        Dest.Trades -> Glyphs.Trades
        Dest.Scorecard -> Glyphs.Scorecard
        Dest.Analyst -> Glyphs.Analyst
        Dest.Alerts -> Glyphs.Bell
        Dest.Learn -> Glyphs.Learn
        Dest.Lists -> Glyphs.Lists
        Dest.Settings -> Glyphs.More
        Dest.More -> Glyphs.Settings
    }

/** What a place behind More is for, in a line. */
private fun blurb(d: Dest): String = when (d) {
    Dest.Trades -> "Every practice trade, open and finished"
    Dest.Analyst -> "Ask Claude what your results mean, and keep its answers"
    Dest.Alerts -> "Every practice trade started or finished, and warnings"
    Dest.Learn -> "Plain explanations of how everything works"
    Dest.Lists -> "Which coins and chart sizes to watch"
    Dest.Settings -> "Fees, scanning, the screen, updates and backup"
    else -> ""
}

/**
 * More: the six places used least, each with what it is for. Back from any of them comes here (see [NavState.back]). Each row
 * carries the place's `nav-` tag, the same as a place in the bar.
 */
@Composable
fun MoreScreen(nav: NavState, modifier: Modifier = Modifier, onShowIntro: () -> Unit = {}) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("more")) {
        ScreenTitle("Settings")
        for (d in MorePlaces) {
            TouchRow({ nav.go(d) }, minHeight = 72.dp, modifier = Modifier.testTag("nav-${d.name}")) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Raised), contentAlignment = Alignment.Center) {
                    Icon(d.icon, contentDescription = null, tint = Palette.Text, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
                    Text(d.label, style = Type.BodyStrong)
                    Text(blurb(d), style = Type.Small)
                }
                Icon(Glyphs.ChevronRight, contentDescription = null, tint = Palette.Faint, modifier = Modifier.size(18.dp))
            }
            HRule()
        }
        TouchRow(onShowIntro, minHeight = 56.dp, modifier = Modifier.testTag("show-intro")) {
            Text("Show the intro again", style = Type.Body, modifier = Modifier.weight(1f))
            Icon(Glyphs.ChevronRight, contentDescription = null, tint = Palette.Faint, modifier = Modifier.size(18.dp))
        }
        HRule()
        Text("Signal Lab ${BuildConfig.VERSION_NAME} · research, not financial advice", style = Type.Small, modifier = Modifier.padding(16.dp))
    }
}
