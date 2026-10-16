package com.ikverse.signallab.view.common

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** The app's icons, drawn as strokes on a 24-unit grid, as in the mockup. Tinted by whatever shows them. */
object Icons {
    private fun stroke(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (d in paths) {
                addPath(
                    PathParser().parsePathString(d).toNodes(), stroke = SolidColor(androidx.compose.ui.graphics.Color.White), strokeLineWidth = 1.8f,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val Today = stroke("today", "M6 5h12a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M4 10h16", "M9 3v4", "M15 3v4")
    val Ledger = stroke("ledger", "M4 19h16", "M5 15l4-4 3 3 6-7")
    val Strategies = stroke("strategies", "M12 4l8 4-8 4-8-4z", "M4 12l8 4 8-4", "M4 16l8 4 8-4")
    val Markets = stroke("markets", "M15 9.5a5.5 5.5 0 1 1-11 0a5.5 5.5 0 1 1 11 0z", "M20 14.5a5.5 5.5 0 1 1-11 0a5.5 5.5 0 1 1 11 0z")
    val Analyst = stroke("analyst", "M5 5h14v10H9l-4 4z", "M12 7.5v5", "M9.5 10h5")
    val Settings = stroke(
        "settings", "M15.2 12a3.2 3.2 0 1 1-6.4 0a3.2 3.2 0 1 1 6.4 0z",
        "M12 2.5v3", "M12 18.5v3", "M2.5 12h3", "M18.5 12h3", "M5.3 5.3l2.1 2.1", "M16.6 16.6l2.1 2.1", "M5.3 18.7l2.1-2.1", "M16.6 7.4l2.1-2.1",
    )
    val Back = stroke("back", "M15 5l-7 7 7 7")
}
