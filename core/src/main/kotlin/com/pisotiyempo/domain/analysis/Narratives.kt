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
 * engine's numbers into plain-language text with the same structure as the AI output, in English,
 * Filipino or Taglish.
 */
object RuleBasedAnalyst {

    private fun peso(v: Double) = "₱" + String.format(Locale.US, if (v >= 100) "%,.2f" else if (v >= 1) "%.2f" else "%.4f", v)
    private fun num(v: Double, pattern: String) = String.format(Locale.US, pattern, v)

    fun stock(a: StockAnalysis, lang: String = "en"): Narrative {
        val p = Phrases.of(lang)
        val bullish = a.patterns.filter { it.direction == Direction.BULLISH }
        val bearish = a.patterns.filter { it.direction == Direction.BEARISH }
        val trend = a.components["Trend"] ?: 0.0
        val trendWord = when {
            trend > 0.5 -> p.firmUp
            trend > 0.1 -> p.mildUp
            trend < -0.5 -> p.firmDown
            trend < -0.1 -> p.mildDown
            else -> p.sideways
        }
        val topPattern = a.patterns.firstOrNull { it.direction != Direction.NEUTRAL }
        val rsi = a.indicators["rsi14"]
        val keyLevels = buildString {
            if (a.supports.isNotEmpty()) append(p.support(a.supports.joinToString { peso(it.price) }))
            if (a.resistances.isNotEmpty()) append(p.resistance(a.resistances.joinToString { peso(it.price) }))
            a.plan?.let { append(p.invalidated(peso(it.stop))) }
        }.ifBlank { p.noLevels }
        val riskNote = buildString {
            if (!a.isLiquid) append(p.thin(peso(a.avgDailyValue)))
            if (!a.hasOhlc) append(p.noOhlc)
            append(p.probabilities)
        }
        return Narrative(
            headline = "${a.symbol}: ${p.headline(a.stance)}",
            oneLiner = p.oneLiner(trendWord, topPattern?.name, a.score),
            summary = buildString {
                append(p.closed(a.name, peso(a.close), trendWord))
                if (rsi != null) append(p.rsi(rsi.toInt(), if (rsi > 70) p.overbought else if (rsi < 30) p.oversold else p.neutral))
                if (topPattern != null) append(p.notable(topPattern.name, topPattern.detail))
                append(p.overall(p.stance(a.stance), a.score))
            },
            bullCase = bullish.take(4).map { "${it.name}: ${it.detail}" }.ifEmpty { listOf(p.noBullish) },
            bearCase = bearish.take(4).map { "${it.name}: ${it.detail}" }.ifEmpty { listOf(p.noBearish) },
            keyLevels = keyLevels,
            riskNote = riskNote,
            stance = a.stance,
            confidence = (40 + abs(a.score) / 2).coerceAtMost(85),
            source = NarrativeSource.RULES,
        )
    }

    fun market(stats: MarketStats, lang: String = "en"): MarketBrief {
        val p = Phrases.of(lang)
        val total = stats.advancers + stats.decliners + stats.unchanged
        val breadth = if (total > 0) (stats.advancers - stats.decliners).toDouble() / total else 0.0
        val pc = stats.pseiChangePct
        val close = stats.pseiClose
        val mood = when {
            (pc ?: 0.0) > 0.5 && breadth > 0.1 -> "BULLISH"
            (pc ?: 0.0) < -0.5 && breadth < -0.1 -> "BEARISH"
            else -> "NEUTRAL"
        }
        val pseiText = if (close != null && pc != null) p.psei(pc >= 0, num(abs(pc), "%.2f"), num(close, "%,.2f")) else p.noPsei
        return MarketBrief(
            date = stats.date,
            headline = p.marketHeadline(mood),
            mood = mood,
            overview = "$pseiText " + p.breadth(stats.advancers, stats.decliners, stats.unchanged, num(stats.totalValue / 1_000_000, "%,.0f")),
            highlights = stats.topGainers.take(3).map { p.up(it.first, num(it.second, "%.2f")) } +
                stats.topLosers.take(3).map { p.down(it.first, num(abs(it.second), "%.2f")) },
            risks = p.risks,
            watchTomorrow = p.watch,
            source = NarrativeSource.RULES,
        )
    }

    /**
     * The rules engine's sentences in each language. Pattern names and their details come from the
     * detectors and stay in English, as technical terms. Have a native speaker review the Filipino and
     * Taglish text before release.
     */
    private class Phrases(
        val firmUp: String,
        val mildUp: String,
        val firmDown: String,
        val mildDown: String,
        val sideways: String,
        val overbought: String,
        val oversold: String,
        val neutral: String,
        val noLevels: String,
        val noOhlc: String,
        val probabilities: String,
        val noBullish: String,
        val noBearish: String,
        val noPsei: String,
        val risks: List<String>,
        val watch: List<String>,
        val headline: (Stance) -> String,
        val stance: (Stance) -> String,
        val oneLiner: (trend: String, pattern: String?, score: Int) -> String,
        val closed: (name: String, price: String, trend: String) -> String,
        val rsi: (value: Int, state: String) -> String,
        val notable: (pattern: String, detail: String) -> String,
        val overall: (stance: String, score: Int) -> String,
        val support: (String) -> String,
        val resistance: (String) -> String,
        val invalidated: (String) -> String,
        val thin: (String) -> String,
        val psei: (up: Boolean, pct: String, close: String) -> String,
        val breadth: (adv: Int, dec: Int, unch: Int, valueM: String) -> String,
        val marketHeadline: (String) -> String,
        val up: (String, String) -> String,
        val down: (String, String) -> String,
    ) {
        companion object {
            fun of(lang: String): Phrases = when (lang) {
                "fil" -> FIL
                "taglish" -> TAGLISH
                else -> EN
            }

            private val EN = Phrases(
                firmUp = "a firm uptrend",
                mildUp = "a mild uptrend",
                firmDown = "a firm downtrend",
                mildDown = "a mild downtrend",
                sideways = "a sideways range",
                overbought = "overbought",
                oversold = "oversold",
                neutral = "neutral",
                noLevels = "Not enough history to map key levels yet.",
                noOhlc = "Recent data lacks intraday ranges, so candlestick patterns are unavailable. ",
                probabilities = "Signals are probabilities, not certainties; size positions so a stop-out is affordable.",
                noBullish = "No bullish patterns detected right now.",
                noBearish = "No bearish patterns detected right now.",
                noPsei = "PSEi data was not available.",
                risks = listOf("Thin trading in small caps can exaggerate moves.", "Watch foreign fund flows and peso movements for direction."),
                watch = listOf("Whether today's leaders hold their gains", "Breadth: more advancers than decliners would confirm strength"),
                headline = { s ->
                    when (s) {
                        Stance.STRONG_BUY -> "strong technical setup"
                        Stance.BUY -> "constructive, momentum improving"
                        Stance.HOLD -> "mixed signals, no clear edge"
                        Stance.REDUCE -> "weakening, consider trimming"
                        Stance.SELL -> "technically weak"
                    }
                },
                stance = { it.label.lowercase() },
                oneLiner = { t, pat, score -> t.replaceFirstChar { it.uppercase() } + (pat?.let { " with ${it.lowercase()}" } ?: "") + "; score $score." },
                closed = { n, price, t -> "$n closed at $price in $t. " },
                rsi = { v, st -> "RSI is $v, $st. " },
                notable = { pat, d -> "The most notable signal is a ${pat.lowercase()}: $d " },
                overall = { st, score -> "Overall the engine rates it $st with a score of $score out of ±100." },
                support = { "Support: $it. " },
                resistance = { "Resistance: $it. " },
                invalidated = { "A long setup would be invalidated below $it." },
                thin = { "Thinly traded (average $it a day): expect wide spreads and slippage. " },
                psei = { up, pct, close -> "The PSEi ${if (up) "gained" else "lost"} $pct% to $close." },
                breadth = { a, d, u, v -> "Advancers $a vs decliners $d ($u unchanged), with ₱${v}M traded." },
                marketHeadline = { m ->
                    when (m) {
                        "BULLISH" -> "Broad buying lifts the market"
                        "BEARISH" -> "Sellers in control across the board"
                        else -> "Mixed session, no clear direction"
                    }
                },
                up = { s, pct -> "$s up $pct%" },
                down = { s, pct -> "$s down $pct%" },
            )

            private val FIL = Phrases(
                firmUp = "matatag na pag-akyat",
                mildUp = "bahagyang pag-akyat",
                firmDown = "matatag na pagbaba",
                mildDown = "bahagyang pagbaba",
                sideways = "patagilid na galaw",
                overbought = "overbought (sobra na ang bili)",
                oversold = "oversold (sobra na ang benta)",
                neutral = "neutral",
                noLevels = "Kulang pa ang kasaysayan ng presyo para matukoy ang mahahalagang antas.",
                noOhlc = "Walang intraday range ang huling datos, kaya hindi magagamit ang mga candlestick pattern. ",
                probabilities = "Posibilidad lang ang mga senyales, hindi katiyakan. Bumili lang ng dami na kaya mong mawala kung tamaan ang stop.",
                noBullish = "Walang bullish na pattern sa ngayon.",
                noBearish = "Walang bearish na pattern sa ngayon.",
                noPsei = "Walang datos ng PSEi.",
                risks = listOf(
                    "Sa maliliit na kompanya, mas lumalaki ang galaw ng presyo dahil manipis ang kalakalan.",
                    "Bantayan ang pondo ng mga dayuhan at ang galaw ng piso.",
                ),
                watch = listOf(
                    "Kung mananatili ang pagtaas ng mga nanguna ngayon",
                    "Breadth: mas maraming tumaas kaysa bumaba ang magpapatunay ng lakas",
                ),
                headline = { s ->
                    when (s) {
                        Stance.STRONG_BUY -> "malakas ang teknikal na setup"
                        Stance.BUY -> "maganda ang lagay, lumalakas ang momentum"
                        Stance.HOLD -> "halo-halo ang senyales, walang malinaw na direksyon"
                        Stance.REDUCE -> "humihina, puwedeng magbawas"
                        Stance.SELL -> "mahina sa teknikal"
                    }
                },
                stance = { s ->
                    when (s) {
                        Stance.STRONG_BUY -> "malakas na bili"
                        Stance.BUY -> "bili"
                        Stance.HOLD -> "hawakan"
                        Stance.REDUCE -> "bawasan"
                        Stance.SELL -> "benta"
                    }
                },
                oneLiner = { t, pat, score -> t.replaceFirstChar { it.uppercase() } + (pat?.let { " na may $it" } ?: "") + "; iskor $score." },
                closed = { n, price, t -> "Nagsara ang $n sa $price, nasa $t. " },
                rsi = { v, st -> "Ang RSI ay $v, $st. " },
                notable = { pat, d -> "Ang pinakamahalagang senyales ay $pat: $d " },
                overall = { st, score -> "Sa kabuuan, \"$st\" ang rating ng engine, iskor $score sa ±100." },
                support = { "Suporta: $it. " },
                resistance = { "Resistensya: $it. " },
                invalidated = { "Mali na ang pagbili kung bababa ito sa $it." },
                thin = { "Manipis ang kalakalan (karaniwang $it kada araw): asahan ang malawak na spread. " },
                psei = { up, pct, close -> "Ang PSEi ay ${if (up) "tumaas" else "bumaba"} ng $pct% sa $close." },
                breadth = { a, d, u, v -> "$a ang tumaas, $d ang bumaba ($u walang galaw), at ₱${v}M ang naikalakal." },
                marketHeadline = { m ->
                    when (m) {
                        "BULLISH" -> "Malawak na pagbili ang nagpaakyat sa merkado"
                        "BEARISH" -> "Hawak ng mga nagbebenta ang merkado"
                        else -> "Halo-halong araw, walang malinaw na direksyon"
                    }
                },
                up = { s, pct -> "$s tumaas ng $pct%" },
                down = { s, pct -> "$s bumaba ng $pct%" },
            )

            private val TAGLISH = Phrases(
                firmUp = "malakas na uptrend",
                mildUp = "medyo pa-uptrend",
                firmDown = "malakas na downtrend",
                mildDown = "medyo pa-downtrend",
                sideways = "sideways lang",
                overbought = "overbought na",
                oversold = "oversold na",
                neutral = "neutral",
                noLevels = "Kulang pa ang history para ma-map ang key levels.",
                noOhlc = "Walang intraday range sa recent data, kaya hindi available ang candlestick patterns. ",
                probabilities = "Probabilities lang ang signals, hindi guarantee. I-size ang position para kaya mo kung ma-stop out.",
                noBullish = "Walang bullish pattern ngayon.",
                noBearish = "Walang bearish pattern ngayon.",
                noPsei = "Walang PSEi data.",
                risks = listOf("Sa small caps, mabilis lumaki ang galaw dahil manipis ang trading.", "Bantayan ang foreign flows at ang galaw ng piso."),
                watch = listOf("Kung mag-ho-hold ang gains ng mga leader ngayon", "Breadth: mas maraming advancers kaysa decliners ang magko-confirm ng lakas"),
                headline = { s ->
                    when (s) {
                        Stance.STRONG_BUY -> "malakas ang technical setup"
                        Stance.BUY -> "maganda ang setup, gumaganda ang momentum"
                        Stance.HOLD -> "mixed ang signals, walang clear edge"
                        Stance.REDUCE -> "humihina, consider mag-trim"
                        Stance.SELL -> "mahina technically"
                    }
                },
                stance = { it.label.lowercase() },
                oneLiner = { t, pat, score -> t.replaceFirstChar { it.uppercase() } + (pat?.let { " with ${it.lowercase()}" } ?: "") + "; score $score." },
                closed = { n, price, t -> "Nag-close ang $n sa $price, nasa $t. " },
                rsi = { v, st -> "Ang RSI ay $v, $st. " },
                notable = { pat, d -> "Pinaka-notable na signal: ${pat.lowercase()}. $d " },
                overall = { st, score -> "Overall, $st ang rating ng engine, score $score out of ±100." },
                support = { "Support: $it. " },
                resistance = { "Resistance: $it. " },
                invalidated = { "Invalid na ang long setup kapag bumaba sa $it." },
                thin = { "Manipis ang trading (average $it a day): expect malapad na spread at slippage. " },
                psei = { up, pct, close -> "Ang PSEi ay ${if (up) "nag-gain" else "nag-lose"} ng $pct%, nasa $close na." },
                breadth = { a, d, u, v -> "Advancers $a vs decliners $d ($u unchanged), ₱${v}M ang na-trade." },
                marketHeadline = { m ->
                    when (m) {
                        "BULLISH" -> "Malawakang buying, umakyat ang market"
                        "BEARISH" -> "Sellers ang may control ngayon"
                        else -> "Mixed na session, walang clear direction"
                    }
                },
                up = { s, pct -> "$s up $pct%" },
                down = { s, pct -> "$s down $pct%" },
            )
        }
    }
}
