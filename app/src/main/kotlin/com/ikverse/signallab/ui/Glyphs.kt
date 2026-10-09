package com.ikverse.signallab.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The app's icons: thin outlines on a 24-unit grid, drawn here rather than taken from an icon library, so the app carries only the
 * few it uses. An [androidx.compose.material3.Icon] tints the outline to whatever colour it is given.
 */
object Glyphs {
    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"

    private fun outline(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (d in paths) {
                addPath(
                    addPathNodes(d), fill = null, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val Lab = outline("lab", "M9 3h6M10 3v6l-5 9a2 2 0 0 0 2 3h10a2 2 0 0 0 2-3l-5-9V3", "M7.5 15h9")
    val Today = outline("today", circle(12f, 12f, 8f), circle(12f, 12f, 3f))
    val Markets = outline("markets", "M7 3v3M7 17v4M17 3v5M17 15v6", rect(5f, 6f, 4f, 11f, 1f), rect(15f, 8f, 4f, 7f, 1f))
    val Trades = outline("trades", "M5 6h14M5 12h14M5 18h9")
    val Scorecard = outline("scorecard", "M5 20V11M12 20V5M19 20v-6")
    val Analyst = outline("analyst", "M4 5h16v11H9l-5 4z")
    val More = outline("more", rect(4f, 4f, 6f, 6f, 1.5f), rect(14f, 4f, 6f, 6f, 1.5f), rect(4f, 14f, 6f, 6f, 1.5f), rect(14f, 14f, 6f, 6f, 1.5f))
    val Bell = outline("bell", "M6 16v-5a6 6 0 0 1 12 0v5l1.5 2h-15z", "M10 20a2 2 0 0 0 4 0")
    val Learn = outline("learn", "M5 4h11a3 3 0 0 1 3 3v13H8a3 3 0 0 1-3-3z", "M5 17a3 3 0 0 1 3-3h11")
    val Lists = outline("lists", "M7 4h10v16l-5-4-5 4z")
    val Settings = outline("settings", "M4 7h9M17 7h3M4 17h3M11 17h9", circle(15f, 7f, 2f), circle(9f, 17f, 2f))
    val Search = outline("search", circle(11f, 11f, 6f), "M20 20l-4.5-4.5")
    val Info = outline("info", circle(12f, 12f, 9f), "M12 11v5M12 8h0.01")
    val ChevronRight = outline("chevron-right", "M9 6l6 6-6 6")
    val ChevronLeft = outline("chevron-left", "M15 6l-6 6 6 6")
    val ChevronDown = outline("chevron-down", "M6 9l6 6 6-6")
    val ChevronUp = outline("chevron-up", "M6 15l6-6 6 6")
    val Back = outline("back", "M19 12H5M11 6l-6 6 6 6")
    val External = outline("external", "M14 5h5v5M19 5l-8 8M18 14v5H5V6h5")
    val Check = outline("check", "M5 12l4 4 10-10")
    val Close = outline("close", "M7 7l10 10M17 7L7 17")
    val Opened = outline("opened", "M4 17l6-6 4 4 6-6M15 9h5v5")
    val Plus = outline("plus", "M12 5v14M5 12h14")
    val Indicators = outline("indicators", "M3 16c3 0 3-9 6-9s3 10 6 10 3-6 6-6")
    val Draw = outline("draw", "M4 20l4-1L19 8l-3-3L5 16z", "M14 7l3 3")
    val Latest = outline("latest", "M4 12h11M11 7l5 5-5 5M20 5v14")
    val Clock = outline("clock", circle(12f, 12f, 9f), "M12 7v5l3 2")
    val Warning = outline("warning", "M12 4L2.5 20h19z", "M12 10v4M12 17h0.01")
    val Dash = outline("dash", "M6 12h12")
}
