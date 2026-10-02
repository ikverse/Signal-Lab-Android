package com.ikverse.signallab.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** The debug page behind a long press on the version in Settings (debug builds only): the data layer's state, and the chart check. */
@Composable
fun DebugScreen(state: DebugState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ChartCheckResult?>(null) }
    Column(modifier.fillMaxSize().testTag("debug")) {
        TextAction("‹ Back", onBack, color = Palette.Muted)
        Box(Modifier.weight(1f)) { DebugPanel(state) }
        HRule()
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            TextAction(if (checking) "Chart check running…" else "Run chart check (2,000 candles)", { result = null; checking = true }, enabled = !checking)
            result?.let {
                Text(
                    "Drawn in ${it.drawMs} ms. ${it.frames} frames while scrolling: average ${"%.1f".format(it.averageFrameMs)} ms, worst ${"%.1f".format(it.worstFrameMs)} ms.",
                    style = Type.Small, modifier = Modifier.padding(horizontal = 16.dp).testTag("chart-check-result"),
                )
            }
            if (checking) ChartCheckView(2000, onResult = { result = it; checking = false }, modifier = Modifier.fillMaxWidth().height(220.dp))
        }
    }
}
