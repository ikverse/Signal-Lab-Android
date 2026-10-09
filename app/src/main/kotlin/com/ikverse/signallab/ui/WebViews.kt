package com.ikverse.signallab.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.util.Locale

/**
 * The lines the user drew on charts, kept per coin and chart size while the app runs (not between runs). Each entry is the list
 * of drawings exactly as the chart page reported it, as JSON text; the page draws them again when that chart is shown.
 */
class ChartMemory {
    private val drawings = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The drawings kept for [key] (see [key]), as a JSON array: "[]" when there are none. */
    fun drawings(key: String): String = drawings[key] ?: "[]"

    /** Keeps [json], a JSON array of drawings, for [key]. Anything that is not an array is ignored; an empty array forgets the key. */
    fun remember(key: String, json: String) {
        val ok = try {
            org.json.JSONArray(json).length() > 0
        } catch (_: org.json.JSONException) {
            return
        }
        if (ok) drawings[key] = json else drawings.remove(key)
    }

    /** Keeps what the chart page reported, `{"key": "BTCUSDT|1h", "drawings": [...]}`. A report that does not read is ignored. */
    fun rememberReport(report: String) {
        try {
            val o = JSONObject(report)
            remember(o.getString("key"), o.getJSONArray("drawings").toString())
        } catch (_: org.json.JSONException) {
            // The drawings already kept stay.
        }
    }

    companion object {
        /** What identifies a chart: its coin and its size. */
        fun key(symbol: String, timeframe: String) = "$symbol|$timeframe"

        /** The one memory the app uses. */
        val Shared = ChartMemory()
    }
}

/**
 * What the chart page is given: candles as rows of numbers, the levels to draw, and the user's own drawings for this coin and chart
 * size (a JSON array, as [ChartMemory] keeps it). Built here so a test can read exactly what is sent.
 */
object ChartJson {
    fun build(chart: ChartUi, drawings: String = "[]"): String {
        val sb = StringBuilder(chart.candles.size * 64 + 256)
        sb.append("{\"key\":").append(JSONObject.quote(ChartMemory.key(chart.symbol, chart.timeframe))).append(",\"candles\":[")
        var first = true
        for (c in chart.candles) {
            if (!(c.open.isFinite() && c.high.isFinite() && c.low.isFinite() && c.close.isFinite() && c.volume.isFinite())) continue
            if (!first) sb.append(',')
            first = false
            sb.append('[').append(c.time).append(',').append(num(c.open)).append(',').append(num(c.high)).append(',')
                .append(num(c.low)).append(',').append(num(c.close)).append(',').append(num(c.volume)).append(']')
        }
        sb.append("],\"levels\":[")
        chart.levels.filter { it.price.isFinite() }.forEachIndexed { i, l ->
            if (i > 0) sb.append(',')
            sb.append("{\"kind\":\"").append(l.kind.name).append("\",\"label\":").append(JSONObject.quote(l.label)).append(",\"price\":").append(num(l.price)).append('}')
        }
        // The drawings are only ever our own page's JSON; anything that does not read as an array is not sent.
        val safe = try {
            org.json.JSONArray(drawings).toString()
        } catch (_: org.json.JSONException) {
            "[]"
        }
        return sb.append("],\"drawings\":").append(safe).append('}').toString()
    }

    private fun num(v: Double) = String.format(Locale.ROOT, "%.10g", v).let { if ('e' in it || 'E' in it) it else it.trimEnd('0').trimEnd('.').ifEmpty { "0" } }
}

/** Only the app's own bundled pages and scripts load: anything else (the network, a file elsewhere) gets an empty answer. */
internal class LocalOnlyClient(val onLoaded: () -> Unit, val onLink: (String) -> Unit = {}, val onGo: (String) -> Unit = {}) : WebViewClient() {
    override fun onPageFinished(view: WebView, url: String?) = onLoaded()

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        return if (url.startsWith("file:///android_asset/")) null else WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        when (val link = parseWebLink(request.url.toString())) {
            is WebLink.Page -> onLink(link.id)
            is WebLink.Place -> onGo(link.name)
            null -> {} // a web address or anything else goes nowhere
        }
        return true // never navigate away from the bundled page
    }
}

@Suppress("DEPRECATION")
@SuppressLint("SetJavaScriptEnabled")
internal fun safeWebView(context: android.content.Context, client: WebViewClient): WebView = WebView(context).apply {
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    setBackgroundColor(Palette.Background.toArgb())
    overScrollMode = android.view.View.OVER_SCROLL_NEVER
    settings.apply {
        javaScriptEnabled = true // the pages are the app's own, bundled in it
        allowFileAccess = true // needed to read the bundled pages from the app's assets
        allowContentAccess = false
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        blockNetworkLoads = true
        cacheMode = WebSettings.LOAD_NO_CACHE
        setSupportZoom(false)
        domStorageEnabled = false
    }
    webViewClient = client
}

/** The indicators the chart shows until the user has chosen: volume alone. */
val DefaultIndicators = listOf("VOL")

/**
 * The app's buttons over the chart (Indicators, Draw, Latest) reach the chart page through this. Until a chart is on screen, and in
 * tests, a press does nothing.
 */
class ChartControl {
    internal var run: (String) -> Unit = {}

    fun indicators() = run("signalLab.openIndicators()")

    fun draw() = run("signalLab.openDraw()")

    fun latest() = run("signalLab.latest()")
}

/**
 * The chart: KLineChart in a web view, with an open paper trade's levels drawn on it and the live price as a line. Its menus
 * (indicators, drawing tools) and the jump to the latest candle are opened through [control], from the app's own buttons. [indicators]
 * are the ids to show and [onIndicators] hears each change; drawings are kept in [memory] per coin and chart size. In tests (and
 * anywhere a web view cannot run) it is a plain line of text saying what it would draw.
 */
@Composable
fun ChartView(
    chart: ChartUi?,
    livePrice: Double?,
    modifier: Modifier = Modifier,
    indicators: List<String> = DefaultIndicators,
    onIndicators: (List<String>) -> Unit = {},
    memory: ChartMemory = ChartMemory.Shared,
    control: ChartControl? = null,
) {
    if (!LocalWebViews.current) {
        Box(modifier.fillMaxSize().background(Palette.Background), contentAlignment = Alignment.Center) {
            Text(
                chart?.let { "Chart: ${it.symbol} ${it.timeframe}, ${it.candles.size} candles, ${it.levels.size} levels" } ?: "Chart: nothing to draw",
                style = Type.Small, modifier = Modifier.testTag("chart-placeholder"),
            )
        }
        return
    }
    val pool = rememberWebPool()
    val kept = remember(pool) { pool.keep(WebPage.Chart) }
    SideEffect {
        kept.memory = memory
        kept.onIndicators = onIndicators
        control?.run = { js -> if (kept.loaded) kept.view.evaluateJavascript(js, null) }
    }
    LaunchedEffect(chart, kept.loaded) {
        if (kept.loaded && chart != null) {
            kept.view.evaluateJavascript("signalLab.setData(${ChartJson.build(chart, memory.drawings(ChartMemory.key(chart.symbol, chart.timeframe)))})", null)
        }
    }
    LaunchedEffect(indicators, kept.loaded) {
        if (kept.loaded) kept.view.evaluateJavascript("signalLab.setIndicators(${org.json.JSONArray(indicators)})", null)
    }
    LaunchedEffect(livePrice, chart, kept.loaded) {
        if (kept.loaded && chart != null && livePrice != null && livePrice > 0) kept.view.evaluateJavascript("signalLab.setLastPrice($livePrice)", null)
    }
    // Back closes an open menu first, rather than leaving the chart with the menu still open behind it.
    BackHandler(enabled = kept.menuOpen) { closeChartMenu(kept) }
    // The view is the pool's, built once: put on screen here, taken off when this screen goes, and kept for the next visit. A menu left open
    // is closed as it goes, so the chart never comes back with it open.
    AndroidView(
        modifier = modifier.fillMaxSize().testTag("chart"),
        factory = { pool.attach(WebPage.Chart) },
        onRelease = {
            closeChartMenu(kept)
            pool.detach(WebPage.Chart)
        },
    )
}

private fun closeChartMenu(kept: KeptWeb) {
    if (kept.loaded) kept.view.evaluateJavascript("signalLab.closeMenu()", null)
    kept.menuOpen = false
}

/** One Learn page: the Markdown shown by the marked and Mermaid libraries bundled in the app. A link written as learn:id opens that page. */
@Composable
fun LearnView(page: LearnPageUi?, onOpenPage: (String) -> Unit, modifier: Modifier = Modifier, onGo: (String) -> Unit = {}) {
    if (!LocalWebViews.current) {
        Box(modifier.fillMaxSize().background(Palette.Background).testTag("learn-placeholder")) {
            Text(page?.markdown ?: "Choose a page.", style = Type.Body, modifier = Modifier.testTag("learn-text"))
        }
        return
    }
    val pool = rememberWebPool()
    val kept = remember(pool) { pool.keep(WebPage.Learn) }
    SideEffect {
        kept.onLink = onOpenPage
        kept.onGo = onGo
    }
    LaunchedEffect(page, kept.loaded) {
        // Coming back to the page already shown leaves it, and where it was scrolled to, as it was.
        if (kept.loaded && page != null && kept.showing != page.markdown) {
            kept.view.evaluateJavascript("render(${JSONObject.quote(page.markdown)})", null)
            kept.showing = page.markdown
        }
    }
    AndroidView(
        modifier = modifier.fillMaxSize().testTag("learn"),
        factory = { pool.attach(WebPage.Learn) },
        onRelease = { pool.detach(WebPage.Learn) },
    )
}

/** The result of the chart check: how long drawing took and how smoothly the chart scrolled. */
data class ChartCheckResult(val drawMs: Long, val frames: Int, val averageFrameMs: Double, val worstFrameMs: Double)

/** A temporary page's WebView that runs the chart check, for the debug page. Calls [onResult] once. */
@Composable
fun ChartCheckView(candles: Int, onResult: (ChartCheckResult) -> Unit, modifier: Modifier = Modifier) {
    if (!LocalWebViews.current) return
    DisposableEffect(Unit) { onDispose { } }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val bridge = object {
                @JavascriptInterface
                fun report(json: String) {
                    val o = JSONObject(json)
                    onResult(ChartCheckResult(o.getLong("drawMs"), o.getInt("frames"), o.getDouble("avgMs"), o.getDouble("worstMs")))
                }
            }
            lateinit var web: WebView
            web = safeWebView(ctx, LocalOnlyClient(onLoaded = {
                web.evaluateJavascript("signalLab.check($candles)", null)
            })).also { it.addJavascriptInterface(bridge, "Android") }
            web.loadUrl("file:///android_asset/chart/chart.html")
            web
        },
    )
}
