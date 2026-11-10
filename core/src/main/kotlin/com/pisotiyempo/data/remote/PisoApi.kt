package com.pisotiyempo.data.remote

import com.pisotiyempo.sync.LinkCode
import com.pisotiyempo.sync.SyncPullResponse
import com.pisotiyempo.sync.SyncPushRequest
import com.pisotiyempo.sync.SyncPushResponse
import com.pisotiyempo.sync.SyncTransport
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

/** Supplies the Play Integrity session token sent with each request (see IntegritySession). */
interface SessionProvider {
    /** A valid session token, or null to send the request without one. */
    suspend fun token(api: PisoApi): String?

    suspend fun invalidate()
}

/**
 * Client for the Piso Tiyempo backend (see backend/). Shared by the Android and desktop apps; each passes in
 * its own server address and app key.
 */
class PisoApi(
    baseUrl: String,
    private val appKey: String,
    private val http: OkHttpClient = defaultClient(),
    private val sessions: SessionProvider? = null,
) {
    private val baseUrl = baseUrl.trimEnd('/')

    suspend fun status(): StatusDto = get("/v1/status")
    suspend fun calendar(from: String): CalendarDto = get("/v1/calendar?from=$from")
    suspend fun latest(): SourcedDto<SnapshotDto> = get("/v1/eod/latest")
    suspend fun listings(): SourcedDto<List<ListingDto>> = get("/v1/listings")
    suspend fun history(symbol: String, from: String): SourcedDto<List<BarDto>> = get("/v1/history/$symbol?from=$from")
    suspend fun psei(from: String): SourcedDto<List<BarDto>> = get("/v1/psei?from=$from")
    suspend fun dividends(symbol: String): SourcedDto<List<DividendDto>> = get("/v1/dividends/$symbol")
    suspend fun disclosures(symbol: String): SourcedDto<List<DisclosureDto>> = get("/v1/disclosures/$symbol")
    suspend fun foreignFlow(): SourcedDto<ForeignFlowDto> = get("/v1/foreign-flow")
    suspend fun brief(lang: String = "en"): BriefResponse = get("/v1/ai/brief?lang=$lang")
    suspend fun analyze(request: AnalyzeRequest): AnalyzeResponse = post("/v1/ai/analyze", ApiJson.encodeToString(AnalyzeRequest.serializer(), request))
    suspend fun journalReview(request: JournalReviewRequest): JournalReviewResponse =
        post("/v1/ai/journal-review", ApiJson.encodeToString(JournalReviewRequest.serializer(), request))
    suspend fun summarizeDisclosure(request: DisclosureRequest): DisclosureSummaryResponse =
        post("/v1/ai/disclosure", ApiJson.encodeToString(DisclosureRequest.serializer(), request))

    // ---- Encrypted sync (see com.pisotiyempo.sync). The server stores only ciphertext. ----

    suspend fun syncPush(groupId: String, token: String, request: SyncPushRequest): SyncPushResponse =
        call(
            Request.Builder().url("$baseUrl/v1/sync/push").header("X-Sync-Group", groupId).header("X-Sync-Token", token)
                .post(ApiJson.encodeToString(SyncPushRequest.serializer(), request).toRequestBody(JSON_TYPE)),
        )

    suspend fun syncPull(groupId: String, token: String, since: Long): SyncPullResponse =
        call(Request.Builder().url("$baseUrl/v1/sync/pull?since=$since").header("X-Sync-Group", groupId).header("X-Sync-Token", token).get())

    /** Deletes the group's encrypted data from the server ("unlink all devices"). */
    suspend fun syncDelete(groupId: String, token: String): Unit =
        call<SyncDeleteResponse>(Request.Builder().url("$baseUrl/v1/sync/group").header("X-Sync-Group", groupId).header("X-Sync-Token", token).delete()).let { }

    /** Exchanges a Play Integrity token for a session token. Sent without a session, since it creates one. */
    suspend fun createSession(request: SessionRequest): SessionDto =
        post("/v1/auth/session", ApiJson.encodeToString(SessionRequest.serializer(), request), authenticated = false)

    private suspend inline fun <reified T> get(path: String): T = call(Request.Builder().url(baseUrl + path).get())

    private suspend inline fun <reified T> post(path: String, body: String, authenticated: Boolean = true): T =
        call(Request.Builder().url(baseUrl + path).post(body.toRequestBody(JSON_TYPE)), authenticated)

    private suspend inline fun <reified T> call(builder: Request.Builder, authenticated: Boolean = true): T {
        val text = try {
            execute(builder, authenticated)
        } catch (e: ApiException) {
            // Sessions expire or get revoked: fetch a fresh one and retry once.
            if (!authenticated || sessions == null || e.status != 401 || e.code != "integrity_required") throw e
            sessions.invalidate()
            execute(builder, authenticated)
        }
        return ApiJson.decodeFromString<T>(text)
    }

    private suspend fun execute(builder: Request.Builder, authenticated: Boolean): String {
        builder.header("X-App-Key", appKey)
        if (authenticated) sessions?.token(this)?.let { builder.header("Authorization", "Bearer $it") }
        return withContext(Dispatchers.IO) {
            http.newCall(builder.build()).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    val err = runCatching { ApiJson.decodeFromString<ErrorDto>(body) }.getOrNull()
                    throw ApiException(res.code, err?.error ?: "http_${res.code}", err?.message ?: "HTTP ${res.code}")
                }
                body
            }
        }
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

@kotlinx.serialization.Serializable
data class SyncDeleteResponse(val ok: Boolean)

/** [SyncTransport] over the Piso Tiyempo backend. */
class PisoSyncTransport(private val api: PisoApi) : SyncTransport {
    override suspend fun push(link: LinkCode, records: List<com.pisotiyempo.sync.SyncBlob>): SyncPushResponse =
        api.syncPush(link.groupId, link.authToken, SyncPushRequest(records))

    override suspend fun pull(link: LinkCode, since: Long): SyncPullResponse = api.syncPull(link.groupId, link.authToken, since)
}
