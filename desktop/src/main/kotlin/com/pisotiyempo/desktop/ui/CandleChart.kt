package com.pisotiyempo.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.pisotiyempo.domain.indicators.Indicators
import com.pisotiyempo.domain.model.Bar
import com.pisotiyempo.domain.model.Direction
import com.pisotiyempo.domain.model.PatternHit

data class ChartLine(val values: DoubleArray, val color: Color)

data class ChartLevel(val price: Double, val label: String, val color: Color)

/**
 * Candlestick chart drawn directly with Compose (no browser component needed on desktop): price pane with
 * moving averages, support/resistance lines and pattern arrows, a volume pane, and a hover readout.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CandleChart(
    bars: List<Bar>,
    modifier: Modifier = Modifier,
    lineMode: Boolean = false,
    overlays: List<ChartLine> = emptyList(),
    levels: List<ChartLevel> = emptyList(),
    patterns: List<PatternHit> = emptyList(),
) {
    if (bars.isEmpty()) return
    val textMeasurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.surfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    var hover by remember { mutableStateOf<Int?>(null) }

    Box(modifier) {
        Canvas(
            Modifier.fillMaxSize()
                .onPointerEvent(PointerEventType.Move) { e ->
                    val x = e.changes.first().position.x
                    val plotW = size.width - 70f
                    hover = ((x / plotW) * bars.size).toInt().takeIf { it in bars.indices }
                }
                .onPointerEvent(PointerEventType.Exit) { hover = null },
        ) {
            val axisW = 70f
            val plotW = size.width - axisW
            val volH = if (lineMode) 0f else size.height * 0.18f
            val priceH = size.height - volH - 18f
            val visibleLevels = levels.map { it.price }
            val hi = (bars.maxOf { it.high }).coerceAtLeast(visibleLevels.maxOrNull() ?: Double.MIN_VALUE)
            val lo = (bars.minOf { it.low }).coerceAtMost(visibleLevels.minOrNull() ?: Double.MAX_VALUE)
            val pad = (hi - lo) * 0.05 + 1e-9
            val top = hi + pad
            val bottom = lo - pad
            fun y(p: Double) = ((top - p) / (top - bottom) * priceH).toFloat()
            val step = plotW / bars.size
            fun x(i: Int) = i * step + step / 2

            // Grid and price axis
            for (k in 0..4) {
                val p = bottom + (top - bottom) * k / 4
                val yy = y(p)
                drawLine(gridColor, Offset(0f, yy), Offset(plotW, yy), 1f)
                drawText(textMeasurer, Fmt.num(p), Offset(plotW + 6f, yy - 8f), TextStyle(color = labelColor, fontSize = 10.sp))
            }

            if (lineMode) {
                val path = Path()
                bars.forEachIndexed { i, b -> if (i == 0) path.moveTo(x(i), y(b.close)) else path.lineTo(x(i), y(b.close)) }
                drawPath(path, accent, style = Stroke(2f))
            } else {
                val bodyW = (step * 0.7f).coerceAtLeast(1f)
                val maxVol = bars.maxOf { it.volume }.coerceAtLeast(1)
                bars.forEachIndexed { i, b ->
                    val color = if (b.close >= b.open) Gain else Loss
                    drawLine(color, Offset(x(i), y(b.high)), Offset(x(i), y(b.low)), 1f)
                    val top0 = y(maxOf(b.open, b.close))
                    val h = (y(minOf(b.open, b.close)) - top0).coerceAtLeast(1f)
                    drawRect(color, Offset(x(i) - bodyW / 2, top0), Size(bodyW, h))
                    val vh = (b.volume.toFloat() / maxVol) * (volH - 4f)
                    drawRect(color.copy(alpha = 0.4f), Offset(x(i) - bodyW / 2, size.height - 18f - vh), Size(bodyW, vh))
                }
            }

            overlays.forEach { line ->
                val path = Path()
                var started = false
                line.values.forEachIndexed { i, v ->
                    if (v.isNaN()) return@forEachIndexed
                    if (!started) { path.moveTo(x(i), y(v)); started = true } else path.lineTo(x(i), y(v))
                }
                drawPath(path, line.color, style = Stroke(1.5f))
            }

            levels.forEach { l ->
                val yy = y(l.price)
                drawLine(l.color, Offset(0f, yy), Offset(plotW, yy), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
                drawText(textMeasurer, "${l.label} ${Fmt.num(l.price)}", Offset(4f, yy - 14f), TextStyle(color = l.color, fontSize = 10.sp))
            }

            patterns.filter { it.endIndex in bars.indices && it.direction != Direction.NEUTRAL }.forEach { p ->
                val bull = p.direction == Direction.BULLISH
                val b = bars[p.endIndex]
                val tipY = if (bull) y(b.low) + 6f else y(b.high) - 6f
                val dir = if (bull) 1f else -1f
                val path = Path().apply {
                    moveTo(x(p.endIndex), tipY)
                    lineTo(x(p.endIndex) - 5f, tipY + 9f * dir)
                    lineTo(x(p.endIndex) + 5f, tipY + 9f * dir)
                    close()
                }
                drawPath(path, if (bull) Gain else Loss)
            }

            // Date labels: first, middle, last
            listOf(0, bars.size / 2, bars.size - 1).distinct().forEach { i ->
                drawText(textMeasurer, bars[i].date.toString(), Offset((x(i) - 30f).coerceIn(0f, plotW - 60f), size.height - 14f), TextStyle(color = labelColor, fontSize = 10.sp))
            }

            hover?.let { i ->
                val b = bars[i]
                drawLine(labelColor.copy(alpha = 0.5f), Offset(x(i), 0f), Offset(x(i), size.height - 18f), 1f)
                val text = "${b.date}  O ${Fmt.num(b.open)}  H ${Fmt.num(b.high)}  L ${Fmt.num(b.low)}  C ${Fmt.num(b.close)}  Vol ${Fmt.shares(b.volume)}"
                drawText(textMeasurer, text, Offset(4f, 2f), TextStyle(color = labelColor, fontSize = 11.sp))
            }
        }
    }
}

/** Standard overlays for the price pane. */
fun movingAverages(bars: List<Bar>): List<ChartLine> {
    val closes = Indicators.closes(bars)
    return listOf(
        ChartLine(Indicators.sma(closes, 20), Color(0xFFF4A259)),
        ChartLine(Indicators.sma(closes, 50), Color(0xFF5B8E7D)),
        ChartLine(Indicators.sma(closes, 200), Color(0xFF8E7DBE)),
    )
}
