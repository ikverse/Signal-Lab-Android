package com.ikverse.signallab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The three cards a new user sees once: what the app does, that nothing is real, and that it checks itself. */
val IntroCards: List<Pair<String, String>> = listOf(
    "Signal Lab watches coins for you." to
        "When a coin matches a pattern, you get one plain message: which coin, the price the practice trade starts at, and the two prices that end it.",
    "Nothing here uses real money." to
        "Every setup becomes a practice trade, so you see what would have happened without risking anything. It is research, not advice.",
    "It checks itself, and lets you experiment." to
        "\"Does it work?\" tells you in plain words whether the patterns beat guessing. The Lab lets you test your own ideas the same honest way.",
)

/**
 * The first-run introduction, over the whole app. [onDone] is called when it is finished or skipped; [onLearn] when the reader asks for the
 * basics first, which also finishes it.
 */
@Composable
fun IntroOverlay(onDone: () -> Unit, onLearn: () -> Unit, modifier: Modifier = Modifier) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val last = index == IntroCards.lastIndex
    Box(modifier.fillMaxSize().background(Palette.Background).testTag("intro"), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("${index + 1} of ${IntroCards.size}", style = Type.Small)
            Text(IntroCards[index].first, style = Type.Title.copy(fontSize = 26.sp), modifier = Modifier.testTag("intro-title"))
            Text(IntroCards[index].second, style = Type.Body, modifier = Modifier.testTag("intro-body"))
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                for (i in IntroCards.indices) {
                    Box(
                        Modifier.padding(horizontal = 4.dp).size(8.dp).clip(CircleShape).background(if (i == index) Palette.Accent else Palette.Rule),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            TonalButton(if (last) "Show me" else "Next", { if (last) onDone() else index++ }, Modifier.fillMaxWidth().testTag("intro-next"))
            if (last) LineButton("Learn the basics first", onLearn, Modifier.fillMaxWidth().testTag("intro-learn"))
            TextAction("Skip", onDone, Modifier.align(Alignment.CenterHorizontally).testTag("intro-skip"))
        }
    }
}
