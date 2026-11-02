package app.kharjyar.baba

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tx", indices = [Index(value = ["fingerprint"], unique = true)])
data class Tx(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fingerprint: String,
    val isDebit: Boolean,
    val amountRial: Long,
    val balanceRial: Long?,
    val merchant: String?,
    val category: String?,
    val time: Long,
    val body: String,
)

@Dao
interface TxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(t: Tx): Long
    @Query("SELECT * FROM tx ORDER BY time DESC") fun all(): Flow<List<Tx>>
    @Query("UPDATE tx SET category = :c WHERE id = :id") suspend fun setCategory(id: Long, c: String?)
    @Query("SELECT SUM(amountRial) FROM tx WHERE isDebit = 1 AND time >= :since") fun spent(since: Long): Flow<Long?>
}

@Database(entities = [Tx::class], version = 1, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun dao(): TxDao

    companion object {
        @Volatile private var inst: Db? = null
        fun get(c: Context): Db = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, Db::class.java, "baba.db").build().also { inst = it }
        }
    }
}
