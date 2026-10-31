package com.studycheck.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studycheck.app.data.AuthRepository
import com.studycheck.app.data.CheckinDto
import com.studycheck.app.data.CheckinEntity
import com.studycheck.app.data.CheckinRepository
import com.studycheck.app.data.TokenStore
import com.studycheck.app.data.DaySnapshot
import com.studycheck.app.data.StatsDto
import com.studycheck.app.data.combineDay
import com.studycheck.app.data.nowIso
import com.studycheck.app.data.toSessionEntity
import com.studycheck.app.data.todayString
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import retrofit2.HttpException

fun Throwable.friendly(): String = when (this) {
    is HttpException -> com.studycheck.app.data.parseApiError(code(), response()?.errorBody()?.string())
    is UnknownHostException -> "连不上服务器，请检查网络或「我的 → 服务器地址」"
    is SocketTimeoutException -> "网络超时，稍后会自动重试"
    else -> message ?: "操作失败"
}

/** 全局登录态 */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val store: com.studycheck.app.data.TokenStore,
) : ViewModel() {
    private val _loggedIn = MutableStateFlow(auth.isLoggedIn || store.devMode)
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    fun signIn() { _loggedIn.value = true }

    /** 后端开了 SC_DEV_NO_AUTH 时，跳过登录直接进入（联调用） */
    fun signInDev() {
        store.devMode = true
        _loggedIn.value = true
    }

    fun signOut() {
        store.devMode = false
        _loggedIn.value = false
    }
}

enum class DayState { NotStarted, InLibrary, Finished }

data class TodayUi(
    val day: String = todayString(),
    val state: DayState = DayState.NotStarted,
    val inAt: String? = null,
    val outAt: String? = null,
    val inNote: String = "",
    val outNote: String = "",
    val inPlace: String = "",
    val outPlace: String = "",
    val inThumb: String? = null,
    val outThumb: String? = null,
    val elapsedSec: Long = 0,
    val pending: Int = 0,
    val goalMin: Int = 120,
    val weekDays: Int = 0,
    val weekMinutes: Long = 0,
    val streak: Int = 0,
    val longest: Int = 0,
    val busy: Boolean = false,
    val error: String? = null,
    val baseUrl: String = "",
    /** 当天的分段（一天可以多次进馆） */
    val sessions: List<com.studycheck.app.data.SessionEntity> = emptyList(),
    /** 前几天进馆后忘了出馆的那一段（跨天自习 / 忘了点出馆） */
    val staleOpen: com.studycheck.app.data.SessionEntity? = null,
) {
    val sessionCount: Int get() = sessions.size

    /** 加起来的总时长：已结束的段 + 进行中的段到此刻 */
    fun totalSecNow(nowMillis: Long): Long {
        var total = 0L
        sessions.forEach { ss ->
            if (ss.outAt != null) {
                total += ss.durationSec
            } else if (ss.inAt != null) {
                val start = runCatching {
                    java.time.OffsetDateTime.parse(ss.inAt).toInstant().toEpochMilli()
                }.getOrDefault(0L)
                if (start > 0) total += ((nowMillis - start) / 1000).coerceAtLeast(0)
            }
        }
        return total
    }

    /** 当前还没出馆的那一段 */
    val openSession: com.studycheck.app.data.SessionEntity?
        get() = sessions.lastOrNull { it.outAt == null }
}

private data class BaseState(
    val snap: DaySnapshot,
    val stats: StatsDto?,
    val busy: Boolean,
    val error: String?,
    val goal: Int,
)

@HiltViewModel
class TodayViewModel @Inject constructor(
    private val repo: CheckinRepository,
    private val auth: AuthRepository,
    private val store: com.studycheck.app.data.TokenStore,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {

    private val day = todayString()
    private val stats = MutableStateFlow<StatsDto?>(null)
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val goal = MutableStateFlow(auth.savedGoal())
    private val baseUrl = MutableStateFlow(auth.baseUrl())

    private val snap: kotlinx.coroutines.flow.Flow<DaySnapshot> =
        combineDay(repo.observeDay(day), repo.observePending(day))

    private val sessions = repo.observeSessions(day)

    // 前几天的段还没出馆：服务端今天没有开着的段，但库里留了一段 out_at 为空
    private val stale = MutableStateFlow<com.studycheck.app.data.SessionEntity?>(null)

    // 专注（番茄钟）状态。注意：必须声明在 init 之前——init 里会用到它，
    // 而 Kotlin 按声明顺序初始化属性，声明晚了 init 阶段就是 null（曾因此启动即崩）。
    private val _focus = MutableStateFlow(focusSnapshot())

    /** 专注状态单独一个流：界面把它和今日状态一起显示 */
    val focus: StateFlow<com.studycheck.app.data.FocusSnapshot> = _focus.asStateFlow()

    // combine 最多 5 个流，这里先合并基础状态，再和分段合并
    private val base: kotlinx.coroutines.flow.Flow<BaseState> = combine(
        snap,
        stats,
        busy,
        error,
        goal,
    ) { s, st, b, e, g -> BaseState(s, st, b, e, g) }

    val ui: StateFlow<TodayUi> = combine(base, sessions, stale) { base, segs, staleSeg ->
        val c: com.studycheck.app.data.CheckinEntity? = base.snap.checkin
        val open = segs.any { it.outAt == null }
        TodayUi(
            day = day,
            state = when {
                open -> DayState.InLibrary
                segs.isNotEmpty() -> DayState.Finished
                c?.outAt != null -> DayState.Finished
                c?.inAt != null -> DayState.InLibrary
                else -> DayState.NotStarted
            },
            inAt = segs.firstOrNull()?.inAt ?: c?.inAt,
            outAt = segs.lastOrNull { it.outAt != null }?.outAt ?: c?.outAt,
            inNote = c?.inNote ?: "",
            outNote = c?.outNote ?: "",
            inPlace = c?.inPlace ?: "",
            outPlace = c?.outPlace ?: "",
            inThumb = c?.inThumb ?: c?.inPhoto,
            outThumb = c?.outThumb ?: c?.outPhoto,
            elapsedSec = c?.durationSec ?: 0,
            pending = base.snap.pending.size,
            goalMin = base.goal,
            weekDays = base.stats?.weekDays ?: 0,
            weekMinutes = base.stats?.weekMinutes ?: 0,
            streak = base.stats?.currentStreak ?: 0,
            longest = base.stats?.longestStreak ?: 0,
            busy = base.busy,
            error = base.error,
            baseUrl = baseUrl.value,
            sessions = segs,
            staleOpen = staleSeg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUi(goalMin = auth.savedGoal()))

    init {
        refresh()
        // 今日数据一变就同步给桌面小组件（离线也能看到上次的状态）
        viewModelScope.launch {
            repo.observeSessions(day).collect { segs -> pushWidget(segs) }
        }
    }

    /** 把当前状态写进小组件快照 */
    private fun pushWidget(segs: List<com.studycheck.app.data.SessionEntity>) {
        val done = segs.filter { it.outAt != null }
        val running = segs.lastOrNull { it.outAt == null && !it.inAt.isNullOrBlank() }
        val doneSec = done.sumOf { seg ->
            val a = parseMillis(seg.inAt)
            val b = parseMillis(seg.outAt)
            if (a <= 0 || b <= a) 0L else (b - a) / 1000
        }
        com.studycheck.app.widget.TodayWidget.push(
            appContext,
            com.studycheck.app.data.TodayWidgetState(
                day = day,
                inAt = running?.inAt,
                outAt = done.lastOrNull()?.outAt,
                doneSec = doneSec,
            ),
        )
    }

    /** 开始 / 继续 */
    fun focusStart() {
        val left = store.focusLeftSec.coerceAtLeast(0)
        if (left <= 0) store.focusLeftSec = store.focusTotalSec
        store.focusEndAt = System.currentTimeMillis() + store.focusLeftSec * 1000L
        publishFocus()
    }

    fun focusPause() {
        store.focusLeftSec = (((store.focusEndAt - System.currentTimeMillis()) / 1000L).toInt())
            .coerceAtLeast(0)
        store.focusEndAt = 0
        publishFocus()
    }

    fun focusReset() {
        store.focusEndAt = 0
        store.focusLeftSec = store.focusTotalSec
        publishFocus()
    }

    /** 选一个预设（分钟 + 阶段），选完直接开始 */
    fun focusPreset(minutes: Int, mode: String) {
        store.focusMode = mode
        store.focusTotalSec = minutes * 60
        store.focusEndAt = 0
        store.focusLeftSec = minutes * 60
        publishFocus()
        focusStart()
    }

    /* ---- 专注（番茄钟）：状态放在本地，界面只负责显示和切换 ---- */

    /** 把本地最新状态推给界面 */
    private fun publishFocus() {
        _focus.value = focusSnapshot()
    }

    fun focusSnapshot(): com.studycheck.app.data.FocusSnapshot = com.studycheck.app.data.FocusSnapshot(
        mode = store.focusMode,
        totalSec = store.focusTotalSec,
        endAt = store.focusEndAt,
        leftSec = store.focusLeftSec,
        rounds = store.focusRounds,
    )

    /** 到点了：切到下一段（专注 → 休息，休息 → 专注） */
    fun focusAdvance(): String {
        val rounds = if (store.focusMode == "focus") store.focusRounds + 1 else store.focusRounds
        return if (store.focusMode == "focus") {
            store.focusRounds = rounds
            // 每 4 段一个长休息
            store.focusMode = "break"
            store.focusTotalSec = if (rounds % 4 == 0) 15 * 60 else 5 * 60
            store.focusEndAt = 0
            store.focusLeftSec = store.focusTotalSec
            "这一段完成，休息 ${store.focusTotalSec / 60} 分钟"
        } else {
            store.focusMode = "focus"
            store.focusTotalSec = 25 * 60
            store.focusEndAt = 0
            store.focusLeftSec = store.focusTotalSec
            "休息结束，开始下一段专注"
        }
    }

    fun refresh() {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            baseUrl.value = auth.baseUrl()
            repo.syncDay(day)
                .onSuccess { resp ->
                    stale.value = if (resp.staleOpen) resp.openSession?.toSessionEntity() else null
                }
                .onFailure { error.value = it.friendly() }
            repo.fetchStats().onSuccess {
                stats.value = it
                if (it.dailyGoalMin > 0) {
                    goal.value = it.dailyGoalMin
                    auth.setSavedGoal(it.dailyGoalMin)
                }
            }
            repo.uploadPending()
            repo.syncDay(day)
            busy.value = false
        }
    }

    /** 修正某一段的进/出馆时间（outIso 传空串表示还没出馆）；subject 为 null 表示不改科目 */
    fun fixSession(
        id: Long,
        inIso: String,
        outIso: String?,
        subject: String? = null,
        onDone: (String?) -> Unit = {},
    ) {
        viewModelScope.launch {
            busy.value = true
            repo.patchSession(
                id,
                com.studycheck.app.data.PatchCheckinReq(inAt = inIso, outAt = outIso, subject = subject),
            )
                .onSuccess {
                    repo.fetchStats().onSuccess { stats.value = it }
                    onDone(null)
                }
                .onFailure { e ->
                    error.value = e.friendly()
                    onDone(e.friendly())
                }
            busy.value = false
        }
    }

    fun deleteSession(id: Long) {
        viewModelScope.launch {
            busy.value = true
            repo.deleteSession(id, day)
                .onSuccess { repo.fetchStats().onSuccess { stats.value = it } }
                .onFailure { error.value = it.friendly() }
            busy.value = false
        }
    }

    /** 补出馆：把前几天忘掉的段关掉 */
    fun closeStale() {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            repo.closeStaleSession()
                .onSuccess {
                    stale.value = null
                    repo.fetchStats().onSuccess { stats.value = it }
                }
                .onFailure { error.value = it.friendly() }
            busy.value = false
        }
    }

    fun dismissError() { error.value = null }
}

/** 拍照后提交：先落本地队列，交给 WorkManager 上传 */
@HiltViewModel
class CheckinSubmitViewModel @Inject constructor(
    private val repo: CheckinRepository,
    val store: TokenStore,
) : ViewModel() {

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun submit(
        kind: String,
        photo: File,
        note: String,
        place: String,
        subject: String = "",
        takenAt: String = nowIso(),
        onQueued: () -> Unit,
    ) {
        viewModelScope.launch {
            _saving.value = true
            _error.value = null
            val result = runCatching {
                repo.enqueue(kind, todayString(), takenAt, note, place, subject.trim(), photo)
            }
            _saving.value = false
            result.onSuccess { onQueued() }
                .onFailure { _error.value = it.friendly() }
        }
    }

    fun clearError() { _error.value = null }
}

data class HistoryUi(
    val month: String = todayString().substring(0, 7),
    val items: List<com.studycheck.app.data.CheckinEntity> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val baseUrl: String = "",
    val manualBusy: Boolean = false,
    val manualError: String? = null,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repo: CheckinRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(HistoryUi(baseUrl = auth.baseUrl()))
    val ui: StateFlow<HistoryUi> = _ui.asStateFlow()

    private val monthFlow = MutableStateFlow(_ui.value.month)

    init {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        viewModelScope.launch {
            monthFlow.flatMapLatest { m -> repo.observeMonth(m) }.collect { list ->
                _ui.update { it.copy(items = list, loading = false) }
            }
        }
        reload()
    }

    fun shiftMonth(delta: Int) {
        val cur = java.time.YearMonth.parse(monthFlow.value).plusMonths(delta.toLong())
        monthFlow.value = cur.toString()
        _ui.update { it.copy(month = cur.toString(), loading = true) }
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null, baseUrl = auth.baseUrl()) }
            repo.fetchMonth(monthFlow.value).onFailure {
                _ui.update { s -> s.copy(loading = false, error = it.friendly()) }
            }
            _ui.update { it.copy(loading = false) }
        }
    }

    /** 删除一条打卡（服务端删成功才清本地缓存） */
    fun delete(item: CheckinEntity) {
        viewModelScope.launch {
            repo.delete(item.serverId)
                .onSuccess { _ui.update { s -> s.copy(error = null) } }
                .onFailure { e -> _ui.update { s -> s.copy(error = "删除失败：${e.friendly()}") } }
        }
    }

    /** 手动补卡：漏打卡时按日期与时间补一条 */
    fun createManual(
        day: String,
        inAt: String,
        outAt: String,
        place: String,
        note: String,
        subject: String = "",
        onDone: (Boolean, String) -> Unit,
    ) {
        viewModelScope.launch {
            _ui.update { it.copy(manualBusy = true, manualError = null) }
            val req = com.studycheck.app.data.ManualCheckinReq(
                day = day,
                inAt = inAt,
                outAt = outAt,
                inPlace = place,
                outPlace = place,
                inNote = note,
                subject = subject.trim(),
            )
            repo.createManual(req)
                .onSuccess {
                    _ui.update { it.copy(manualBusy = false) }
                    reload()
                    onDone(true, "补卡成功")
                }
                .onFailure { e ->
                    val msg = e.friendly()
                    _ui.update { it.copy(manualBusy = false, manualError = msg) }
                    onDone(false, msg)
                }
        }
    }
}

data class StatsUi(
    val stats: StatsDto? = null,
    val report: com.studycheck.app.data.ReportDto? = null,
    val achievements: com.studycheck.app.data.AchievementsDto? = null,
    /** 最近 30 天各科目时长（1.8.0 起） */
    val subjects: List<com.studycheck.app.data.SubjectStat> = emptyList(),
    val period: String = "week",
    val anchor: String = todayString(),
    val loading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val repo: CheckinRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(StatsUi(loading = true))
    val ui: StateFlow<StatsUi> = _ui.asStateFlow()

    init { reload() }

    fun reload() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            repo.fetchStats()
                .onSuccess { s -> _ui.update { it.copy(stats = s) } }
                .onFailure { e -> _ui.update { it.copy(error = e.friendly()) } }
            loadReport()
            repo.fetchAchievements()
                .onSuccess { a -> _ui.update { it.copy(achievements = a) } }
            repo.fetchSubjectStats(30)
                .onSuccess { list -> _ui.update { it.copy(subjects = list) } }
            _ui.update { it.copy(loading = false) }
        }
    }

    fun setPeriod(period: String) {
        if (_ui.value.period == period) return
        _ui.update { it.copy(period = period, anchor = todayString()) }
        viewModelScope.launch { loadReport() }
    }

    fun shiftPeriod(delta: Int) {
        viewModelScope.launch {
            val u = _ui.value
            val anchor = shiftAnchor(u.anchor, u.period, delta)
            if (anchor > todayString()) return@launch
            _ui.update { it.copy(anchor = anchor) }
            loadReport()
        }
    }

    private suspend fun loadReport() {
        val u = _ui.value
        repo.fetchReport(u.period, u.anchor)
            .onSuccess { r -> _ui.update { it.copy(report = r) } }
    }
}

private fun shiftAnchor(anchor: String, period: String, delta: Int): String {
    return try {
        val d = java.time.LocalDate.parse(anchor)
        val next = if (period == "month") d.plusMonths(delta.toLong()) else d.plusWeeks(delta.toLong())
        next.toString()
    } catch (_: Exception) {
        todayString()
    }
}

data class PhotosUi(
    val month: String = currentMonth(),
    val data: com.studycheck.app.data.PhotosResp = com.studycheck.app.data.PhotosResp(),
    val loading: Boolean = false,
    val error: String? = null,
    val baseUrl: String = "",
)

@HiltViewModel
class PhotosViewModel @Inject constructor(
    private val repo: CheckinRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(PhotosUi(loading = true, baseUrl = auth.baseUrl()))
    val ui: StateFlow<PhotosUi> = _ui.asStateFlow()

    init { reload() }

    fun reload() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null, baseUrl = auth.baseUrl()) }
            repo.fetchPhotos(_ui.value.month)
                .onSuccess { d -> _ui.update { it.copy(data = d, month = d.month.ifBlank { it.month }) } }
                .onFailure { e -> _ui.update { it.copy(error = e.friendly()) } }
            _ui.update { it.copy(loading = false) }
        }
    }

    /** 翻月：delta=-1 上一个月，+1 下一个月 */
    fun shift(delta: Int) {
        val cur = _ui.value.month.ifBlank { currentMonth() }
        val next = try {
            java.time.YearMonth.parse(cur).plusMonths(delta.toLong()).toString()
        } catch (_: Exception) {
            currentMonth()
        }
        if (next > currentMonth()) return
        _ui.update { it.copy(month = next) }
        reload()
    }
}

data class MeUi(
    val username: String = "",
    val nickname: String = "",
    val goalMin: Int = 120,
    val baseUrl: String = "",
    val reminderEnabled: Boolean = true,
    val reminderHour: Int = 20,
    /** 连续在馆多久提醒一次（分钟），0 表示关掉 */
    val overtimeMin: Int = 240,
    /** 外观：auto / light / dark */
    val themeMode: String = "auto",
    /** 主色 id（1.10.0 起） */
    val themeColor: String = "amber",
    /** 每周学习小结 */
    val weeklyOn: Boolean = false,
    val weeklyDay: Int = 7,
    val weeklyHour: Int = 20,
    val saving: Boolean = false,
    val message: String? = null,
    val pendingCount: Int = 0,
    val exporting: Boolean = false,
    val exportNote: String? = null,
    /** 版本检查状态 */
    val updateState: com.studycheck.app.data.UpdateState = com.studycheck.app.data.UpdateState.Idle,
    /** 账号安全 */
    val isAdmin: Boolean = false,
    val passwordBusy: Boolean = false,
    val passwordMessage: String? = null,
    val deleting: Boolean = false,
    val users: List<com.studycheck.app.data.AdminUserDto> = emptyList(),
    /** 数据导入（1.9.0 起） */
    val importing: Boolean = false,
    val importResult: com.studycheck.app.data.ImportResp? = null,
    val importError: String? = null,
)

@HiltViewModel
class MeViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val repo: CheckinRepository,
    private val exporter: com.studycheck.app.data.ExportRepository,
    private val store: com.studycheck.app.data.TokenStore,
    private val updates: com.studycheck.app.data.UpdateRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {

    private val _ui = MutableStateFlow(
        MeUi(
            username = store.username ?: "",
            nickname = store.nickname ?: "",
            goalMin = store.savedGoal,
            baseUrl = store.baseUrl,
            reminderEnabled = store.reminderEnabled,
            reminderHour = store.reminderHour,
            overtimeMin = store.overtimeRemindMin,
            themeMode = store.themeMode,
            themeColor = store.themeColor,
            weeklyOn = store.weeklyReportEnabled,
            weeklyDay = store.weeklyReportWeekday,
            weeklyHour = store.weeklyReportHour,
            isAdmin = store.isAdmin,
        ),
    )
    val ui: StateFlow<MeUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            repo.observePendingCount().collect { n -> _ui.update { it.copy(pendingCount = n) } }
        }
        viewModelScope.launch {
            auth.refreshProfile().onSuccess { u ->
                _ui.update {
                    it.copy(
                        username = u.username,
                        nickname = u.nickname,
                        goalMin = u.dailyGoalMin,
                        isAdmin = u.isAdmin,
                    )
                }
                store.isAdmin = u.isAdmin
                if (u.isAdmin) loadUsers()
            }
        }
    }

    /* ---------------- App 版本检查 ---------------- */

    /** 进入页面时调用：距上次检查超过一天才真的联网，其余时候直接用上次结论。 */
    fun checkUpdateIfStale() {
        val last = store.lastUpdateCheckAt
        val stale = System.currentTimeMillis() - last > 24 * 3600 * 1000
        if (!stale && _ui.value.updateState !is com.studycheck.app.data.UpdateState.Idle) return
        if (!stale && last > 0) {
            _ui.update { it.copy(updateState = com.studycheck.app.data.UpdateState.UpToDate(com.studycheck.app.BuildConfig.VERSION_NAME)) }
            return
        }
        checkUpdate()
    }

    /** 打开发布页（有新版时跳过去下载） */
    fun openReleasePage() = updates.releasePageUrl()

    fun checkUpdate() {
        if (_ui.value.updateState is com.studycheck.app.data.UpdateState.Checking) return
        viewModelScope.launch {
            _ui.update { it.copy(updateState = com.studycheck.app.data.UpdateState.Checking) }
            val result = runCatching { updates.check() }
            store.lastUpdateCheckAt = System.currentTimeMillis()
            result.onSuccess { m ->
                val local = com.studycheck.app.BuildConfig.VERSION_NAME
                _ui.update {
                    it.copy(
                        updateState = if (com.studycheck.app.data.UpdateRepository.hasUpdate(local, m.version)) {
                            com.studycheck.app.data.UpdateState.Available(m)
                        } else {
                            com.studycheck.app.data.UpdateState.UpToDate(local)
                        },
                    )
                }
            }.onFailure { e ->
                _ui.update {
                    it.copy(
                        updateState = com.studycheck.app.data.UpdateState.Failed(e.friendly()),
                    )
                }
            }
        }
    }

    fun setGoal(v: Int) { _ui.update { it.copy(goalMin = v.coerceIn(10, 960)) } }

    fun setReminder(enabled: Boolean, hour: Int) {
        _ui.update { it.copy(reminderEnabled = enabled, reminderHour = hour) }
        store.reminderEnabled = enabled
        store.reminderHour = hour
        com.studycheck.app.work.ReminderScheduler.scheduleOvertime(
            appContext,
            enabled,
            _ui.value.overtimeMin,
        )
    }

    /** 超时未出馆提醒的阈值（分钟），0 = 关闭 */
    fun setOvertime(minutes: Int) {
        _ui.update { it.copy(overtimeMin = minutes) }
        store.overtimeRemindMin = minutes
        com.studycheck.app.work.ReminderScheduler.scheduleOvertime(
            appContext,
            _ui.value.reminderEnabled,
            minutes,
        )
    }

    /** 每周学习小结：weekday 1=周一 … 7=周日 */
    fun setWeekly(enabled: Boolean, weekday: Int, hour: Int) {
        _ui.update { it.copy(weeklyOn = enabled, weeklyDay = weekday, weeklyHour = hour) }
        store.weeklyReportEnabled = enabled
        store.weeklyReportWeekday = weekday
        store.weeklyReportHour = hour
        com.studycheck.app.work.WeeklyReportWorker.schedule(appContext, enabled, weekday, hour)
    }

    /** 外观：auto / light / dark */
    fun setTheme(mode: String) {
        _ui.update { it.copy(themeMode = mode) }
        store.themeMode = mode
        com.studycheck.app.ui.theme.ThemeController.mode.value = mode
    }

    /** 主色：id 见 com.studycheck.app.ui.theme.BRANDS */
    fun setThemeColor(id: String) {
        _ui.update { it.copy(themeColor = id) }
        store.themeColor = id
        com.studycheck.app.ui.theme.ThemeController.brandId.value = id
    }

    /**
     * 导入打卡记录：把用户选的 CSV 复制到缓存再上传，
     * 服务端逐行校验（重复跳过、重叠拒绝），结果原样给界面。
     */
    fun importCsv(uri: android.net.Uri) {
        viewModelScope.launch {
            _ui.update { it.copy(importing = true, importResult = null, importError = null) }
            var file: java.io.File? = null
            try {
                file = copyToCache(appContext, uri)
                repo.importCsv(file)
                    .onSuccess { r ->
                        _ui.update { it.copy(importing = false, importResult = r) }
                        // 导入的记录要能立刻在今日 / 历史里看到
                        runCatching { repo.syncDay(todayString()) }
                    }
                    .onFailure { e ->
                        _ui.update { it.copy(importing = false, importError = e.friendly()) }
                    }
            } catch (t: Throwable) {
                _ui.update { it.copy(importing = false, importError = t.friendly()) }
            } finally {
                runCatching { file?.delete() }
            }
        }
    }

    fun clearImportResult() { _ui.update { it.copy(importResult = null, importError = null) } }

    fun setBaseUrl(url: String) { _ui.update { it.copy(baseUrl = url) } }

    fun save(nickname: String, goalMin: Int, baseUrl: String, onSaved: (String) -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(saving = true, message = null) }
            auth.setBaseUrl(baseUrl.trim())
            val res = auth.updateProfile(nickname.trim(), goalMin)
            _ui.update { it.copy(saving = false) }
            res.onSuccess { u ->
                auth.setSavedGoal(u.dailyGoalMin)
                store.nickname = u.nickname
                _ui.update { it.copy(nickname = u.nickname, goalMin = u.dailyGoalMin) }
                onSaved("已保存")
            }.onFailure { e ->
                // 服务器不可达时也允许本地保存目标
                auth.setSavedGoal(goalMin)
                onSaved("已保存到本地（${e.friendly()}）")
            }
        }
    }

    fun uploadNow(onMessage: (String) -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(saving = true) }
            val ok = repo.uploadPending()
            _ui.update { it.copy(saving = false) }
            onMessage(if (ok) "队列已上传完成" else "仍有未上传项，稍后自动重试")
        }
    }

    /** 导出到用户选定的文件（SAF），完成后回调提示 */
    fun exportTo(uri: android.net.Uri, kind: com.studycheck.app.data.ExportRepository.Kind, onMessage: (String) -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(exporting = true, exportNote = "正在导出…") }
            exporter.writeTo(uri, kind)
                .onSuccess { size ->
                    val text = "已导出 ${formatSize(size)}"
                    _ui.update { it.copy(exporting = false, exportNote = text) }
                    onMessage(text)
                }
                .onFailure { e ->
                    val text = "导出失败：${e.friendly()}"
                    _ui.update { it.copy(exporting = false, exportNote = text) }
                    onMessage(text)
                }
        }
    }

    /* ---------------- 账号安全 ---------------- */

    fun changePassword(oldPassword: String, newPassword: String, onDone: (String?) -> Unit) {
        if (_ui.value.passwordBusy) return
        viewModelScope.launch {
            _ui.update { it.copy(passwordBusy = true, passwordMessage = null) }
            auth.changePassword(oldPassword, newPassword)
                .onSuccess {
                    _ui.update { it.copy(passwordBusy = false, passwordMessage = null) }
                    onDone(null)
                }
                .onFailure { e ->
                    val msg = e.friendly()
                    _ui.update { it.copy(passwordBusy = false, passwordMessage = msg) }
                    onDone(msg)
                }
        }
    }

    fun clearPasswordMessage() { _ui.update { it.copy(passwordMessage = null) } }

    /** 注销账号：成功后调用方负责跳回登录页 */
    fun deleteAccount(password: String, onDone: (String?) -> Unit) {
        if (_ui.value.deleting) return
        viewModelScope.launch {
            _ui.update { it.copy(deleting = true) }
            auth.deleteAccount(password)
                .onSuccess {
                    _ui.update { it.copy(deleting = false) }
                    onDone(null)
                }
                .onFailure { e ->
                    _ui.update { it.copy(deleting = false) }
                    onDone(e.friendly())
                }
        }
    }

    fun loadUsers() {
        viewModelScope.launch {
            auth.adminUsers().onSuccess { list -> _ui.update { it.copy(users = list) } }
        }
    }

    fun adminResetPassword(id: Long, newPassword: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            auth.adminResetPassword(id, newPassword)
                .onSuccess { onDone(null) }
                .onFailure { e -> onDone(e.friendly()) }
        }
    }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            auth.logout()
            onDone()
        }
    }
}

/** 兼容 Retorfit list 返回，供历史页兜底展示 */
fun CheckinDto.thumbOrPhoto(in0: Boolean): String? =
    if (in0) inThumbUrl ?: inPhotoUrl else outThumbUrl ?: outPhotoUrl

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val auth: AuthRepository,
) : ViewModel() {
    suspend fun login(username: String, password: String): Result<Unit> =
        auth.login(username, password).map { }

    suspend fun register(username: String, password: String, nickname: String): Result<Unit> =
        auth.register(username, password, nickname).map { }

    fun baseUrl(): String = auth.baseUrl()
    fun setBaseUrl(url: String) = auth.setBaseUrl(url)
}

// ------------------------------------------------------------------
// 每日详情（过程留存）：二级页面
// ------------------------------------------------------------------

data class DayDetailUi(
    val day: String = todayString(),
    val checkin: com.studycheck.app.data.CheckinEntity? = null,
    val synced: List<com.studycheck.app.data.EntryEntity> = emptyList(),
    val pending: List<com.studycheck.app.data.PendingEntryEntity> = emptyList(),
    val baseUrl: String = "",
    val loading: Boolean = false,
    val savingNote: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class DayDetailViewModel @Inject constructor(
    private val entries: com.studycheck.app.data.EntryRepository,
    private val checkins: CheckinRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    private val day = todayString()
    private val loading = MutableStateFlow(false)
    private val saving = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val baseUrl = MutableStateFlow(auth.baseUrl())

    private val feedAndCheckin = combine(
        entries.observeFeed(day),
        checkins.observeDay(day),
    ) { feed, c -> feed to c }

    val ui: StateFlow<DayDetailUi> = combine(
        feedAndCheckin,
        loading,
        saving,
        error,
        baseUrl,
    ) { fc, l, s, e, base ->
        DayDetailUi(
            day = day,
            checkin = fc.second,
            synced = fc.first.synced,
            pending = fc.first.pending,
            baseUrl = base,
            loading = l,
            savingNote = s,
            error = e,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        DayDetailUi(day = day, baseUrl = auth.baseUrl()),
    )

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            loading.value = true
            error.value = null
            baseUrl.value = auth.baseUrl()
            entries.syncDay(day).onFailure { error.value = it.friendly() }
            entries.uploadPending()
            checkins.syncDay(day)
            loading.value = false
        }
    }

    /** 手动修正进/出馆时间，成功后本地缓存立刻更新 */
    fun fixTimes(serverId: Long, inAt: String?, outAt: String?, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch {
            checkins.updateTimes(serverId, inAt, outAt)
                .onSuccess { onDone(null) }
                .onFailure { e -> error.value = e.friendly(); onDone(e.friendly()) }
        }
    }

    fun saveNote(text: String, onDone: () -> Unit) {
        if (text.isBlank()) return
        viewModelScope.launch {
            saving.value = true
            error.value = null
            entries.saveNote(day, text.trim())
                .onSuccess { onDone() }
                .onFailure { error.value = it.friendly() }
            saving.value = false
        }
    }

    fun deleteSynced(serverId: Long) {
        viewModelScope.launch {
            entries.delete(serverId).onFailure { error.value = it.friendly() }
        }
    }

    fun deletePending(id: Long) {
        viewModelScope.launch { entries.deletePending(id) }
    }

    fun dismissError() { error.value = null }
}

/** 连拍页：拍完一次性全部入队上传 */
@HiltViewModel
class BurstViewModel @Inject constructor(
    private val entries: com.studycheck.app.data.EntryRepository,
) : ViewModel() {

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun submit(photos: List<File>, note: String, onDone: () -> Unit) {
        if (photos.isEmpty()) { onDone(); return }
        viewModelScope.launch {
            _saving.value = true
            _error.value = null
            runCatching {
                entries.enqueueBurst(todayString(), note.trim(), photos)
            }.onSuccess {
                // 立即尝试上传；失败的留给 WorkManager
                entries.uploadPending()
                onDone()
            }.onFailure { _error.value = it.friendly() }
            _saving.value = false
        }
    }

    fun clearError() { _error.value = null }
}

// ------------------------------------------------------------------
// 历史某一天的详情（点击历史记录进入）
// ------------------------------------------------------------------

data class DayArchiveUi(
    val day: String = "",
    val checkin: com.studycheck.app.data.CheckinEntity? = null,
    val entries: List<com.studycheck.app.data.EntryEntity> = emptyList(),
    val baseUrl: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    /** 这一天的分段 */
    val sessions: List<com.studycheck.app.data.SessionEntity> = emptyList(),
)

@HiltViewModel
class DayArchiveViewModel @Inject constructor(
    savedState: androidx.lifecycle.SavedStateHandle,
    private val checkins: CheckinRepository,
    private val entries: com.studycheck.app.data.EntryRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    private val day: String = savedState.get<String>("day") ?: todayString()
    private val loading = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val baseUrl = MutableStateFlow(auth.baseUrl())

    private val archiveSnap = combine(
        checkins.observeDay(day),
        entries.observeDay(day),
        checkins.observeSessions(day),
    ) { c, e, segs -> Triple(c, e, segs) }

    val ui: StateFlow<DayArchiveUi> = combine(
        archiveSnap,
        loading,
        error,
        baseUrl,
    ) { snap, l, err, base ->
        DayArchiveUi(
            day = day,
            checkin = snap.first,
            entries = snap.second,
            baseUrl = base,
            loading = l,
            error = err,
            sessions = snap.third,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        DayArchiveUi(day = day, baseUrl = auth.baseUrl()),
    )

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            loading.value = true
            error.value = null
            baseUrl.value = auth.baseUrl()
            // 打卡记录只取本地缓存无所谓，详情条目按天拉一次
            entries.syncDay(day).onFailure { error.value = it.friendly() }
            checkins.fetchSessions(day).onFailure { error.value = it.friendly() }
            loading.value = false
        }
    }

    fun fixTimes(serverId: Long, inAt: String?, outAt: String?, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch {
            checkins.updateTimes(serverId, inAt, outAt)
                .onSuccess { onDone(null) }
                .onFailure { e -> error.value = e.friendly(); onDone(e.friendly()) }
        }
    }

    /** 只修正某一段（subject 传 null 表示不改科目） */
    fun fixSession(
        id: Long,
        inIso: String,
        outIso: String?,
        subject: String? = null,
        onDone: (String?) -> Unit = {},
    ) {
        viewModelScope.launch {
            checkins.patchSession(
                id,
                com.studycheck.app.data.PatchCheckinReq(inAt = inIso, outAt = outIso, subject = subject),
            )
                .onSuccess { onDone(null) }
                .onFailure { e -> error.value = e.friendly(); onDone(e.friendly()) }
        }
    }

    fun deleteSession(id: Long) {
        viewModelScope.launch {
            checkins.deleteSession(id, day).onFailure { error.value = it.friendly() }
        }
    }

    fun dismissError() { error.value = null }
}

// ------------------------------------------------------------------
// 全局搜索
// ------------------------------------------------------------------

data class SearchUi(
    val query: String = "",
    val items: List<com.studycheck.app.data.SearchItemDto> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val searched: Boolean = false,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repo: CheckinRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(SearchUi())
    val ui: StateFlow<SearchUi> = _ui.asStateFlow()

    fun updateQuery(q: String) { _ui.update { it.copy(query = q) } }

    fun search() {
        val q = _ui.value.query.trim()
        if (q.isEmpty()) {
            _ui.update { it.copy(items = emptyList(), searched = false, error = null) }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            repo.search(q)
                .onSuccess { list -> _ui.update { it.copy(items = list, loading = false, searched = true) } }
                .onFailure { e -> _ui.update { it.copy(loading = false, error = e.friendly(), searched = true) } }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1 shl 10 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}


/** 把用户通过系统文件选择器选中的 CSV 复制到缓存目录（SAF 的 Uri 不能直接给 OkHttp） */
private fun copyToCache(context: android.content.Context, uri: android.net.Uri): java.io.File {
    val dir = java.io.File(context.cacheDir, "import").apply { mkdirs() }
    val name = "import-" + System.currentTimeMillis() + ".csv"
    val out = java.io.File(dir, name)
    context.contentResolver.openInputStream(uri)?.use { input ->
        out.outputStream().use { input.copyTo(it) }
    } ?: throw IllegalStateException("读不到这个文件")
    if (out.length() <= 0) throw IllegalStateException("文件是空的")
    if (out.length() > 5L * 1024 * 1024) {
        out.delete()
        throw IllegalStateException("文件太大（超过 5MB）")
    }
    return out
}
