package com.pisotiyempo.domain.analysis

import com.pisotiyempo.domain.model.Stance

/** One analyzed stock as the screener sees it. */
data class ScreenRow(
    val symbol: String,
    val name: String,
    val sector: String,
    val close: Double,
    val changePct: Double?,
    val score: Int,
    val stance: Stance,
    val rsi14: Double?,
    val aboveSma50: Boolean?,
    val aboveSma200: Boolean?,
    val patterns: List<String>,
    val bullishCount: Int,
    val bearishCount: Int,
    val volumeRatio: Double?,
    val return3mPct: Double?,
    val avgDailyValue: Double,
    val isLiquid: Boolean,
)

enum class ScreenSort(val label: String) { SCORE("Score"), CHANGE("Today's change"), VALUE("Value traded"), RETURN_3M("3-month return"), RSI("RSI") }

enum class PatternFilter(val label: String) { ANY("Any"), BULLISH("Has a bullish pattern"), BEARISH("Has a bearish pattern") }

/** Every filter is optional; null or empty means "don't filter on this". */
data class ScreenCriteria(
    val minScore: Int? = null,
    val maxScore: Int? = null,
    val stances: Set<Stance> = emptySet(),
    val sectors: Set<String> = emptySet(),
    val minRsi: Double? = null,
    val maxRsi: Double? = null,
    val aboveSma50: Boolean? = null,
    val aboveSma200: Boolean? = null,
    val patternFilter: PatternFilter = PatternFilter.ANY,
    /** Case-insensitive substring match against pattern names, e.g. "flag" or "double bottom". */
    val patternName: String = "",
    val minVolumeRatio: Double? = null,
    val minReturn3m: Double? = null,
    val maxReturn3m: Double? = null,
    val liquidOnly: Boolean = true,
    val sort: ScreenSort = ScreenSort.SCORE,
    val descending: Boolean = true,
)

/** Ready-made scans for common setups. */
enum class ScreenPreset(val label: String, val description: String, val criteria: ScreenCriteria) {
    STRONG_UPTREND(
        "Strong uptrend", "Above the 50- and 200-day averages with a buy score.",
        ScreenCriteria(minScore = 20, aboveSma50 = true, aboveSma200 = true),
    ),
    OVERSOLD(
        "Oversold bounce", "RSI under 35 while the long-term trend is still up.",
        ScreenCriteria(maxRsi = 35.0, aboveSma200 = true, sort = ScreenSort.RSI, descending = false),
    ),
    BREAKOUTS(
        "Breakouts", "Fresh bullish chart patterns or breakouts.",
        ScreenCriteria(patternFilter = PatternFilter.BULLISH, patternName = "breakout"),
    ),
    VOLUME_SURGE(
        "Volume surge", "Trading at least twice the usual volume today.",
        ScreenCriteria(minVolumeRatio = 2.0, sort = ScreenSort.VALUE),
    ),
    WEAKEST(
        "Weakest", "Sell-rated stocks below their 200-day average.",
        ScreenCriteria(maxScore = -20, aboveSma200 = false, sort = ScreenSort.SCORE, descending = false),
    ),
}

object Screener {

    fun matches(r: ScreenRow, c: ScreenCriteria): Boolean {
        if (c.liquidOnly && !r.isLiquid) return false
        if (c.minScore != null && r.score < c.minScore) return false
        if (c.maxScore != null && r.score > c.maxScore) return false
        if (c.stances.isNotEmpty() && r.stance !in c.stances) return false
        if (c.sectors.isNotEmpty() && r.sector !in c.sectors) return false
        // A stock without enough history for a reading fails any filter on that reading.
        if (c.minRsi != null && (r.rsi14 == null || r.rsi14 < c.minRsi)) return false
        if (c.maxRsi != null && (r.rsi14 == null || r.rsi14 > c.maxRsi)) return false
        if (c.aboveSma50 != null && r.aboveSma50 != c.aboveSma50) return false
        if (c.aboveSma200 != null && r.aboveSma200 != c.aboveSma200) return false
        when (c.patternFilter) {
            PatternFilter.ANY -> Unit
            PatternFilter.BULLISH -> if (r.bullishCount == 0) return false
            PatternFilter.BEARISH -> if (r.bearishCount == 0) return false
        }
        if (c.patternName.isNotBlank() && r.patterns.none { it.contains(c.patternName.trim(), ignoreCase = true) }) return false
        if (c.minVolumeRatio != null && (r.volumeRatio == null || r.volumeRatio < c.minVolumeRatio)) return false
        if (c.minReturn3m != null && (r.return3mPct == null || r.return3mPct < c.minReturn3m)) return false
        if (c.maxReturn3m != null && (r.return3mPct == null || r.return3mPct > c.maxReturn3m)) return false
        return true
    }

    fun run(rows: List<ScreenRow>, c: ScreenCriteria): List<ScreenRow> {
        val selector: (ScreenRow) -> Double? = when (c.sort) {
            ScreenSort.SCORE -> { r -> r.score.toDouble() }
            ScreenSort.CHANGE -> { r -> r.changePct }
            ScreenSort.VALUE -> { r -> r.avgDailyValue }
            ScreenSort.RETURN_3M -> { r -> r.return3mPct }
            ScreenSort.RSI -> { r -> r.rsi14 }
        }
        val matched = rows.filter { matches(it, c) }
        // Rows missing the sort value go last either way.
        val (withValue, without) = matched.partition { selector(it) != null }
        val sorted = if (c.descending) withValue.sortedByDescending { selector(it) } else withValue.sortedBy { selector(it) }
        return sorted + without.sortedBy { it.symbol }
    }
}
