package com.swipegallery.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

const val ACTION_KEEP = "KEEP"
const val ACTION_DELETE = "DELETE"
const val ACTION_SKIP = "SKIP"

/**
 * 每张图最后的决定。KEEP/DELETE 的条目会被排除在下一轮队列之外，
 * SKIP 的条目会重新回到队列里（但不会马上出现，顺序是重新打乱的）。
 */
@Entity(tableName = "decisions")
data class Decision(
    @PrimaryKey val mediaId: Long,
    val action: String,
    val decidedAt: Long,
    val dateTaken: Long,
    val isVideo: Boolean,
    val uri: String,
)

data class ActionCount(
    val action: String,
    val count: Int,
)

@Dao
interface DecisionDao {

    /** 已经明确处理过的（保留 / 删除），下次不再刷到。 */
    @Query("SELECT mediaId FROM decisions WHERE action IN ('KEEP', 'DELETE')")
    suspend fun settledIds(): List<Long>

    @Query("SELECT * FROM decisions WHERE mediaId = :id LIMIT 1")
    suspend fun find(id: Long): Decision?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(decision: Decision)

    /** 「跳过」不应该覆盖已经做过的明确决定。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putIfAbsent(decision: Decision)

    @Query("DELETE FROM decisions WHERE mediaId = :id")
    suspend fun remove(id: Long)

    @Query("SELECT action, COUNT(*) AS count FROM decisions GROUP BY action")
    suspend fun counts(): List<ActionCount>
}

@Database(entities = [Decision::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun decisionDao(): DecisionDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "swipe.db",
            ).build().also { instance = it }
        }
    }
}
