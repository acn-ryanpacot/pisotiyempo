package com.pisotiyempo.data.remote

import com.pisotiyempo.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, val code: String, message: String) : Exception(message)

val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/** Client for the Piso Tiyempo backend (see backend/). */
class PisoApi(
    private val baseUrl: String = BuildConfig.API_BASE_URL.trimEnd('/'),
    private val appKey: String = BuildConfig.APP_KEY,
    private val http: OkHttpClient = defaultClient(),
) {
    suspend fun status(): StatusDto = get("/v1/status")
    suspend fun latest(): SourcedDto<SnapshotDto> = get("/v1/eod/latest")
    suspend fun listings(): SourcedDto<List<ListingDto>> = get("/v1/listings")
    suspend fun history(symbol: String, from: String): SourcedDto<List<BarDto>> = get("/v1/history/$symbol?from=$from")
    suspend fun psei(from: String): SourcedDto<List<BarDto>> = get("/v1/psei?from=$from")
    suspend fun dividends(symbol: String): SourcedDto<List<DividendDto>> = get("/v1/dividends/$symbol")
    suspend fun disclosures(symbol: String): SourcedDto<List<DisclosureDto>> = get("/v1/disclosures/$symbol")
    suspend fun foreignFlow(): SourcedDto<ForeignFlowDto> = get("/v1/foreign-flow")
    suspend fun brief(): BriefResponse = get("/v1/ai/brief")
    suspend fun analyze(request: AnalyzeRequest): AnalyzeResponse = post("/v1/ai/analyze", ApiJson.encodeToString(AnalyzeRequest.serializer(), request))
    suspend fun summarizeDisclosure(request: DisclosureRequest): DisclosureSummaryResponse =
        post("/v1/ai/disclosure", ApiJson.encodeToString(DisclosureRequest.serializer(), request))

    private suspend inline fun <reified T> get(path: String): T = call(Request.Builder().url(baseUrl + path).get())

    private suspend inline fun <reified T> post(path: String, body: String): T =
        call(Request.Builder().url(baseUrl + path).post(body.toRequestBody(JSON_TYPE)))

    private suspend inline fun <reified T> call(builder: Request.Builder): T {
        val text = withContext(Dispatchers.IO) {
            http.newCall(builder.header("X-App-Key", appKey).build()).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    val err = runCatching { ApiJson.decodeFromString<ErrorDto>(body) }.getOrNull()
                    throw ApiException(res.code, err?.error ?: "http_${res.code}", err?.message ?: "HTTP ${res.code}")
                }
                body
            }
        }
        return ApiJson.decodeFromString<T>(text)
    }

    companion object {
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS) // AI calls can take a while
            .build()
    }
}

/** Direct fallback to the phisix community feed when the backend can't be reached. */
class PhisixClient(private val http: OkHttpClient = PisoApi.defaultClient()) {
    suspend fun snapshot(): PhisixResponse = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://phisix-api3.appspot.com/stocks.json").header("User-Agent", "PisoTiyempo/1.0 (Android)").build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw ApiException(res.code, "phisix_${res.code}", "phisix returned HTTP ${res.code}")
            ApiJson.decodeFromString<PhisixResponse>(res.body?.string().orEmpty())
        }
    }
}
