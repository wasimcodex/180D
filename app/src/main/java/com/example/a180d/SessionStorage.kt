package com.example.a180d

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
)

data class SessionSummary(val session: SessionEntity, val stats: SessionStats?)

/**
 * The user-supplied title, or blank if there is none.
 *
 * Sessions saved before a blank title could be stored got a synthetic
 * "Session <date>" label written into the row. That just restates the date the
 * UI already shows beside it, so it reads as untitled — and since there is no
 * rename affordance, the only place to undo it is at display time. Best-effort:
 * it is matched against the format the device's *current* locale produces.
 */
val SessionEntity.userTitle: String
    get() {
        val trimmed = title.trim()
        return if (trimmed == legacyDefaultTitle(startedAtMs)) "" else trimmed
    }

private fun legacyDefaultTitle(startedAtMs: Long): String =
    "Session ${SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(startedAtMs))}"

@Entity(
    tableName = "heart_rate_samples",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timestampMs: Long,
    val bpm: Int,
)

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Update
    suspend fun update(session: SessionEntity)

    @Delete
    suspend fun delete(session: SessionEntity)

    @Query("SELECT * FROM sessions ORDER BY startedAtMs DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: Long): SessionEntity?
}

data class SessionStats(val sessionId: Long, val avgBpm: Double, val maxBpm: Int, val minBpm: Int)

@Dao
interface SampleDao {
    @Insert
    suspend fun insertAll(samples: List<SampleEntity>)

    @Query("SELECT * FROM heart_rate_samples WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    suspend fun getForSession(sessionId: Long): List<SampleEntity>

    @Query("SELECT COUNT(*) FROM heart_rate_samples WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: Long): Int

    @Query("SELECT sessionId, AVG(bpm) AS avgBpm, MAX(bpm) AS maxBpm, MIN(bpm) AS minBpm FROM heart_rate_samples GROUP BY sessionId")
    fun observeStats(): Flow<List<SessionStats>>

    @Query("SELECT sessionId, AVG(bpm) AS avgBpm, MAX(bpm) AS maxBpm, MIN(bpm) AS minBpm FROM heart_rate_samples WHERE sessionId = :sessionId GROUP BY sessionId")
    suspend fun getStatsForSession(sessionId: Long): SessionStats?
}

@Database(entities = [SessionEntity::class, SampleEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun sampleDao(): SampleDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "180d.db",
            ).build().also { instance = it }
        }
    }
}

/**
 * Owns session persistence: creates a provisional session row when a BLE
 * session starts, batches sample writes, and leaves the row for the user to
 * either title-and-keep or discard once the session ends (see
 * `pendingSession`). Room DB, one row per sample, per CLAUDE.md.
 */
class SessionRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)

    private val sampleBufferMutex = Mutex()
    private val sampleBuffer = mutableListOf<SampleEntity>()
    private var currentSessionId: Long? = null

    private val _pendingSession = MutableStateFlow<SessionEntity?>(null)
    val pendingSession: StateFlow<SessionEntity?> = _pendingSession.asStateFlow()

    val sessions: Flow<List<SessionEntity>> = database.sessionDao().observeAll()

    /** Sessions paired with their aggregate BPM stats, for the history table. */
    val sessionSummaries: Flow<List<SessionSummary>> = combine(sessions, database.sampleDao().observeStats()) { sessionList, statsList ->
        val statsById = statsList.associateBy { it.sessionId }
        sessionList.map { SessionSummary(it, statsById[it.id]) }
    }

    suspend fun startSession(startedAtMs: Long) {
        currentSessionId = database.sessionDao().insert(
            SessionEntity(title = "", startedAtMs = startedAtMs, endedAtMs = 0L),
        )
    }

    suspend fun recordSample(sample: HeartRateSample) {
        val sessionId = currentSessionId ?: return
        sampleBufferMutex.withLock {
            sampleBuffer.add(SampleEntity(sessionId = sessionId, timestampMs = sample.timestampMs, bpm = sample.bpm))
            if (sampleBuffer.size >= SAMPLE_BATCH_SIZE) flushLocked()
        }
    }

    /** Flushes buffered samples and, if any were recorded, exposes the session via [pendingSession]. */
    suspend fun endSession() {
        val sessionId = currentSessionId ?: return
        currentSessionId = null
        sampleBufferMutex.withLock { flushLocked() }

        val session = database.sessionDao().getById(sessionId) ?: return
        if (database.sampleDao().countForSession(sessionId) > 0) {
            val ended = session.copy(endedAtMs = System.currentTimeMillis())
            database.sessionDao().update(ended)
            _pendingSession.value = ended
        } else {
            database.sessionDao().delete(session)
        }
    }

    suspend fun discardPendingSession() {
        val session = _pendingSession.value ?: return
        _pendingSession.value = null
        database.sessionDao().delete(session)
    }

    suspend fun savePendingSession(title: String) {
        val session = _pendingSession.value ?: return
        _pendingSession.value = null
        // A blank title is stored as-is; the listings and the detail screen derive
        // a date-based label at display time rather than baking one into the row.
        database.sessionDao().update(session.copy(title = title.trim()))
    }

    suspend fun deleteSession(session: SessionEntity) {
        database.sessionDao().delete(session)
    }

    suspend fun statsForSession(sessionId: Long): SessionStats? = database.sampleDao().getStatsForSession(sessionId)

    suspend fun samplesForSession(sessionId: Long): List<SampleEntity> = withContext(Dispatchers.IO) {
        database.sampleDao().getForSession(sessionId)
    }

    /** Writes the session's samples to a CSV in the cache dir and returns a FileProvider URI for sharing. */
    suspend fun exportCsv(session: SessionEntity): Uri = withContext(Dispatchers.IO) {
        val samples = database.sampleDao().getForSession(session.id)
        val exportDir = File(appContext.cacheDir, "exports").apply { mkdirs() }
        val file = File(exportDir, "session_${session.id}.csv")
        val timeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        file.bufferedWriter().use { writer ->
            writer.write("timestamp,bpm\n")
            samples.forEach { sample ->
                writer.write("${timeFormat.format(Date(sample.timestampMs))},${sample.bpm}\n")
            }
        }
        FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
    }

    private suspend fun flushLocked() {
        if (sampleBuffer.isEmpty()) return
        database.sampleDao().insertAll(sampleBuffer.toList())
        sampleBuffer.clear()
    }

    companion object {
        private const val SAMPLE_BATCH_SIZE = 10
    }
}
