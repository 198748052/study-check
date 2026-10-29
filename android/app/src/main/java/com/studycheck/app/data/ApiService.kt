package com.studycheck.app.data

import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.DELETE
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Streaming
import retrofit2.http.Query
import java.time.ZoneId
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

interface ApiService {
    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginReq): AuthResp

    @POST("api/v1/auth/register")
    suspend fun register(@Body body: RegisterReq): AuthResp

    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshReq): AuthResp

    @POST("api/v1/auth/logout")
    suspend fun logout(@Body body: RefreshReq)

    @GET("api/v1/me")
    suspend fun me(): UserDto

    @PATCH("api/v1/me")
    suspend fun updateMe(@Body body: UpdateMeReq): UserDto

    @GET("api/v1/health")
    suspend fun health(): HealthDto

    @GET("api/v1/checkins/today")
    suspend fun today(): TodayResp

    @GET("api/v1/stats")
    suspend fun stats(): StatsDto

    @GET("api/v1/checkins")
    suspend fun list(
        @Query("month") month: String? = null,
        @Query("limit") limit: Int = 60,
        @Query("offset") offset: Int = 0,
    ): ListResp

    /** 手动修正进/出馆时间 */
    @PATCH("api/v1/checkins/{id}")
    suspend fun patchCheckin(
        @Path("id") id: Long,
        @Body body: PatchCheckinReq,
    ): CheckinDto

    // ---------------- 数据导出 ----------------

    @Streaming
    @GET("api/v1/export/checkins.csv")
    suspend fun exportCheckins(): ResponseBody

    @Streaming
    @GET("api/v1/export/entries.csv")
    suspend fun exportNotes(): ResponseBody

    @Streaming
    @GET("api/v1/export/photos.zip")
    suspend fun exportPhotos(): ResponseBody

    // ---------------- 每日详情（多张连拍 + 笔记） ----------------

    @Multipart
    @POST("api/v1/entries")
    suspend fun createEntries(
        @Part photos: List<MultipartBody.Part>,
        @Part("note") note: RequestBody,
        @Part("day") day: RequestBody,
        @Header("Idempotency-Key") idemKey: String,
    ): EntryCreateResp

    @GET("api/v1/entries")
    suspend fun listEntries(
        @Query("day") day: String? = null,
        @Query("limit") limit: Int = 200,
    ): EntryListResp

    @DELETE("api/v1/entries/{id}")
    suspend fun deleteEntry(@Path("id") id: Long)

    @Multipart
    @POST("api/v1/checkins/in")
    suspend fun checkIn(
        @Part photo: MultipartBody.Part,
        @Part("note") note: RequestBody,
        @Part("place") place: RequestBody,
        @Part("day") day: RequestBody,
        @Part("taken_at") takenAt: RequestBody,
        @Header("Idempotency-Key") idemKey: String,
    ): CheckinDto

    @Multipart
    @POST("api/v1/checkins/out")
    suspend fun checkOut(
        @Part photo: MultipartBody.Part,
        @Part("note") note: RequestBody,
        @Part("place") place: RequestBody,
        @Part("day") day: RequestBody,
        @Part("taken_at") takenAt: RequestBody,
        @Header("Idempotency-Key") idemKey: String,
    ): CheckinDto
}

/** 把 Retrofit 的相对地址重写到用户配置的服务器地址（支持带路径前缀）。 */
class BaseUrlInterceptor(private val store: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val base = store.baseUrl.ifBlank { TokenStore.DEFAULT_BASE_URL }
        // 没有默认地址（自建部署场景）时给出可读提示，而不是一句 Connection refused
        if (base.isBlank()) throw java.io.IOException("还没有设置服务器地址，请先在登录页填写")
        val baseUrl = base.toHttpUrlOrNull() ?: return chain.proceed(request)

        val builder = baseUrl.newBuilder()
        request.url.pathSegments.forEach { builder.addPathSegment(it) }
        // 保留查询参数
        request.url.query?.let { builder.encodedQuery(it) }
        val newUrl = builder.build()

        return chain.proceed(request.newBuilder().url(newUrl).build())
    }
}

class AuthInterceptor(private val store: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder()
        store.accessToken?.takeIf { it.isNotBlank() }?.let {
            if (chain.request().header("Authorization") == null) {
                builder.header("Authorization", "Bearer $it")
            }
        }
        // 让服务端按手机本地时区判定"哪一天"
        if (chain.request().header("X-Timezone") == null) {
            builder.header("X-Timezone", ZoneId.systemDefault().id)
        }
        builder.header("X-Device-Name", "android-${android.os.Build.MODEL}")
        return chain.proceed(builder.build())
    }
}

/**
 * 401 时自动用 refresh token 换新 access token 并重放请求。
 * 弱网/并发场景用锁保证只刷新一次。
 */
class TokenAuthenticator(
    private val store: TokenStore,
    private val bareClient: OkHttpClient,
) : okhttp3.Authenticator {

    private val lock = ReentrantLock()

    override fun authenticate(route: okhttp3.Route?, response: Response): Request? {
        if (response.request.url.encodedPath.endsWith("/auth/refresh")) return null
        if (response.priorResponse != null && response.priorResponse!!.priorResponse != null) return null

        val failedToken = response.request.header("Authorization")?.removePrefix("Bearer ")

        lock.withLock {
            // 另一个线程已经刷新过了，直接用新的重试
            val current = store.accessToken
            if (!current.isNullOrBlank() && current != failedToken) {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $current")
                    .build()
            }
            val refresh = store.refreshToken ?: return null
            val newToken = tryRefresh(refresh) ?: return null
            return response.request.newBuilder()
                .header("Authorization", "Bearer $newToken")
                .build()
        }
    }

    private fun tryRefresh(refreshToken: String): String? {
        val base = store.baseUrl
        val baseUrl = base.toHttpUrlOrNull() ?: return null
        val url = baseUrl.newBuilder().addPathSegments("api/v1/auth/refresh").build()
        val body = AppJson.encodeToString(RefreshReq.serializer(), RefreshReq(refreshToken))
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder().url(url).post(body).build()
        return try {
            bareClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val text = resp.body?.string() ?: return null
                val auth = AppJson.decodeFromString(AuthResp.serializer(), text)
                store.saveSession(auth.token, auth.refreshToken, expiresAtMillis(auth.expiresAt))
                auth.token
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun expiresAtMillis(iso: String): Long = try {
        java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    } catch (t: Throwable) {
        0L
    }
}

/** 从 HttpException 的响应体里取出服务端错误码，便于 UI 精准提示。 */
fun parseApiError(code: Int, body: String?): String {
    if (body.isNullOrBlank()) return "请求失败（$code）"
    return try {
        val obj = AppJson.parseToJsonElement(body) as JsonObject
        val err = obj["error"] as? JsonObject
        err?.get("message")?.jsonPrimitive?.content ?: "请求失败（$code）"
    } catch (t: Throwable) {
        "请求失败（$code）"
    }
}

/** 构造 Retrofit + OkHttp（DI 里只用一次）。 */
fun buildApi(store: TokenStore): ApiService {
    val bare = OkHttpClient.Builder()
        .connectTimeout(java.util.concurrent.TimeUnit.SECONDS.toMillis(15), java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(java.util.concurrent.TimeUnit.SECONDS.toMillis(60), java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    val client = bare.newBuilder()
        .addInterceptor(BaseUrlInterceptor(store))
        .addInterceptor(AuthInterceptor(store))
        .authenticator(TokenAuthenticator(store, bare))
        .build()

    return Retrofit.Builder()
        .baseUrl("http://localhost/")
        .client(client)
        .addConverterFactory(AppJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(ApiService::class.java)
}
