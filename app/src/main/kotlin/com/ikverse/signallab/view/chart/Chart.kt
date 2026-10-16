package com.ikverse.signallab.view.chart

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import com.ikverse.signallab.engine.Candles
import com.ikverse.signallab.view.theme.Ink
import com.ikverse.signallab.view.theme.Type
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** A line across the chart: the entry (amber), the safety stop (coral), or an exit line (grey). */
data class Level(val kind: String, val label: String, val price: Double) {
    companion object {
        const val ENTRY = "ENTRY"
        const val STOP = "STOP"
        const val EXIT = "EXIT"
    }
}

/** What the chart draws: one coin's candles on one chart, and its lines. */
data class ChartData(val symbol: String, val timeframe: String, val candles: Candles, val levels: List<Level> = emptyList())

/** What the bundled chart page is given, as JSON. Built here so a test reads exactly what is sent. */
object ChartJson {
    fun build(d: ChartData): String {
        val c = d.candles
        val sb = StringBuilder(c.size * 64 + 256)
        sb.append("{\"key\":").append(JSONObject.quote("${d.symbol}|${d.timeframe}")).append(",\"candles\":[")
        var first = true
        for (i in 0 until c.size) {
            if (!(c.open[i].isFinite() && c.high[i].isFinite() && c.low[i].isFinite() && c.close[i].isFinite() && c.volume[i].isFinite())) continue
            if (!first) sb.append(',')
            first = false
            sb.append('[').append(c.t[i]).append(',').append(num(c.open[i])).append(',').append(num(c.high[i])).append(',')
                .append(num(c.low[i])).append(',').append(num(c.close[i])).append(',').append(num(c.volume[i])).append(']')
        }
        sb.append("],\"levels\":[")
        d.levels.filter { it.price.isFinite() }.forEachIndexed { i, l ->
            if (i > 0) sb.append(',')
            sb.append("{\"kind\":").append(JSONObject.quote(l.kind)).append(",\"label\":").append(JSONObject.quote(l.label)).append(",\"price\":").append(num(l.price)).append('}')
        }
        return sb.append("],\"drawings\":[]}").toString()
    }

    private fun num(v: Double) = String.format(Locale.ROOT, "%.10g", v).let { if ('e' in it || 'E' in it) it else it.trimEnd('0').trimEnd('.').ifEmpty { "0" } }
}

/** Only the app's own bundled pages and scripts load; anything else gets an empty answer, and no link navigates away. */
private class LocalOnly(val onLoaded: () -> Unit) : WebViewClient() {
    override fun onPageFinished(view: WebView, url: String?) = onLoaded()

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        if (request.url.toString().startsWith("file:///android_asset/")) null
        else WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
}

/** The chart's web view, built once and kept between visits: building one freezes the screen for a moment. */
class ChartHost(context: Context) {
    var loaded by mutableStateOf(false)
    var menuOpen by mutableStateOf(false)

    @Suppress("DEPRECATION")
    @SuppressLint("SetJavaScriptEnabled")
    val view: WebView = WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(Ink.Ground.toArgb())
        overScrollMode = android.view.View.OVER_SCROLL_NEVER
        settings.apply {
            javaScriptEnabled = true // the page is the app's own, bundled in it
            allowFileAccess = true
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            blockNetworkLoads = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            setSupportZoom(false)
        }
        webViewClient = LocalOnly { loaded = true }
        val v = this
        addJavascriptInterface(object {
            @JavascriptInterface
            fun indicatorsChanged(@Suppress("UNUSED_PARAMETER") csv: String) = Unit

            @JavascriptInterface
            fun drawingsChanged(@Suppress("UNUSED_PARAMETER") json: String) = Unit

            @JavascriptInterface
            fun menuChanged(open: String) {
                v.post { menuOpen = open == "true" }
            }
        }, "Android")
        loadUrl("file:///android_asset/chart/chart.html")
    }

    fun run(js: String) {
        if (loaded) view.evaluateJavascript(js, null)
    }

    fun detach() {
        (view.parent as? ViewGroup)?.removeView(view)
        view.onPause()
    }

    fun attach(): WebView {
        (view.parent as? ViewGroup)?.removeView(view)
        view.onResume()
        return view
    }
}

/** The activity's kept chart; null in tests, where the chart is a line of text instead. */
val LocalChartHost = staticCompositionLocalOf<ChartHost?> { null }

/** The chart's own menus, opened from the app's buttons. */
class ChartControls internal constructor(private val host: ChartHost?) {
    fun indicators() = host?.run("signalLab.openIndicators()")
    fun draw() = host?.run("signalLab.openDraw()")
    fun latest() = host?.run("signalLab.latest()")
}

@Composable
fun rememberChartControls(): ChartControls {
    val host = LocalChartHost.current
    return remember(host) { ChartControls(host) }
}

/**
 * KLineChart in a web view, with the call's lines on it and the live price as a line. Without a kept web view (tests, or a
 * phone with no web engine) it is a plain line of text saying what it would draw.
 */
@Composable
fun Chart(data: ChartData?, livePrice: Double?, modifier: Modifier = Modifier) {
    val host = LocalChartHost.current
    if (host == null) {
        Box(modifier.fillMaxSize().background(Ink.Surface), contentAlignment = Alignment.Center) {
            Text(data?.let { "Chart: ${it.symbol} ${it.timeframe}, ${it.candles.size} candles, ${it.levels.size} lines" } ?: "Chart: nothing to draw",
                style = Type.Small, modifier = Modifier.testTag("chart-placeholder"))
        }
        return
    }
    LaunchedEffect(data, host.loaded) { if (data != null) host.run("signalLab.setData(${ChartJson.build(data)})") }
    LaunchedEffect(host.loaded) { host.run("signalLab.setIndicators(${JSONArray(listOf("VOL"))})") }
    LaunchedEffect(livePrice, data, host.loaded) { if (data != null && livePrice != null && livePrice > 0) host.run("signalLab.setLastPrice($livePrice)") }
    androidx.activity.compose.BackHandler(enabled = host.menuOpen) {
        host.run("signalLab.closeMenu()")
        host.menuOpen = false
    }
    DisposableEffect(host) { onDispose { host.detach() } }
    AndroidView(modifier = modifier.fillMaxSize().testTag("chart"), factory = { host.attach() })
}

/** A chart host made once per activity. */
@Composable
fun rememberChartHost(): ChartHost {
    val context = LocalContext.current
    return remember { ChartHost(context) }
}
