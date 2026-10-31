package com.studycheck.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** 服务端已确认的打卡记录（本地缓存，离线也能看历史） */
@Entity(tableName = "checkins")
data class CheckinEntity(
    @PrimaryKey val day: String,
    val serverId: Long,
    val inAt: String?,
    val outAt: String?,
    val inPhoto: String?,
    val outPhoto: String?,
    val inThumb: String?,
    val outThumb: String?,
    val inNote: String,
    val outNote: String,
    val inPlace: String,
    val outPlace: String,
    val durationSec: Long,
    val updatedAt: String,
)

/**
 * 一天里的某一「段」：每次进馆到出馆是一段。
 * 服务端 checkins 仍是按天聚合，这里额外缓存分段，用于一天多段的展示与修正。
 */
@Entity(tableName = "sessions", indices = [Index("day")])
data class SessionEntity(
    @PrimaryKey val serverId: Long,
    val day: String,
    val inAt: String?,
    val outAt: String?,
    val inPhoto: String?,
    val outPhoto: String?,
    val inThumb: String?,
    val outThumb: String?,
    val inNote: String,
    val outNote: String,
    val inPlace: String,
    val outPlace: String,
    val durationSec: Long,
    val updatedAt: String,
    /** 科目 / 标签（1.8.0 起）；老数据与聚合行是空串 */
    val subject: String = "",
)

/** 待上传队列：照片先落本地，弱网/无网时由 WorkManager 自动重试 */
@Entity(tableName = "pending_uploads")
data class PendingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String, // "in" | "out"
    val day: String,
    val takenAt: String,
    val note: String,
    val place: String,
    val photoPath: String,
    val idemKey: String,
    /** 进馆科目（出馆不用），空串表示没填 */
    val subject: String = "",
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
)

/** 每日详情条目（服务端已确认）：一张照片 + 可选笔记 */
@Entity(tableName = "entries")
data class EntryEntity(
    @PrimaryKey val serverId: Long,
    val day: String,
    val photo: String?,
    val thumb: String?,
    val note: String,
    val createdAt: String,
)

/** 每日详情待上传：连拍的多张照片分条入队 */
@Entity(tableName = "pending_entries")
data class PendingEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: String,
    val note: String,
    val photoPath: String,
    val idemKey: String,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
)

@Dao
interface EntryDao {
    @Query("SELECT * FROM entries WHERE day = :day ORDER BY serverId DESC")
    fun observeDay(day: String): Flow<List<EntryEntity>>

    @Upsert
    suspend fun upsert(items: List<EntryEntity>)

    @Query("DELETE FROM entries WHERE serverId = :serverId")
    suspend fun delete(serverId: Long)

    @Query("DELETE FROM entries WHERE day = :day")
    suspend fun deleteDay(day: String)

    @Query("DELETE FROM entries")
    suspend fun clear()
}

@Dao
interface PendingEntryDao {
    @Query("SELECT * FROM pending_entries WHERE day = :day ORDER BY createdAt ASC")
    fun observeForDay(day: String): Flow<List<PendingEntryEntity>>

    @Query("SELECT * FROM pending_entries ORDER BY createdAt ASC")
    suspend fun all(): List<PendingEntryEntity>

    @Query("SELECT COUNT(*) FROM pending_entries")
    fun observeCount(): Flow<Int>

    @Insert
    suspend fun insert(p: PendingEntryEntity): Long

    @Update
    suspend fun update(p: PendingEntryEntity)

    @Query("DELETE FROM pending_entries WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface CheckinDao {
    @Query("SELECT * FROM checkins WHERE day = :day LIMIT 1")
    fun observeDay(day: String): Flow<CheckinEntity?>

    @Query("SELECT * FROM checkins ORDER BY day DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CheckinEntity>>

    @Query("SELECT * FROM checkins WHERE day LIKE :prefix || '%' ORDER BY day DESC")
    fun observeMonth(prefix: String): Flow<List<CheckinEntity>>

    @Query("SELECT * FROM checkins ORDER BY day DESC")
    suspend fun all(): List<CheckinEntity>

    @Upsert
    suspend fun upsert(items: List<CheckinEntity>)

    @Query("DELETE FROM checkins WHERE day = :day")
    suspend fun deleteDay(day: String)

    @Query("DELETE FROM checkins WHERE serverId = :serverId")
    suspend fun delete(serverId: Long)

    @Query("DELETE FROM checkins")
    suspend fun clear()
}

@Dao
interface PendingDao {
    @Query("SELECT * FROM pending_uploads WHERE day = :day")
    fun observeForDay(day: String): Flow<List<PendingEntity>>

    @Query("SELECT * FROM pending_uploads ORDER BY createdAt ASC")
    suspend fun all(): List<PendingEntity>

    @Query("SELECT COUNT(*) FROM pending_uploads")
    fun observeCount(): Flow<Int>

    @Insert
    suspend fun insert(p: PendingEntity): Long

    @Insert
    suspend fun insertIgnore(p: PendingEntity): Long

    @Update
    suspend fun update(p: PendingEntity)

    @Query("DELETE FROM pending_uploads WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM pending_uploads")
    suspend fun clear()
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE day = :day ORDER BY inAt ASC, serverId ASC")
    fun observeDay(day: String): Flow<List<SessionEntity>>

    @Upsert
    suspend fun upsert(items: List<SessionEntity>)

    @Query("DELETE FROM sessions WHERE day = :day")
    suspend fun deleteDay(day: String)

    @Query("DELETE FROM sessions WHERE serverId = :serverId")
    suspend fun delete(serverId: Long)

    @Query("DELETE FROM sessions")
    suspend fun clear()
}

@Database(
    entities = [
        CheckinEntity::class,
        PendingEntity::class,
        EntryEntity::class,
        PendingEntryEntity::class,
        SessionEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun checkinDao(): CheckinDao
    abstract fun pendingDao(): PendingDao
    abstract fun sessionDao(): SessionDao
    abstract fun entryDao(): EntryDao
    abstract fun pendingEntryDao(): PendingEntryDao
}

fun CheckinDto.toEntity() = CheckinEntity(
    day = day,
    serverId = id,
    inAt = inAt,
    outAt = outAt,
    inPhoto = inPhotoUrl,
    outPhoto = outPhotoUrl,
    inThumb = inThumbUrl,
    outThumb = outThumbUrl,
    inNote = inNote,
    outNote = outNote,
    inPlace = inPlace,
    outPlace = outPlace,
    durationSec = durationSec,
    updatedAt = updatedAt,
)

fun CheckinDto.toSessionEntity() = SessionEntity(
    serverId = id,
    day = day,
    inAt = inAt,
    outAt = outAt,
    inPhoto = inPhotoUrl,
    outPhoto = outPhotoUrl,
    inThumb = inThumbUrl,
    outThumb = outThumbUrl,
    inNote = inNote,
    outNote = outNote,
    inPlace = inPlace,
    outPlace = outPlace,
    durationSec = durationSec,
    updatedAt = updatedAt,
    subject = subject,
)

fun EntryDto.toEntity() = EntryEntity(
    serverId = id,
    day = day,
    photo = photoUrl,
    thumb = thumbUrl ?: photoUrl,
    note = note,
    createdAt = createdAt,
)
