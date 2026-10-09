package com.ikverse.signallab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Where a foldable's fold is, in dp from the top-left of the app's window. [horizontal] is a fold that runs across the screen (the
 * phone stands like a laptop when it is [halfOpen]); a fold that runs down the screen is a book. [startDp] is where the fold starts
 * along the axis it divides, and [sizeDp] how wide a hinge it is (0 for a seamless one).
 */
data class FoldInfo(val horizontal: Boolean, val halfOpen: Boolean, val startDp: Float, val sizeDp: Float = 0f) {
    /** A half-open fold across the screen: the top half is held up, the bottom half lies flat. */
    val tabletop: Boolean get() = horizontal && halfOpen && startDp > 0f
}

/** The fold of the screen the app is on, or null on a phone that does not fold. */
val LocalFold = staticCompositionLocalOf<FoldInfo?> { null }

object FoldMath {
    /** The least a pane is left when a book's fold is used to place a divider. */
    const val MIN_PANE = PaneMath.MIN_SIDE

    /**
     * The width the first of two side-by-side panes should start at so that the divider sits on a vertical fold: the fold's position, less
     * [originDp] (where the panes start in the window) and half the divider. Null when there is no vertical fold, or when that would leave
     * the first pane too narrow, or too little for the second out of [totalDp].
     */
    fun firstPane(fold: FoldInfo?, originDp: Float, totalDp: Float): Float? {
        if (fold == null || fold.horizontal) return null
        val w = fold.startDp - originDp - PaneMath.DIVIDER / 2 + fold.sizeDp / 2
        return w.takeIf { it >= MIN_PANE && totalDp - w - PaneMath.DIVIDER >= PaneMath.MIN_PAGE }
    }

    /** The height of the top half when the phone stands like a laptop, kept between a fifth and four fifths of [totalDp]. */
    fun topHalf(fold: FoldInfo, totalDp: Float): Float = fold.startDp.coerceIn(totalDp * 0.2f, totalDp * 0.8f)
}

/**
 * What the held-up half of a phone standing like a laptop shows: the coin you are looking at, large, with its price and its open trade's
 * three prices, readable from a few steps away. The app itself lies on the lower half.
 */
@Composable
fun TableTopHeader(model: AppModel, nav: NavState, modifier: Modifier = Modifier) {
    val coins by model.markets.coins.collectAsStateWithLifecycle()
    val prices by model.markets.prices.collectAsStateWithLifecycle()
    val trades by model.trades.trades.collectAsStateWithLifecycle()
    val open = setupsOf(trades)
    val symbol = nav.symbol ?: open.firstOrNull()?.symbol ?: coins.firstOrNull()?.symbol
    val coin = coins.firstOrNull { it.symbol == symbol }
    val trade = open.firstOrNull { it.symbol == symbol }
    val price = symbol?.let { prices[it] } ?: coin?.price
    Box(modifier.fillMaxSize().background(Palette.Background).testTag("tabletop-header"), contentAlignment = Alignment.Center) {
        if (symbol == null) {
            Text("Choose a coin below", style = Type.Heading)
            return@Box
        }
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(baseOf(symbol), style = Type.Title)
            Text(Fmt.price(price), style = Type.Big, modifier = Modifier.testTag("tabletop-price"))
            ChangePill(coin?.changeFraction)
            if (trade != null) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    TableCell("Entry price", Fmt.price(trade.entryPrice), Palette.Strong)
                    TableCell("Profit goal", trade.target?.let { Fmt.price(it) } ?: "—", Palette.Up)
                    TableCell("Loss limit", trade.stop?.let { Fmt.price(it) } ?: "—", Palette.Down)
                }
                PlainWords.since(trade.entryPrice, price)?.let { Text(it, style = Type.Small, textAlign = TextAlign.Center) }
            }
        }
    }
}

@Composable
private fun TableCell(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = Type.Label)
        Text(value, style = Type.Kpi.copy(color = color))
    }
}
