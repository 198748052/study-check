package com.studycheck.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studycheck.app.data.AuthRepository
import com.studycheck.app.data.CheckinDto
import com.studycheck.app.data.CheckinEntity
import com.studycheck.app.data.CheckinRepository
import com.studycheck.app.data.DaySnapshot
import com.studycheck.app.data.StatsDto
import com.studycheck.app.data.combineDay
import com.studycheck.app.data.nowIso
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
)

@HiltViewModel
class TodayViewModel @Inject constructor(
    private val repo: CheckinRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    private val day = todayString()
    private val stats = MutableStateFlow<StatsDto?>(null)
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val goal = MutableStateFlow(auth.savedGoal())
    private val baseUrl = MutableStateFlow(auth.baseUrl())

    private val snap: kotlinx.coroutines.flow.Flow<DaySnapshot> =
        combineDay(repo.observeDay(day), repo.observePending(day))

    val ui: StateFlow<TodayUi> = combine(snap, stats, busy, error, goal) { s, st, b, e, g ->
        val c: com.studycheck.app.data.CheckinEntity? = s.checkin
        TodayUi(
            day = day,
            state = when {
                c?.outAt != null -> DayState.Finished
                c?.inAt != null -> DayState.InLibrary
                else -> DayState.NotStarted
            },
            inAt = c?.inAt,
            outAt = c?.outAt,
            inNote = c?.inNote ?: "",
            outNote = c?.outNote ?: "",
            inPlace = c?.inPlace ?: "",
            outPlace = c?.outPlace ?: "",
            inThumb = c?.inThumb ?: c?.inPhoto,
            outThumb = c?.outThumb ?: c?.outPhoto,
            elapsedSec = c?.durationSec ?: 0,
            pending = s.pending.size,
            goalMin = g,
            weekDays = st?.weekDays ?: 0,
            weekMinutes = st?.weekMinutes ?: 0,
            streak = st?.currentStreak ?: 0,
            longest = st?.longestStreak ?: 0,
            busy = b,
            error = e,
            baseUrl = baseUrl.value,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUi(goalMin = auth.savedGoal()))

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            baseUrl.value = auth.baseUrl()
            repo.syncDay(day).onFailure { error.value = it.friendly() }
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

    fun dismissError() { error.value = null }
}

/** 拍照后提交：先落本地队列，交给 WorkManager 上传 */
@HiltViewModel
class CheckinSubmitViewModel @Inject constructor(
    private val repo: CheckinRepository,
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
        takenAt: String = nowIso(),
        onQueued: () -> Unit,
    ) {
        viewModelScope.launch {
            _saving.value = true
            _error.value = null
            val result = runCatching {
                repo.enqueue(kind, todayString(), takenAt, note, place, photo)
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
}

data class StatsUi(
    val stats: StatsDto? = null,
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
                .onSuccess { s -> _ui.update { it.copy(stats = s, loading = false) } }
                .onFailure { e -> _ui.update { it.copy(loading = false, error = e.friendly()) } }
        }
    }
}

data class MeUi(
    val username: String = "",
    val nickname: String = "",
    val goalMin: Int = 120,
    val baseUrl: String = "",
    val reminderEnabled: Boolean = true,
    val reminderHour: Int = 20,
    val saving: Boolean = false,
    val message: String? = null,
    val pendingCount: Int = 0,
    val exporting: Boolean = false,
    val exportNote: String? = null,
)

@HiltViewModel
class MeViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val repo: CheckinRepository,
    private val exporter: com.studycheck.app.data.ExportRepository,
    private val store: com.studycheck.app.data.TokenStore,
) : ViewModel() {

    private val _ui = MutableStateFlow(
        MeUi(
            username = store.username ?: "",
            nickname = store.nickname ?: "",
            goalMin = store.savedGoal,
            baseUrl = store.baseUrl,
            reminderEnabled = store.reminderEnabled,
            reminderHour = store.reminderHour,
        ),
    )
    val ui: StateFlow<MeUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            repo.observePendingCount().collect { n -> _ui.update { it.copy(pendingCount = n) } }
        }
        viewModelScope.launch {
            auth.refreshProfile().onSuccess { u ->
                _ui.update { it.copy(username = u.username, nickname = u.nickname, goalMin = u.dailyGoalMin) }
            }
        }
    }

    fun setGoal(v: Int) { _ui.update { it.copy(goalMin = v.coerceIn(10, 960)) } }

    fun setReminder(enabled: Boolean, hour: Int) {
        _ui.update { it.copy(reminderEnabled = enabled, reminderHour = hour) }
        store.reminderEnabled = enabled
        store.reminderHour = hour
    }

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

    val ui: StateFlow<DayArchiveUi> = combine(
        checkins.observeDay(day),
        entries.observeDay(day),
        loading,
        error,
        baseUrl,
    ) { c, e, l, err, base ->
        DayArchiveUi(
            day = day,
            checkin = c,
            entries = e,
            baseUrl = base,
            loading = l,
            error = err,
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
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1 shl 10 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
