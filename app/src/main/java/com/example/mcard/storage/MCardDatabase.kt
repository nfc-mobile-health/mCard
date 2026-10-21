package com.example.mcard.storage

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

@Entity(tableName = "basic_info")
data class BasicInfoEntity(
    @PrimaryKey val id: Int = 0,
    val block: ByteArray,
)

@Entity(tableName = "record_blobs")
data class RecordBlobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val blob: ByteArray,
    val blobLength: Int,
)

@Dao
interface MCardDao {
    @Query("SELECT block FROM basic_info WHERE id = 0")
    suspend fun basicInfoBlock(): ByteArray?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun writeBasicInfo(entity: BasicInfoEntity): Long

    @Insert
    suspend fun insertRecord(entity: RecordBlobEntity): Long

    @Query("SELECT * FROM record_blobs ORDER BY id DESC LIMIT 1")
    suspend fun newestRecord(): RecordBlobEntity?

    @Query("SELECT * FROM record_blobs ORDER BY id ASC LIMIT 1")
    suspend fun oldestRecord(): RecordBlobEntity?

    @Query("DELETE FROM record_blobs WHERE id = :id")
    suspend fun deleteRecord(id: Long): Int

    @Query("SELECT COUNT(*) FROM record_blobs")
    suspend fun recordCount(): Int

    @Query("SELECT COALESCE(SUM(blobLength + 1), 0) FROM record_blobs")
    suspend fun usedRecordBytes(): Int

    @Query("SELECT * FROM record_blobs ORDER BY id DESC LIMIT :limit OFFSET :offset")
    suspend fun newestRecords(limit: Int, offset: Int): List<RecordBlobEntity>
}

@Database(entities = [BasicInfoEntity::class, RecordBlobEntity::class], version = 1, exportSchema = false)
abstract class MCardDatabase : RoomDatabase() {
    abstract fun dao(): MCardDao

    companion object {
        @Volatile private var instance: MCardDatabase? = null

        fun get(context: Context): MCardDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MCardDatabase::class.java,
                "mcard.db",
            ).build().also { instance = it }
        }
    }
}
