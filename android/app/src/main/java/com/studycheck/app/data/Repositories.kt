package com.studycheck.app.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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

    /** 改密码：服务端返回新会话，直接落盘（其它设备的登录会失效） */
    suspend fun changePassword(oldPassword: String, newPassword: String): Result<UserDto> = runCatching {
        val resp = api.changePassword(ChangePasswordReq(oldPassword, newPassword))
        persist(resp)
        resp.user
    }

    /** 注销账号：删服务端数据后清掉本地登录态 */
    suspend fun deleteAccount(password: String): Result<Unit> = runCatching {
        api.deleteAccount(DeleteAccountReq(password))
        store.clearSession()
    }.map { }

    suspend fun adminUsers(): Result<List<AdminUserDto>> = runCatching { api.adminUsers().items }

    suspend fun adminResetPassword(id: Long, newPassword: String): Result<Unit> =
        runCatching { api.adminResetPassword(id, ResetPasswordReq(newPassword)) }.map { }

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
        store.isAdmin = resp.user.isAdmin
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
    private val sessionDao = db.sessionDao()

    fun observeDay(day: String): Flow<CheckinEntity?> = checkinDao.observeDay(day)
    fun observePending(day: String): Flow<List<PendingEntity>> = pendingDao.observeForDay(day)
    fun observePendingCount(): Flow<Int> = pendingDao.observeCount()
    fun observeRecent(limit: Int = 60): Flow<List<CheckinEntity>> = checkinDao.observeRecent(limit)
    fun observeMonth(prefix: String): Flow<List<CheckinEntity>> = checkinDao.observeMonth(prefix)
    fun observeSessions(day: String): Flow<List<SessionEntity>> = sessionDao.observeDay(day)

    /** 今天还没出馆的那一段的进馆时间（没有则 null），给超时提醒用 */
    suspend fun latestOpenInAt(day: String): String? = withContext(Dispatchers.IO) {
        sessionDao.observeDay(day).first().lastOrNull { it.outAt == null && !it.inAt.isNullOrBlank() }?.inAt
    }

    /** 拉取服务端当日状态并写入本地缓存；同时把已完成的待上传项清理掉 */
    suspend fun syncDay(day: String): Result<TodayResp> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = api.today()
            resp.checkin?.let { checkinDao.upsert(listOf(it.toEntity())) }
            // 分段缓存整天的（服务端是权威，直接覆盖）
            sessionDao.deleteDay(resp.day)
            sessionDao.upsert(resp.sessions.map { it.toSessionEntity() })
            // 服务端已确认的内容超过本地待上传项时，清掉本地队列。
            // 分段模式下：进馆看「有没有段」，出馆看「有没有未结束的段」。
            val pendings = pendingDao.all().filter { it.day == resp.day }
            val openCount = resp.sessions.count { it.outAt == null }
            pendings.forEach { p ->
                val done = when (p.kind) {
                    "in" -> resp.sessions.isNotEmpty()
                    else -> openCount == 0 && resp.sessions.isNotEmpty()
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

    suspend fun fetchReport(period: String, anchor: String): Result<ReportDto> =
        withContext(Dispatchers.IO) { runCatching { api.report(period, anchor) } }

    suspend fun fetchAchievements(): Result<AchievementsDto> =
        withContext(Dispatchers.IO) { runCatching { api.achievements() } }

    /** 各科目时长（1.8.0 起） */
    suspend fun fetchSubjectStats(days: Int = 30): Result<List<com.studycheck.app.data.SubjectStat>> =
        withContext(Dispatchers.IO) {
            runCatching { api.subjectStats(days).items.filter { it.seconds > 0 } }
        }

    /** 手动补卡：服务端建好后写进本地缓存 */
    suspend fun createManual(req: ManualCheckinReq): Result<CheckinEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dto = api.createCheckin(req)
                val e = dto.toEntity()
                checkinDao.upsert(listOf(e))
                e
            }
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
        subject: String,
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
                subject = subject,
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
                        subject = p.subject.takeIf { it.isNotBlank() }?.toRequestBody(TEXT),
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

    /** 补出馆：关掉前几天忘了出馆的那一段（服务端对非当天的段允许不带照片） */
    suspend fun closeStaleSession(): Result<CheckinDto> = withContext(Dispatchers.IO) {
        runCatching {
            val dto = api.checkOutLite("补出馆".toRequestBody(TEXT))
            checkinDao.upsert(listOf(dto.toEntity()))
            // 那一天的缓存重拉一遍，分段列表才对得上
            sessionDao.deleteDay(dto.day)
            runCatching { api.listSessions(dto.day) }.getOrNull()?.let { resp ->
                sessionDao.upsert(resp.items.map { it.toSessionEntity() })
            }
            dto
        }
    }

    /** 拉某天的分段并写进本地缓存 */
    suspend fun fetchSessions(day: String): Result<List<CheckinDto>> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = api.listSessions(day)
            sessionDao.deleteDay(day)
            sessionDao.upsert(resp.items.map { it.toSessionEntity() })
            resp.items
        }
    }

    /** 给某天补一段（不需要照片） */
    suspend fun createSession(req: SessionCreateReq): Result<CheckinDto> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = api.createSession(req)
            resp.checkin?.let { checkinDao.upsert(listOf(it.toEntity())) }
            resp.session?.let { sessionDao.upsert(listOf(it.toSessionEntity())) }
            resp.session ?: error("补卡失败")
        }
    }

    /** 只修正这一段 */
    suspend fun patchSession(id: Long, req: PatchCheckinReq): Result<CheckinDto> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = api.patchSession(id, req)
                resp.session?.let { sessionDao.upsert(listOf(it.toSessionEntity())) }
                resp.checkin?.let { checkinDao.upsert(listOf(it.toEntity())) }
                resp.session ?: error("保存失败")
            }
        }

    /** 删掉某一段 */
    suspend fun deleteSession(id: Long, day: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            api.deleteSession(id)
            sessionDao.delete(id)
            // 当天可能一段都不剩了，重新同步一次聚合结果
            runCatching { api.today() }.getOrNull()?.let { resp ->
                if (resp.day == day) {
                    sessionDao.deleteDay(day)
                    sessionDao.upsert(resp.sessions.map { it.toSessionEntity() })
                    if (resp.checkin != null) {
                        checkinDao.upsert(listOf(resp.checkin.toEntity()))
                    } else {
                        checkinDao.deleteDay(day)
                    }
                }
            }
            Unit
        }
    }

    /** 全局搜索 */
    suspend fun search(q: String): Result<List<SearchItemDto>> =
        withContext(Dispatchers.IO) { runCatching { api.search(q).items } }

    /** 删除一条已同步的打卡记录（服务端 + 本地） */
    suspend fun delete(serverId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val day = checkinDao.all().firstOrNull { it.serverId == serverId }?.day
            api.deleteCheckin(serverId)
            checkinDao.delete(serverId)
            if (day != null) sessionDao.deleteDay(day)
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
        SESSIONS("studycheck-sessions.csv", "text/csv"),
        NOTES("studycheck-notes.csv", "text/csv"),
        PHOTOS("studycheck-photos.zip", "application/zip"),
    }

    suspend fun writeTo(uri: android.net.Uri, kind: Kind): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val body = when (kind) {
                Kind.CHECKINS -> api.exportCheckins()
                Kind.SESSIONS -> api.exportSessions()
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
