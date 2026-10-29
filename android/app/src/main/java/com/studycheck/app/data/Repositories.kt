package com.studycheck.app.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val TEXT = "text/plain".toMediaType()

fun todayString(zone: ZoneId = ZoneId.systemDefault()): String =
    java.time.LocalDate.now(zone).format(DateTimeFormatter.ISO_LOCAL_DATE)

fun nowIso(): String = OffsetDateTime.now().toString()

@Singleton
class AuthRepository @Inject constructor(
    private val api: ApiService,
    private val store: TokenStore,
    @ApplicationContext private val context: Context,
) {
    val isLoggedIn: Boolean get() = store.isLoggedIn

    suspend fun login(username: String, password: String): Result<UserDto> = runCatching {
        val resp = api.login(LoginReq(username.trim(), password))
        persist(resp)
        resp.user
    }

    suspend fun register(username: String, password: String, nickname: String): Result<UserDto> = runCatching {
        val resp = api.register(RegisterReq(username.trim(), password, nickname.trim()))
        persist(resp)
        resp.user
    }

    suspend fun refreshProfile(): Result<UserDto> = runCatching { api.me() }

    suspend fun updateProfile(nickname: String?, goalMin: Int?): Result<UserDto> = runCatching {
        api.updateMe(UpdateMeReq(nickname = nickname, dailyGoalMin = goalMin))
    }

    suspend fun logout() {
        store.refreshToken?.let {
            runCatching { api.logout(RefreshReq(it)) }
        }
        store.clearSession()
    }

    fun savedGoal(): Int = store.savedGoal
    fun setSavedGoal(v: Int) { store.savedGoal = v }

    fun baseUrl(): String = store.baseUrl
    fun setBaseUrl(url: String) {
        store.baseUrl = if (url.endsWith("/")) url else "$url/"
    }

    private fun persist(resp: AuthResp) {
        val exp = runCatching { OffsetDateTime.parse(resp.expiresAt).toInstant().toEpochMilli() }
            .getOrDefault(0L)
        store.saveSession(resp.token, resp.refreshToken, exp)
        store.username = resp.user.username
        store.nickname = resp.user.nickname
        store.savedGoal = resp.user.dailyGoalMin
    }
}

@Singleton
class CheckinRepository @Inject constructor(
    private val api: ApiService,
    private val db: AppDatabase,
    private val store: TokenStore,
    @ApplicationContext private val context: Context,
) {
    private val checkinDao = db.checkinDao()
    private val pendingDao = db.pendingDao()

    fun observeDay(day: String): Flow<CheckinEntity?> = checkinDao.observeDay(day)
    fun observePending(day: String): Flow<List<PendingEntity>> = pendingDao.observeForDay(day)
    fun observePendingCount(): Flow<Int> = pendingDao.observeCount()
    fun observeRecent(limit: Int = 60): Flow<List<CheckinEntity>> = checkinDao.observeRecent(limit)
    fun observeMonth(prefix: String): Flow<List<CheckinEntity>> = checkinDao.observeMonth(prefix)

    /** 拉取服务端当日状态并写入本地缓存；同时把已完成的待上传项清理掉 */
    suspend fun syncDay(day: String): Result<TodayResp> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = api.today()
            resp.checkin?.let { checkinDao.upsert(listOf(it.toEntity())) }
            // 服务端已确认的内容超过本地待上传项时，清掉本地队列
            val pendings = pendingDao.all().filter { it.day == resp.day }
            pendings.forEach { p ->
                val done = when (p.kind) {
                    "in" -> resp.checkin?.inAt != null
                    else -> resp.checkin?.outAt != null
                }
                if (done) pendingDao.delete(p.id)
            }
            resp
        }
    }

    suspend fun fetchMonth(month: String): Result<List<CheckinDto>> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = api.list(month = month, limit = 100)
            checkinDao.upsert(resp.items.map { it.toEntity() })
            resp.items
        }
    }

    suspend fun syncRecent(): Result<List<CheckinDto>> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = api.list(limit = 120)
            val items = resp.items.map { it.toEntity() }
            checkinDao.clear()
            checkinDao.upsert(items)
            resp.items
        }
    }

    suspend fun fetchStats(): Result<StatsDto> = withContext(Dispatchers.IO) {
        runCatching { api.stats() }
    }

    /**
     * 落盘 + 入队。照片先 copy 到私有目录，即使上传失败也不会丢。
     */
    suspend fun enqueue(
        kind: String,
        day: String,
        takenAt: String,
        note: String,
        place: String,
        sourcePhoto: File,
    ): Long = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "pending").apply { mkdirs() }
        val compressed = PhotoCompressor.compress(sourcePhoto, File(dir, "${kind}_${System.currentTimeMillis()}.webp"))
        pendingDao.insert(
            PendingEntity(
                kind = kind,
                day = day,
                takenAt = takenAt,
                note = note,
                place = place,
                photoPath = compressed.absolutePath,
                idemKey = UUID.randomUUID().toString(),
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /** 依次上传队列；全部成功返回 true */
    suspend fun uploadPending(): Boolean = withContext(Dispatchers.IO) {
        val items = pendingDao.all()
        var allOk = true
        for (p in items) {
            val file = File(p.photoPath)
            if (!file.exists()) {
                pendingDao.delete(p.id)
                continue
            }
            val part = MultipartBody.Part.createFormData(
                "photo",
                file.name,
                file.asRequestBody("image/webp".toMediaType()),
            )
            try {
                val dto = if (p.kind == "in") {
                    api.checkIn(
                        photo = part,
                        note = p.note.toRequestBody(TEXT),
                        place = p.place.toRequestBody(TEXT),
                        day = p.day.toRequestBody(TEXT),
                        takenAt = p.takenAt.toRequestBody(TEXT),
                        idemKey = p.idemKey,
                    )
                } else {
                    api.checkOut(
                        photo = part,
                        note = p.note.toRequestBody(TEXT),
                        place = p.place.toRequestBody(TEXT),
                        day = p.day.toRequestBody(TEXT),
                        takenAt = p.takenAt.toRequestBody(TEXT),
                        idemKey = p.idemKey,
                    )
                }
                checkinDao.upsert(listOf(dto.toEntity()))
                pendingDao.delete(p.id)
                file.delete()
            } catch (e: HttpException) {
                val body = e.response()?.errorBody()?.string()
                val msg = parseApiError(e.code(), body)
                when (e.code()) {
                    // 服务端已经记录过（幂等/重复打卡），视为成功，丢弃本地队列项
                    409 -> { pendingDao.delete(p.id); file.delete() }
                    400, 401, 403, 413, 422 -> {
                        pendingDao.update(p.copy(attempts = p.attempts + 1, lastError = msg))
                        pendingDao.delete(p.id)
                        file.delete()
                    }
                    else -> {
                        pendingDao.update(p.copy(attempts = p.attempts + 1, lastError = msg))
                        allOk = false
                    }
                }
            } catch (t: Throwable) {
                // 网络异常：保留队列，等 WorkManager 重试
                if (p.attempts > 20) {
                    pendingDao.delete(p.id)
                    file.delete()
                } else {
                    pendingDao.update(p.copy(attempts = p.attempts + 1, lastError = t.message))
                }
                allOk = false
            }
        }
        allOk
    }

    /** 删除一条已同步的打卡记录（服务端 + 本地） */
    suspend fun delete(serverId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            api.deleteCheckin(serverId)
            checkinDao.delete(serverId)
        }
    }

    /** 手动修正进/出馆时间（outAt 传空串 = 还没出馆） */
    suspend fun updateTimes(serverId: Long, inAt: String?, outAt: String?): Result<CheckinEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dto = api.patchCheckin(
                    serverId,
                    PatchCheckinReq(inAt = inAt, outAt = outAt),
                )
                val e = dto.toEntity()
                checkinDao.upsert(listOf(e))
                e
            }
        }

    fun baseUrl(): String = store.baseUrl
}

/**
 * 每日详情仓库：当天的过程留存（连拍多张 + 笔记）。
 * 与「进/出馆打卡」互不影响，照片同样先落本地再上传。
 */
@Singleton
class EntryRepository @Inject constructor(
    private val api: ApiService,
    private val db: AppDatabase,
    @ApplicationContext private val context: Context,
) {
    private val entryDao = db.entryDao()
    private val pendingDao = db.pendingEntryDao()

    fun observeDay(day: String): Flow<List<EntryEntity>> = entryDao.observeDay(day)
    fun observePending(day: String): Flow<List<PendingEntryEntity>> = pendingDao.observeForDay(day)
    fun observePendingCount(): Flow<Int> = pendingDao.observeCount()

    /** 组合已同步条目与本地待上传照片，UI 一眼看到「已存/待传」 */
    fun observeFeed(day: String): Flow<EntryFeed> = combine(
        entryDao.observeDay(day),
        pendingDao.observeForDay(day),
    ) { synced, pending -> EntryFeed(synced, pending) }

    suspend fun syncDay(day: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = api.listEntries(day = day, limit = 200)
            entryDao.upsert(resp.items.map { it.toEntity() })
        }
    }

    /** 纯笔记：联网失败就排队，联网后自动补传 */
    suspend fun saveNote(day: String, note: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = api.createEntries(
                photos = emptyList(),
                note = note.toRequestBody(TEXT),
                day = day.toRequestBody(TEXT),
                idemKey = UUID.randomUUID().toString(),
            )
            entryDao.upsert(resp.items.map { it.toEntity() })
        }.recoverCatching { e ->
            queue(day = day, note = note, photoPath = "", attempts = 0, lastError = e.message)
            Unit
        }
    }

    /** 连拍：全部照片压缩落盘并入队，返回队列项 id 列表 */
    suspend fun enqueueBurst(day: String, note: String, sources: List<File>): List<Long> =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "entries").apply { mkdirs() }
            sources.mapIndexed { i, src ->
                val out = File(dir, "entry_${System.currentTimeMillis()}_$i.webp")
                val compressed = try {
                    PhotoCompressor.compress(src, out)
                } catch (t: Throwable) {
                    src
                }
                pendingDao.insert(
                    PendingEntryEntity(
                        day = day,
                        note = note,
                        photoPath = compressed.absolutePath,
                        idemKey = UUID.randomUUID().toString(),
                        createdAt = System.currentTimeMillis() + i,
                    ),
                )
            }
        }

    /** 逐条上传待传项；有失败返回 false 交给 WorkManager 重试 */
    suspend fun uploadPending(): Boolean = withContext(Dispatchers.IO) {
        var allOk = true
        for (p in pendingDao.all()) {
            val file = if (p.photoPath.isBlank()) null else File(p.photoPath)
            if (file != null && !file.exists()) {
                pendingDao.delete(p.id)
                continue
            }
            val parts = if (file != null) {
                listOf(
                    MultipartBody.Part.createFormData(
                        "photo",
                        file.name,
                        file.asRequestBody("image/webp".toMediaType()),
                    ),
                )
            } else {
                emptyList()
            }
            try {
                val resp = api.createEntries(
                    photos = parts,
                    note = p.note.toRequestBody(TEXT),
                    day = p.day.toRequestBody(TEXT),
                    idemKey = p.idemKey,
                )
                entryDao.upsert(resp.items.map { it.toEntity() })
                pendingDao.delete(p.id)
                file?.delete()
            } catch (e: HttpException) {
                val msg = parseApiError(e.code(), e.response()?.errorBody()?.string())
                when (e.code()) {
                    409 -> {
                        pendingDao.delete(p.id)
                        file?.delete()
                    }
                    400, 401, 403, 413, 422 -> {
                        pendingDao.update(p.copy(attempts = p.attempts + 1, lastError = msg))
                        pendingDao.delete(p.id)
                        file?.delete()
                    }
                    else -> {
                        pendingDao.update(p.copy(attempts = p.attempts + 1, lastError = msg))
                        allOk = false
                    }
                }
            } catch (t: Throwable) {
                if (p.attempts > 20) {
                    pendingDao.delete(p.id)
                    file?.delete()
                } else {
                    pendingDao.update(p.copy(attempts = p.attempts + 1, lastError = t.message))
                }
                allOk = false
            }
        }
        allOk
    }

    /** 删除一条（先删服务端，成功后再删本地；离线时也删本地，避免残留） */
    suspend fun delete(serverId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            api.deleteEntry(serverId)
        }.onSuccess {
            entryDao.delete(serverId)
        }.recoverCatching { e ->
            if (e is HttpException && e.code() == 404) entryDao.delete(serverId) else throw e
        }.map { }
    }

    suspend fun deletePending(id: Long) = withContext(Dispatchers.IO) {
        pendingDao.all().firstOrNull { it.id == id }?.photoPath?.takeIf { it.isNotBlank() }?.let {
            File(it).delete()
        }
        pendingDao.delete(id)
    }

    private suspend fun queue(day: String, note: String, photoPath: String, attempts: Int, lastError: String?) {
        pendingDao.insert(
            PendingEntryEntity(
                day = day,
                note = note,
                photoPath = photoPath,
                idemKey = UUID.randomUUID().toString(),
                createdAt = System.currentTimeMillis(),
                attempts = attempts,
                lastError = lastError,
            ),
        )
    }
}

/** 当日详情流：已同步条目 + 本地待上传 */
data class EntryFeed(
    val synced: List<EntryEntity> = emptyList(),
    val pending: List<PendingEntryEntity> = emptyList(),
) {
    val total get() = synced.size + pending.size
    val pendingCount get() = pending.size
}

/** 组合当日缓存与待上传队列，供 UI 使用 */
data class DaySnapshot(
    val checkin: CheckinEntity?,
    val pending: List<PendingEntity>,
)

fun combineDay(
    checkin: Flow<CheckinEntity?>,
    pending: Flow<List<PendingEntity>>,
): Flow<DaySnapshot> = combine(checkin, pending) { c, p -> DaySnapshot(c, p) }

/** 数据导出：CSV / 照片 ZIP 直接写进用户选的文件（SAF Uri），不在本地留副本 */
@Singleton
class ExportRepository @Inject constructor(
    private val api: ApiService,
    @ApplicationContext private val context: Context,
) {
    enum class Kind(val fileName: String, val mime: String) {
        CHECKINS("studycheck-checkins.csv", "text/csv"),
        NOTES("studycheck-notes.csv", "text/csv"),
        PHOTOS("studycheck-photos.zip", "application/zip"),
    }

    suspend fun writeTo(uri: android.net.Uri, kind: Kind): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val body = when (kind) {
                Kind.CHECKINS -> api.exportCheckins()
                Kind.NOTES -> api.exportNotes()
                Kind.PHOTOS -> api.exportPhotos()
            }
            body.use { rb ->
                val out = context.contentResolver.openOutputStream(uri)
                    ?: error("无法写入所选文件")
                out.use { os ->
                    var total = 0L
                    rb.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            total += n
                        }
                    }
                    os.flush()
                    total
                }
            }
        }
    }
}
