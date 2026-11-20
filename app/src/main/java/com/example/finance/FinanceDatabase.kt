package com.example.finance

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.PayeeParser
import com.example.SmsIdentity

@Database(
    entities = [
        CategoryEntity::class,
        DebitEntity::class,
        IgnoredSmsEntity::class,
        KeywordRuleEntity::class
    ],
    version = 5,
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

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `categories` ADD COLUMN `parentCategoryId` INTEGER DEFAULT NULL")
            }
        }

        /**
         * Tag memory now keys on who was paid and where, not on the bank that sent
         * the SMS. The old bank-keyed memory is dropped: it mapped every payment from
         * one account to whatever category was picked last.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf(
                    "ALTER TABLE `debits` ADD COLUMN `payeeKey` TEXT DEFAULT NULL",
                    "ALTER TABLE `debits` ADD COLUMN `payeeName` TEXT DEFAULT NULL",
                    "ALTER TABLE `debits` ADD COLUMN `lat` REAL DEFAULT NULL",
                    "ALTER TABLE `debits` ADD COLUMN `lng` REAL DEFAULT NULL",
                    "ALTER TABLE `debits` ADD COLUMN `locAccuracyM` REAL DEFAULT NULL",
                    "ALTER TABLE `debits` ADD COLUMN `tagSource` TEXT NOT NULL DEFAULT 'none'",
                    "ALTER TABLE `debits` ADD COLUMN `tripKm` REAL DEFAULT NULL",
                    "ALTER TABLE `debits` ADD COLUMN `litres` REAL DEFAULT NULL",
                    "ALTER TABLE `debits` ADD COLUMN `fuelPricePerL` REAL DEFAULT NULL",
                    "ALTER TABLE `debits` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0",
                    "CREATE INDEX IF NOT EXISTS `index_debits_payeeKey` ON `debits` (`payeeKey`)",
                    "CREATE INDEX IF NOT EXISTS `index_debits_lat` ON `debits` (`lat`)",
                    "DROP TABLE IF EXISTS `sender_category_memory`",
                    "UPDATE `debits` SET `updatedAt` = `createdAt`"
                ).forEach { db.execSQL(it) }

                // Past manual choices seed the new memory; old auto-tags never teach it.
                db.execSQL(
                    """
                    UPDATE `debits` SET `tagSource` = CASE
                        WHEN `autoCategorized` = 1 THEN 'legacy_auto'
                        WHEN `categoryId` IN (SELECT `id` FROM `categories` WHERE `name` = 'Uncategorized') THEN 'none'
                        ELSE 'manual' END
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE `debits` SET `fuelPricePerL` = ${Fuel.DEFAULT_PRICE_PER_LITRE},
                        `litres` = (`amountPaise` / 100.0) / ${Fuel.DEFAULT_PRICE_PER_LITRE}
                    WHERE `categoryId` IN (
                        SELECT `id` FROM `categories` WHERE LOWER(`name`) IN ('fuel', 'petrol', 'diesel')
                    )
                    """.trimIndent()
                )

                val updates = mutableListOf<Triple<Long, String, String>>()
                db.query("SELECT id, messageKey, sender, snippet FROM debits").use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val isManual = c.getString(1).orEmpty().startsWith("manual:")
                        val payee = if (isManual) {
                            val name = c.getString(2).orEmpty().trim()
                            if (name.isBlank() || name.equals("Cash", ignoreCase = true)) null
                            else PayeeParser.Payee("N:" + PayeeParser.normaliseKey(name), name)
                        } else {
                            PayeeParser.parse(c.getString(3).orEmpty())
                        }
                        if (payee != null) updates += Triple(id, payee.key, payee.name)
                    }
                }
                val stmt = db.compileStatement("UPDATE debits SET payeeKey = ?, payeeName = ? WHERE id = ?")
                for ((id, key, name) in updates) {
                    stmt.clearBindings()
                    stmt.bindString(1, key)
                    stmt.bindString(2, name)
                    stmt.bindLong(3, id)
                    stmt.executeUpdateDelete()
                }
                stmt.close()
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
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
