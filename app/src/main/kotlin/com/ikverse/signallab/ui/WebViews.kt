package com.ikverse.signallab.ui

import android.annotation.SuppressLint
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.util.Locale

/** What the chart page is given: candles as rows of numbers, and the levels to draw. Built here so a test can read exactly what is sent. */
object ChartJson {
    fun build(chart: ChartUi): String {
        val sb = StringBuilder(chart.candles.size * 64 + 256)
        sb.append("{\"candles\":[")
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
        return sb.append("]}").toString()
    }

    private fun num(v: Double) = String.format(Locale.ROOT, "%.10g", v).let { if ('e' in it || 'E' in it) it else it.trimEnd('0').trimEnd('.').ifEmpty { "0" } }
}

/** Only the app's own bundled pages and scripts load: anything else (the network, a file elsewhere) gets an empty answer. */
private class LocalOnlyClient(val onLoaded: () -> Unit, val onLink: (String) -> Unit = {}) : WebViewClient() {
    override fun onPageFinished(view: WebView, url: String?) = onLoaded()

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        return if (url.startsWith("file:///android_asset/")) null else WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        if (url.startsWith("learn:")) onLink(url.removePrefix("learn:"))
        return true // never navigate away from the bundled page
    }
}

@Suppress("DEPRECATION")
@SuppressLint("SetJavaScriptEnabled")
private fun safeWebView(context: android.content.Context, client: WebViewClient): WebView = WebView(context).apply {
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

/**
 * The chart: KLineChart in a web view, with the open paper trades drawn on it and the live price as a line. In tests (and anywhere a web
 * view cannot run) it is a plain line of text saying what it would draw.
 */
@Composable
fun ChartView(chart: ChartUi?, livePrice: Double?, modifier: Modifier = Modifier) {
    if (!LocalWebViews.current) {
        Box(modifier.fillMaxSize().background(Palette.Background), contentAlignment = Alignment.Center) {
            Text(
                chart?.let { "Chart: ${it.symbol} ${it.timeframe}, ${it.candles.size} candles, ${it.levels.size} levels" } ?: "Chart: nothing to draw",
                style = Type.Small, modifier = Modifier.testTag("chart-placeholder"),
            )
        }
        return
    }
    var view by remember { mutableStateOf<WebView?>(null) }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(chart, ready, view) {
        val v = view
        if (ready && v != null && chart != null) v.evaluateJavascript("signalLab.setData(${ChartJson.build(chart)})", null)
    }
    LaunchedEffect(livePrice, chart, ready, view) {
        val v = view
        if (ready && v != null && chart != null && livePrice != null && livePrice > 0) v.evaluateJavascript("signalLab.setLastPrice($livePrice)", null)
    }
    DisposableEffect(Unit) {
        onDispose { view?.destroy() }
    }
    AndroidView(
        modifier = modifier.fillMaxSize().testTag("chart"),
        factory = { ctx ->
            safeWebView(ctx, LocalOnlyClient(onLoaded = { ready = true })).also {
                view = it
                it.loadUrl("file:///android_asset/chart/chart.html")
            }
        },
    )
}

/** One Learn page: the Markdown shown by the marked and Mermaid libraries bundled in the app. A link written as learn:id opens that page. */
@Composable
fun LearnView(page: LearnPageUi?, onOpenPage: (String) -> Unit, modifier: Modifier = Modifier) {
    if (!LocalWebViews.current) {
        Box(modifier.fillMaxSize().background(Palette.Background).testTag("learn-placeholder")) {
            Text(page?.markdown ?: "Choose a page.", style = Type.Body, modifier = Modifier.testTag("learn-text"))
        }
        return
    }
    var view by remember { mutableStateOf<WebView?>(null) }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(page, ready, view) {
        val v = view
        if (ready && v != null && page != null) v.evaluateJavascript("render(${JSONObject.quote(page.markdown)})", null)
    }
    DisposableEffect(Unit) {
        onDispose { view?.destroy() }
    }
    AndroidView(
        modifier = modifier.fillMaxSize().testTag("learn"),
        factory = { ctx ->
            safeWebView(ctx, LocalOnlyClient(onLoaded = { ready = true }, onLink = onOpenPage)).also {
                view = it
                it.loadUrl("file:///android_asset/learn/page.html")
            }
        },
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
