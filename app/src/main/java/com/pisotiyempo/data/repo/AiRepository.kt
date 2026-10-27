package com.pisotiyempo.data.repo

import android.util.Log
import com.pisotiyempo.data.local.AppDatabase
import com.pisotiyempo.data.local.DisclosureEntity
import com.pisotiyempo.data.local.KvEntity
import com.pisotiyempo.data.remote.AiAnalysisDto
import com.pisotiyempo.data.remote.AnalyzeRequest
import com.pisotiyempo.data.remote.ApiJson
import com.pisotiyempo.data.remote.BacktestFeatureDto
import com.pisotiyempo.data.remote.DisclosureRequest
import com.pisotiyempo.data.remote.LevelsDto
import com.pisotiyempo.data.remote.PatternFeatureDto
import com.pisotiyempo.data.remote.PisoApi
import com.pisotiyempo.data.settings.SettingsStore
import com.pisotiyempo.domain.analysis.MarketBrief
import com.pisotiyempo.domain.analysis.MarketStats
import com.pisotiyempo.domain.analysis.Narrative
import com.pisotiyempo.domain.analysis.NarrativeSource
import com.pisotiyempo.domain.analysis.RuleBasedAnalyst
import com.pisotiyempo.domain.model.Stance
import com.pisotiyempo.domain.model.StockAnalysis

/**
 * AI text with an automatic fallback: Claude (via the backend) when it's switched on and reachable,
 * otherwise the on-device rules engine. Every result carries its [NarrativeSource] so the UI can label it.
 */
class AiRepository(
    private val db: AppDatabase,
    private val api: PisoApi,
    private val settings: SettingsStore,
) {
    suspend fun stockNarrative(a: StockAnalysis): Narrative {
        if (!settings.current().aiEnabled) return RuleBasedAnalyst.stock(a)
        val key = "ai:stock:${a.symbol}:${a.asOf}"
        db.kv().get(key)?.let { cached ->
            runCatching { ApiJson.decodeFromString(AiAnalysisDto.serializer(), cached.value) }.getOrNull()?.let { return it.toNarrative() }
        }
        return try {
            val res = api.analyze(a.toRequest())
            db.kv().put(KvEntity(key, ApiJson.encodeToString(AiAnalysisDto.serializer(), res.analysis), System.currentTimeMillis()))
            res.analysis.toNarrative()
        } catch (e: Exception) {
            Log.i(TAG, "AI analysis unavailable for ${a.symbol}, using rules: ${e.message}")
            RuleBasedAnalyst.stock(a)
        }
    }

    /** The day's brief is written once by Claude Opus on the backend; fall back to rules if it isn't for [stats]'s date. */
    suspend fun marketBrief(stats: MarketStats): MarketBrief {
        if (settings.current().aiEnabled) {
            try {
                val res = api.brief()
                if (res.date == stats.date) {
                    val b = res.brief
                    return MarketBrief(res.date, b.headline, b.mood, b.overview, b.highlights, b.risks, b.watchTomorrow, NarrativeSource.CLAUDE_OPUS)
                }
            } catch (e: Exception) {
                Log.i(TAG, "AI brief unavailable, using rules: ${e.message}")
            }
        }
        return RuleBasedAnalyst.market(stats)
    }

    /** Summarizes a disclosure with Claude and stores the result. Returns false when AI is unavailable. */
    suspend fun summarizeDisclosure(d: DisclosureEntity): Boolean {
        if (!settings.current().aiEnabled) return false
        return try {
            val res = api.summarizeDisclosure(DisclosureRequest(d.id, d.symbol)).summary
            db.disclosures().setSummary(d.id, res.summary, res.impact, res.keyPoints.joinToString("\n"))
            true
        } catch (e: Exception) {
            Log.i(TAG, "disclosure summary failed: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "AiRepository"
    }
}

private fun AiAnalysisDto.toNarrative() = Narrative(
    headline = headline,
    oneLiner = oneLiner,
    summary = summary,
    bullCase = bullCase,
    bearCase = bearCase,
    keyLevels = keyLevels,
    riskNote = riskNote,
    stance = runCatching { Stance.valueOf(stance) }.getOrDefault(Stance.HOLD),
    confidence = confidence,
    source = NarrativeSource.CLAUDE_HAIKU,
)

private fun StockAnalysis.toRequest() = AnalyzeRequest(
    symbol = symbol,
    name = name,
    asOf = asOf.toString(),
    close = close,
    changePct = changePct,
    score = score,
    indicators = indicators,
    patterns = patterns.take(12).map { PatternFeatureDto(it.name, it.direction.name, it.confidence, it.detail.take(200)) },
    levels = LevelsDto(supports.map { it.price }, resistances.map { it.price }),
    backtest = backtest.take(12).map { BacktestFeatureDto(it.signalName, it.winRate, it.samples, it.avgReturnPct) },
    signals = signals.take(15),
)
