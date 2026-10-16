package com.ikverse.signallab.view.analyst

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ikverse.signallab.view.common.Panel
import com.ikverse.signallab.view.common.ScreenTitle
import com.ikverse.signallab.view.theme.Ink
import com.ikverse.signallab.view.theme.Type

/** The analyst's place, until the in-app Claude analyst arrives (milestone 8). */
@Composable
fun AnalystScreen(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(16.dp).testTag("analyst"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Analyst")
        Panel {
            Text("Coming in the next update", style = Type.Subhead)
            Text(
                "Claude will read your ledger, the market and the news, write a short brief after each daily close, answer your questions about any call, and propose new strategies to test. It will use an API key on your own Claude plan, and show what it sends and what it costs.",
                style = Type.Body.copy(color = Ink.Body),
            )
        }
    }
}
