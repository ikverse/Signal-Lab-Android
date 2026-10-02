package com.ikverse.signallab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The temporary debug screen: plain text and two buttons, in the app's colours. */
@Composable
fun DebugPanel(state: DebugState) {
    val lines = state.lines.collectAsStateWithLifecycle().value
    Column(
        Modifier.fillMaxSize().background(Color(0xFF050505)).padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for ((i, line) in lines.withIndex()) {
            Text(line, color = if (i == 0) Color(0xFFF0F3FA) else Color(0xFFB2B5BE), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = state::refreshPairList) { Text("Refresh pair list", color = Color(0xFF2962FF)) }
            TextButton(onClick = state::createTestList) { Text("Create test list: top 30", color = Color(0xFF2962FF)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = state::scanNow) { Text("Scan now", color = Color(0xFF2962FF)) }
            TextButton(onClick = state::sendTestAlert) { Text("Send test alert", color = Color(0xFF2962FF)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = state::createFastTestList) { Text("Create fast test list: 10 coins on 1m, 5m, 15m", color = Color(0xFF2962FF)) }
        }
    }
}
