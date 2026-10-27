package com.pisotiyempo.data.remote

import kotlinx.serialization.Serializable

// Mirrors the JSON returned by the Piso Tiyempo backend (backend/src).

@Serializable
data class SourcedDto<T>(
    val source: String,
    val fetchedAt: String,
    val notes: List<String> = emptyList(),
    val data: T,
)

@Serializable
data class BarDto(
    val date: String,
    val open: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val close: Double,
    val volume: Long? = null,
)

@Serializable
data class QuoteDto(
    val symbol: String,
    val name: String? = null,
    val date: String,
    val open: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val close: Double,
    val volume: Long? = null,
    val changePct: Double? = null,
)

@Serializable
data class SnapshotDto(val date: String, val quotes: List<QuoteDto>)

@Serializable
data class ListingDto(val symbol: String, val name: String, val type: String? = null)

@Serializable
data class DividendDto(
    val symbol: String,
    val type: String,
    val amount: Double? = null,
    val rateText: String,
    val exDate: String? = null,
    val recordDate: String? = null,
    val paymentDate: String? = null,
)

@Serializable
data class DisclosureDto(
    val id: String,
    val symbol: String,
    val title: String,
    val announcedAt: String,
    val formNumber: String = "",
    val circular: String = "",
    val url: String,
)

@Serializable
data class SymbolFlowDto(val symbol: String, val netValue: Double)

@Serializable
data class ForeignFlowDto(
    val date: String,
    val buyValue: Double,
    val sellValue: Double,
    val netValue: Double,
    val bySymbol: List<SymbolFlowDto> = emptyList(),
)

@Serializable
data class StatusDto(val eodhdConfigured: Boolean, val aiEnabled: Boolean, val today: String)

@Serializable
data class BriefDto(
    val headline: String,
    val mood: String,
    val overview: String,
    val highlights: List<String>,
    val risks: List<String>,
    val watchTomorrow: List<String>,
)

@Serializable
data class BriefResponse(val date: String, val model: String, val generatedAt: String, val brief: BriefDto)

@Serializable
data class PatternFeatureDto(val name: String, val direction: String, val confidence: Double, val detail: String)

@Serializable
data class LevelsDto(val support: List<Double>, val resistance: List<Double>)

@Serializable
data class BacktestFeatureDto(val signal: String, val winRate: Double, val samples: Int, val avgReturnPct: Double)

@Serializable
data class AnalyzeRequest(
    val symbol: String,
    val name: String,
    val asOf: String,
    val close: Double,
    val changePct: Double?,
    val score: Int,
    val indicators: Map<String, Double?>,
    val patterns: List<PatternFeatureDto>,
    val levels: LevelsDto,
    val backtest: List<BacktestFeatureDto>,
    val signals: List<String>,
)

@Serializable
data class AiAnalysisDto(
    val headline: String,
    val oneLiner: String,
    val summary: String,
    val bullCase: List<String>,
    val bearCase: List<String>,
    val keyLevels: String,
    val riskNote: String,
    val stance: String,
    val confidence: Int,
)

@Serializable
data class AnalyzeResponse(val symbol: String, val asOf: String, val model: String, val analysis: AiAnalysisDto)

@Serializable
data class DisclosureRequest(val id: String, val symbol: String)

@Serializable
data class DisclosureSummaryDto(val summary: String, val impact: String, val keyPoints: List<String>)

@Serializable
data class DisclosureSummaryResponse(val id: String, val model: String, val summary: DisclosureSummaryDto)

@Serializable
data class ErrorDto(val error: String, val message: String = "")

// phisix-api3.appspot.com, used directly by the app only when our backend is unreachable.
@Serializable
data class PhisixPrice(val currency: String = "PHP", val amount: Double)

@Serializable
data class PhisixStock(val name: String, val price: PhisixPrice, val percentChange: Double? = null, val volume: Long? = null, val symbol: String)

@Serializable
data class PhisixResponse(val stocks: List<PhisixStock>, val as_of: String)
