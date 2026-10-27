package com.pisotiyempo.domain.analysis

import com.pisotiyempo.domain.model.Direction
import com.pisotiyempo.domain.model.Stance
import com.pisotiyempo.domain.model.StockAnalysis
import java.util.Locale
import kotlin.math.abs

/** Where a piece of analysis text came from, shown on every AI card. */
enum class NarrativeSource(val label: String) { CLAUDE_HAIKU("AI · Claude Haiku"), CLAUDE_OPUS("AI · Claude Opus"), RULES("Rules engine") }

data class Narrative(
    val headline: String,
    val oneLiner: String,
    val summary: String,
    val bullCase: List<String>,
    val bearCase: List<String>,
    val keyLevels: String,
    val riskNote: String,
    val stance: Stance,
    val confidence: Int,
    val source: NarrativeSource,
)

data class MarketStats(
    val date: String,
    val pseiClose: Double?,
    val pseiChangePct: Double?,
    val advancers: Int,
    val decliners: Int,
    val unchanged: Int,
    val totalValue: Double,
    val topGainers: List<Pair<String, Double>>,
    val topLosers: List<Pair<String, Double>>,
)

data class MarketBrief(
    val date: String,
    val headline: String,
    val mood: String,
    val overview: String,
    val highlights: List<String>,
    val risks: List<String>,
    val watchTomorrow: List<String>,
    val source: NarrativeSource,
)

/**
 * The fallback writer used when Claude is unavailable (no key, outage, refusal, offline). It turns the
 * engine's numbers into plain-language text with the same structure as the AI output.
 */
object RuleBasedAnalyst {

    private fun peso(v: Double) = "₱" + String.format(Locale.US, if (v >= 100) "%,.2f" else if (v >= 1) "%.2f" else "%.4f", v)

    fun stock(a: StockAnalysis): Narrative {
        val bullish = a.patterns.filter { it.direction == Direction.BULLISH }
        val bearish = a.patterns.filter { it.direction == Direction.BEARISH }
        val trend = a.components["Trend"] ?: 0.0
        val trendWord = when {
            trend > 0.5 -> "a firm uptrend"
            trend > 0.1 -> "a mild uptrend"
            trend < -0.5 -> "a firm downtrend"
            trend < -0.1 -> "a mild downtrend"
            else -> "a sideways range"
        }
        val topPattern = a.patterns.firstOrNull { it.direction != Direction.NEUTRAL }
        val headline = when (a.stance) {
            Stance.STRONG_BUY -> "${a.symbol}: strong technical setup"
            Stance.BUY -> "${a.symbol}: constructive, momentum improving"
            Stance.HOLD -> "${a.symbol}: mixed signals, no clear edge"
            Stance.REDUCE -> "${a.symbol}: weakening, consider trimming"
            Stance.SELL -> "${a.symbol}: technically weak"
        }
        val oneLiner = buildString {
            append(trendWord.replaceFirstChar { it.uppercase() })
            if (topPattern != null) append(" with ${topPattern.name.lowercase()}")
            append("; score ${a.score}.")
        }
        val rsi = a.indicators["rsi14"]
        val summary = buildString {
            append("${a.name} closed at ${peso(a.close)} in $trendWord. ")
            if (rsi != null) append("RSI is ${rsi.toInt()}, ${if (rsi > 70) "overbought" else if (rsi < 30) "oversold" else "neutral"}. ")
            if (topPattern != null) append("The most notable signal is a ${topPattern.name.lowercase()}: ${topPattern.detail} ")
            append("Overall the engine rates it ${a.stance.label.lowercase()} with a score of ${a.score} out of ±100.")
        }
        val keyLevels = buildString {
            if (a.supports.isNotEmpty()) append("Support: ${a.supports.joinToString { peso(it.price) }}. ")
            if (a.resistances.isNotEmpty()) append("Resistance: ${a.resistances.joinToString { peso(it.price) }}. ")
            a.plan?.let { append("A long setup would be invalidated below ${peso(it.stop)}.") }
        }.ifBlank { "Not enough history to map key levels yet." }
        val riskNote = buildString {
            if (!a.isLiquid) append("Thinly traded (average ${peso(a.avgDailyValue)} a day): expect wide spreads and slippage. ")
            if (!a.hasOhlc) append("Recent data lacks intraday ranges, so candlestick patterns are unavailable. ")
            append("Signals are probabilities, not certainties; size positions so a stop-out is affordable.")
        }
        return Narrative(
            headline = headline,
            oneLiner = oneLiner,
            summary = summary,
            bullCase = bullish.take(4).map { "${it.name}: ${it.detail}" }.ifEmpty { listOf("No bullish patterns detected right now.") },
            bearCase = bearish.take(4).map { "${it.name}: ${it.detail}" }.ifEmpty { listOf("No bearish patterns detected right now.") },
            keyLevels = keyLevels,
            riskNote = riskNote,
            stance = a.stance,
            confidence = (40 + abs(a.score) / 2).coerceAtMost(85),
            source = NarrativeSource.RULES,
        )
    }

    fun market(stats: MarketStats): MarketBrief {
        val total = stats.advancers + stats.decliners + stats.unchanged
        val breadth = if (total > 0) (stats.advancers - stats.decliners).toDouble() / total else 0.0
        val pc = stats.pseiChangePct
        val mood = when {
            (pc ?: 0.0) > 0.5 && breadth > 0.1 -> "BULLISH"
            (pc ?: 0.0) < -0.5 && breadth < -0.1 -> "BEARISH"
            else -> "NEUTRAL"
        }
        val pseiText = if (stats.pseiClose != null && pc != null)
            "The PSEi ${if (pc >= 0) "gained" else "lost"} ${String.format(Locale.US, "%.2f", abs(pc))}% to ${String.format(Locale.US, "%,.2f", stats.pseiClose)}."
        else "PSEi data was not available."
        return MarketBrief(
            date = stats.date,
            headline = when (mood) {
                "BULLISH" -> "Broad buying lifts the market"
                "BEARISH" -> "Sellers in control across the board"
                else -> "Mixed session, no clear direction"
            },
            mood = mood,
            overview = "$pseiText Advancers ${stats.advancers} vs decliners ${stats.decliners} (${stats.unchanged} unchanged), with ₱${String.format(Locale.US, "%,.0f", stats.totalValue / 1_000_000)}M traded.",
            highlights = stats.topGainers.take(3).map { "${it.first} up ${String.format(Locale.US, "%.2f", it.second)}%" } +
                stats.topLosers.take(3).map { "${it.first} down ${String.format(Locale.US, "%.2f", abs(it.second))}%" },
            risks = listOf("Thin trading in small caps can exaggerate moves.", "Watch foreign fund flows and peso movements for direction."),
            watchTomorrow = listOf("Whether today's leaders hold their gains", "Breadth: more advancers than decliners would confirm strength"),
            source = NarrativeSource.RULES,
        )
    }
}
