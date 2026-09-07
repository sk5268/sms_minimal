package com.example.finance

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.SmsIdentity

@Database(
    entities = [
        CategoryEntity::class,
        DebitEntity::class,
        IgnoredSmsEntity::class,
        SenderCategoryMemoryEntity::class,
        KeywordRuleEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun financeDao(): FinanceDao

    companion object {
        @Volatile
        private var instance: FinanceDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `debits_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `messageKey` TEXT NOT NULL,
                        `amountPaise` INTEGER NOT NULL,
                        `sender` TEXT NOT NULL,
                        `snippet` TEXT NOT NULL,
                        `categoryId` INTEGER NOT NULL,
                        `occurredAt` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `autoCategorized` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                val seenKeys = HashSet<String>()
                db.query(
                    "SELECT id, sender, snippet, amountPaise, categoryId, occurredAt, createdAt, autoCategorized FROM debits"
                ).use { cursor ->
                    val idIndex = cursor.getColumnIndex("id")
                    val senderIndex = cursor.getColumnIndex("sender")
                    val snippetIndex = cursor.getColumnIndex("snippet")
                    val amountIndex = cursor.getColumnIndex("amountPaise")
                    val categoryIndex = cursor.getColumnIndex("categoryId")
                    val occurredIndex = cursor.getColumnIndex("occurredAt")
                    val createdIndex = cursor.getColumnIndex("createdAt")
                    val autoIndex = cursor.getColumnIndex("autoCategorized")
                    val insert = db.compileStatement(
                        """
                        INSERT INTO `debits_new`
                            (id, messageKey, amountPaise, sender, snippet, categoryId, occurredAt, createdAt, autoCategorized)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent()
                    )
                    while (cursor.moveToNext()) {
                        val sender = cursor.getString(senderIndex).orEmpty()
                        val snippet = cursor.getString(snippetIndex).orEmpty()
                        val occurredAt = cursor.getLong(occurredIndex)
                        val messageKey = SmsIdentity.key(occurredAt, sender, snippet)
                        if (!seenKeys.add(messageKey)) continue
                        insert.clearBindings()
                        insert.bindLong(1, cursor.getLong(idIndex))
                        insert.bindString(2, messageKey)
                        insert.bindLong(3, cursor.getLong(amountIndex))
                        insert.bindString(4, sender)
                        insert.bindString(5, snippet)
                        insert.bindLong(6, cursor.getLong(categoryIndex))
                        insert.bindLong(7, occurredAt)
                        insert.bindLong(8, cursor.getLong(createdIndex))
                        insert.bindLong(9, cursor.getLong(autoIndex))
                        insert.executeInsert()
                    }
                    insert.close()
                }

                db.execSQL("DROP TABLE `debits`")
                db.execSQL("ALTER TABLE `debits_new` RENAME TO `debits`")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_debits_messageKey` ON `debits` (`messageKey`)")

                // Old ignore rows were only a recycled telephony _id. They cannot be
                // mapped to a real message, so they are dropped rather than poisoning
                // future transactions that inherit that id.
                db.execSQL("DROP TABLE IF EXISTS `ignored_sms`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ignored_sms` (`messageKey` TEXT NOT NULL, PRIMARY KEY(`messageKey`))"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `debits` ADD COLUMN `note` TEXT DEFAULT NULL")
                db.execSQL("UPDATE `debits` SET `note` = `snippet` WHERE `messageKey` LIKE 'manual:%' AND `snippet` != 'Manual entry'")
            }
        }

        fun getInstance(context: Context): FinanceDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FinanceDatabase::class.java,
                    "finance.db"
                )
                    .createFromAsset("finance.db")
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
                Triple("Uncategorized", 0xFF6B7280.toInt(), 0), // Slate Grey
                Triple("Food", 0xFFFF9F0A.toInt(), 1),          // Orange
                Triple("Fuel", 0xFF4086FF.toInt(), 2),          // Electric Blue
                Triple("Home", 0xFF32D74B.toInt(), 3),          // Neon Green
                Triple("Bike Maintenance", 0xFFFF453A.toInt(), 4), // Red
                Triple("Groceries", 0xFF00E5FF.toInt(), 5),     // Neon Cyan
                Triple("Other", 0xFFBF5AF2.toInt(), 6)          // Purple
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
