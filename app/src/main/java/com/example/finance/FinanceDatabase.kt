package com.example.finance

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CategoryEntity::class,
        DebitEntity::class,
        IgnoredSmsEntity::class,
        SenderCategoryMemoryEntity::class,
        KeywordRuleEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun financeDao(): FinanceDao

    companion object {
        @Volatile
        private var instance: FinanceDatabase? = null

        fun getInstance(context: Context): FinanceDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FinanceDatabase::class.java,
                    "finance.db"
                )
                    .addCallback(SeedCallback())
                    .build()
                    .also { instance = it }
            }
        }
    }

    private class SeedCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            val now = System.currentTimeMillis()
            val categories = listOf(
                Triple("Uncategorized", 0xFF6B7280.toInt(), 0),
                Triple("Food", 0xFFFF9F0A.toInt(), 1),
                Triple("Fuel", 0xFF4086FF.toInt(), 2),
                Triple("Home", 0xFF32D74B.toInt(), 3),
                Triple("Bike Maintenance", 0xFFFF453A.toInt(), 4),
                Triple("Groceries", 0xFF00E5FF.toInt(), 5),
                Triple("Other", 0xFF8A8A93.toInt(), 6)
            )
            categories.forEachIndexed { index, (name, color, order) ->
                val isSystem = if (name == "Uncategorized") 1 else 0
                db.execSQL(
                    """
                    INSERT INTO categories (name, colorArgb, sortOrder, isSystem, createdAt)
                    VALUES ('$name', $color, $order, $isSystem, $now)
                    """.trimIndent()
                )
            }
        }
    }
}
