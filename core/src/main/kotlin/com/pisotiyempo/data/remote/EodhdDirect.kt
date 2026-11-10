package com.pisotiyempo.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
private data class EodBar(
    val date: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    @SerialName("adjusted_close") val adjustedClose: Double = 0.0,
    val volume: Long = 0,
)

@Serializable
private data class EodBulkRow(
    val code: String,
    val name: String? = null,
    val date: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long = 0,
)

/**
 * Calls EODHD straight from the phone with the user's own key, so the data is licensed to them under
 * their own plan. Mirrors backend/src/providers/eodhd.ts.
 */
class EodhdDirect(private val apiKey: String, private val http: OkHttpClient = PisoApi.defaultClient()) {

    suspend fun latestDay(): List<QuoteDto> = get<List<EodBulkRow>>("eod-bulk-last-day/PSE", mapOf("filter" to "extended"))
        .filter { it.close > 0 }
        .map { QuoteDto(it.code, it.name, it.date, it.open, it.high, it.low, it.close, it.volume, changePct = null) }

    /** Daily bars rescaled by adjusted_close / close so splits and stock dividends don't look like crashes. */
    suspend fun history(symbol: String, from: String): List<BarDto> = get<List<EodBar>>("eod/$symbol.PSE", mapOf("from" to from, "order" to "a"))
        .filter { it.close > 0 }
        .map {
            val f = if (it.adjustedClose > 0) it.adjustedClose / it.close else 1.0
            BarDto(it.date, it.open * f, it.high * f, it.low * f, it.close * f, it.volume)
        }

    /** True when the key works and covers PSE data. */
    suspend fun check(from: String): Boolean = runCatching { history("JFC", from).isNotEmpty() }.getOrDefault(false)

    private suspend inline fun <reified T> get(path: String, params: Map<String, String>): T {
        val url = "https://eodhd.com/api/$path".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("api_token", apiKey)
            addQueryParameter("fmt", "json")
        }.build()
        val text = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).build()).execute().use { res ->
                // Never put the URL in the message: it contains the user's key.
                if (!res.isSuccessful) throw ApiException(res.code, "eodhd_${res.code}", "EODHD returned HTTP ${res.code}")
                res.body?.string().orEmpty()
            }
        }
        return ApiJson.decodeFromString<T>(text)
    }
}
