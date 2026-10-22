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
        "settings",
        "M9.71 4.75L10.44 2.12L13.56 2.12L14.29 4.75L15.51 5.26L17.88 3.91L20.09 6.12L18.74 8.49L19.25 9.71L21.88 10.44L21.88 13.56" +
            "L19.25 14.29L18.74 15.51L20.09 17.88L17.88 20.09L15.51 18.74L14.29 19.25L13.56 21.88L10.44 21.88L9.71 19.25L8.49 18.74" +
            "L6.12 20.09L3.91 17.88L5.26 15.51L4.75 14.29L2.12 13.56L2.12 10.44L4.75 9.71L5.26 8.49L3.91 6.12L6.12 3.91L8.49 5.26z",
        "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z",
    )
    val Back = stroke("back", "M15 5l-7 7 7 7")
}
