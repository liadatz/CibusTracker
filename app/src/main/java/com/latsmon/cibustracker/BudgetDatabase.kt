package com.latsmon.cibustracker

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "spends")
data class Spend(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val amount: Double,
    val timestamp: Long = System.currentTimeMillis(),
    val businessName: String? = null
)

@Dao
interface SpendDao {
    @Insert
    suspend fun insert(spend: Spend)

    @Insert
    fun insertBlocking(spend: Spend)

    @Delete
    suspend fun delete(spend: Spend)

    @Query("SELECT * FROM spends ORDER BY timestamp DESC")
    fun getAllSpends(): Flow<List<Spend>>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM spends WHERE timestamp >= :startOfMonth")
    fun getTotalSpentSince(startOfMonth: Long): Flow<Double>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM spends WHERE timestamp >= :startOfMonth")
    fun getTotalSpentSinceBlocking(startOfMonth: Long): Double

    @Query("DELETE FROM spends")
    suspend fun deleteAll()
}

@Database(entities = [Spend::class], version = 2)
abstract class BudgetDatabase : RoomDatabase() {
    abstract fun spendDao(): SpendDao

    companion object {
        @Volatile private var INSTANCE: BudgetDatabase? = null

        fun getDatabase(context: Context): BudgetDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    BudgetDatabase::class.java,
                    "budget_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build().also { INSTANCE = it }
            }
        }

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE spends ADD COLUMN businessName TEXT")
            }
        }
    }
}