package com.pisotiyempo.ui.chart

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.pisotiyempo.domain.indicators.Indicators
import com.pisotiyempo.domain.model.Bar
import com.pisotiyempo.domain.model.Direction
import com.pisotiyempo.domain.model.PatternHit
import com.pisotiyempo.ui.theme.LocalMarketColors
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class Overlay(val label: String) { SMA20("SMA 20"), SMA50("SMA 50"), SMA200("SMA 200"), BOLLINGER("Bollinger") }

data class ChartLevel(val price: Double, val title: String, val color: Color)

@Serializable private data class Candle(val time: String, val open: Double, val high: Double, val low: Double, val close: Double)
@Serializable private data class Point(val time: String, val value: Double, val color: String? = null)
@Serializable private data class LineSpec(val color: String, val data: List<Point>)
@Serializable private data class Marker(val time: String, val position: String, val color: String, val shape: String, val text: String)
@Serializable private data class PriceLineSpec(val price: Double, val color: String, val title: String)
@Serializable private data class Theme(val background: String, val text: String, val grid: String, val up: String, val down: String, val accent: String)
@Serializable private data class Payload(
    val theme: Theme,
    val mode: String,
    val candles: List<Candle>,
    val volume: List<Point>,
    val lines: List<LineSpec>,
    val markers: List<Marker>,
    val priceLines: List<PriceLineSpec>,
)

private val ChartJson = Json { explicitNulls = false }

private fun Color.hex(): String = String.format("#%06X", 0xFFFFFF and toArgb())

/** Holds the WebView and whether chart.html has finished loading, so data sent early isn't lost. */
private class ChartHost(val webView: WebView) {
    var loaded = false
    var pending: String? = null

    fun send(json: String) {
        if (loaded) webView.evaluateJavascript("render($json)", null) else pending = json
    }
}

/**
 * Candlestick (or line) chart drawn by TradingView Lightweight Charts inside a WebView, with indicator
 * overlays, pattern markers and support/resistance lines.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PriceChart(
    bars: List<Bar>,
    modifier: Modifier = Modifier,
    lineMode: Boolean = false,
    showVolume: Boolean = true,
    overlays: Set<Overlay> = emptySet(),
    patterns: List<PatternHit> = emptyList(),
    levels: List<ChartLevel> = emptyList(),
) {
    val scheme = MaterialTheme.colorScheme
    val market = LocalMarketColors.current
    val theme = Theme(scheme.surface.hex(), scheme.onSurfaceVariant.hex(), scheme.surfaceVariant.hex(), market.gain.hex(), market.loss.hex(), scheme.primary.hex())

    val json = remember(bars, lineMode, showVolume, overlays, patterns, levels, theme) {
        buildPayload(bars, theme, lineMode, showVolume, overlays, patterns, levels)
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val webView = WebView(context)
            val host = ChartHost(webView)
            webView.settings.javaScriptEnabled = true
            webView.settings.allowFileAccess = false
            webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    host.loaded = true
                    host.pending?.let { host.send(it) }
                    host.pending = null
                }
            }
            webView.tag = host
            webView.loadUrl("file:///android_asset/chart/chart.html")
            webView
        },
        update = { webView -> (webView.tag as ChartHost).send(json) },
    )
}

private fun buildPayload(
    bars: List<Bar>,
    theme: Theme,
    lineMode: Boolean,
    showVolume: Boolean,
    overlays: Set<Overlay>,
    patterns: List<PatternHit>,
    levels: List<ChartLevel>,
): String {
    val times = bars.map { it.date.toString() }
    val closes = Indicators.closes(bars)
    fun series(values: DoubleArray, color: String) = LineSpec(color, values.indices.filter { !values[it].isNaN() }.map { Point(times[it], values[it]) })

    val lines = buildList {
        if (Overlay.SMA20 in overlays) add(series(Indicators.sma(closes, 20), "#F4A259"))
        if (Overlay.SMA50 in overlays) add(series(Indicators.sma(closes, 50), "#5B8E7D"))
        if (Overlay.SMA200 in overlays) add(series(Indicators.sma(closes, 200), "#8E7DBE"))
        if (Overlay.BOLLINGER in overlays) {
            val b = Indicators.bollinger(closes)
            add(series(b.upper, "#9AA5B1"))
            add(series(b.lower, "#9AA5B1"))
        }
    }
    val markers = patterns
        .filter { it.endIndex in bars.indices && it.direction != Direction.NEUTRAL }
        .sortedBy { it.endIndex }
        .map {
            val bull = it.direction == Direction.BULLISH
            Marker(times[it.endIndex], if (bull) "belowBar" else "aboveBar", if (bull) theme.up else theme.down, if (bull) "arrowUp" else "arrowDown", it.name)
        }
    val payload = Payload(
        theme = theme,
        mode = if (lineMode) "line" else "candle",
        candles = bars.map { Candle(it.date.toString(), it.open, it.high, it.low, it.close) },
        volume = if (showVolume && !lineMode) bars.map { Point(it.date.toString(), it.volume.toDouble(), if (it.close >= it.open) theme.up + "66" else theme.down + "66") } else emptyList(),
        lines = lines,
        markers = markers,
        priceLines = levels.map { PriceLineSpec(it.price, it.color.hex(), it.title) },
    )
    return ChartJson.encodeToString(Payload.serializer(), payload)
}
