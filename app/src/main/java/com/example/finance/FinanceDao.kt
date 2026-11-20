package com.example.finance

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class CategoryTotal(
    val categoryId: Long,
    val totalPaise: Long
)

data class DailyTotal(
    val dayStart: Long,
    val totalPaise: Long
)

@Dao
interface FinanceDao {
    @Query("SELECT * FROM categories ORDER BY CASE WHEN LOWER(name) = 'uncategorized' THEN 0 ELSE 1 END ASC, LOWER(name) ASC")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY CASE WHEN LOWER(name) = 'uncategorized' THEN 0 ELSE 1 END ASC, LOWER(name) ASC")
    suspend fun getCategories(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    suspend fun getCategoryById(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories WHERE name = :name LIMIT 1")
    suspend fun getCategoryByName(name: String): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCategory(category: CategoryEntity): Long

    @Update
    suspend fun updateCategory(category: CategoryEntity)

    @Query("UPDATE categories SET parentCategoryId = :parentCategoryId WHERE id = :categoryId")
    suspend fun updateParentCategory(categoryId: Long, parentCategoryId: Long?)

    @Query("UPDATE categories SET parentCategoryId = NULL WHERE parentCategoryId = :parentCategoryId")
    suspend fun unlinkSubcategories(parentCategoryId: Long)

    @Query("DELETE FROM categories WHERE id = :id AND isSystem = 0")
    suspend fun deleteCategory(id: Long)

    @Query("UPDATE debits SET categoryId = :newCategoryId WHERE categoryId = :oldCategoryId")
    suspend fun reassignDebits(oldCategoryId: Long, newCategoryId: Long)

    @Query("SELECT * FROM debits ORDER BY occurredAt DESC")
    fun observeDebits(): Flow<List<DebitEntity>>

    /** Debits sitting in Uncategorized are untagged, whatever put them there. */
    @Query("UPDATE debits SET tagSource = 'none', autoCategorized = 0 WHERE categoryId = :categoryId")
    suspend fun untagCategory(categoryId: Long)

    @Query("SELECT * FROM debits WHERE categoryId = :categoryId ORDER BY occurredAt DESC")
    fun observeDebitsByCategory(categoryId: Long): Flow<List<DebitEntity>>

    @Query("SELECT * FROM debits WHERE id = :id LIMIT 1")
    suspend fun getDebitById(id: Long): DebitEntity?

    @Query("SELECT * FROM debits WHERE messageKey = :messageKey LIMIT 1")
    suspend fun getDebitByMessageKey(messageKey: String): DebitEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDebit(debit: DebitEntity): Long

    @Update
    suspend fun updateDebit(debit: DebitEntity)

    @Query("DELETE FROM debits WHERE id = :id")
    suspend fun deleteDebitById(id: Long)

    @Query("DELETE FROM debits WHERE messageKey = :messageKey")
    suspend fun deleteDebitByMessageKey(messageKey: String)

    @Query("SELECT EXISTS(SELECT 1 FROM ignored_sms WHERE messageKey = :messageKey)")
    suspend fun isIgnored(messageKey: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIgnored(ignored: IgnoredSmsEntity)

    @Query(
        """
        SELECT * FROM debits
        WHERE tagSource = 'manual' AND payeeKey = :payeeKey
        ORDER BY occurredAt DESC LIMIT :limit
        """
    )
    suspend fun manualTagsForPayee(payeeKey: String, limit: Int = 10): List<DebitEntity>

    @Query(
        """
        SELECT * FROM debits
        WHERE tagSource = 'manual' AND lat IS NOT NULL
          AND lat BETWEEN :minLat AND :maxLat AND lng BETWEEN :minLng AND :maxLng
        ORDER BY occurredAt DESC LIMIT 50
        """
    )
    suspend fun manualTagsInBox(minLat: Double, maxLat: Double, minLng: Double, maxLng: Double): List<DebitEntity>

    @Query(
        """
        SELECT categoryId FROM debits
        WHERE tagSource = 'manual' AND occurredAt >= :since
        GROUP BY categoryId ORDER BY COUNT(*) DESC LIMIT :limit
        """
    )
    suspend fun frequentCategoryIds(since: Long, limit: Int = 6): List<Long>

    @Query("SELECT * FROM debits WHERE categoryId IN (:categoryIds) ORDER BY occurredAt ASC")
    suspend fun debitsInCategories(categoryIds: List<Long>): List<DebitEntity>

    @Query("SELECT * FROM debits ORDER BY occurredAt DESC")
    suspend fun allDebits(): List<DebitEntity>

    @Query("SELECT * FROM keyword_rules")
    suspend fun getKeywordRules(): List<KeywordRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKeywordRule(rule: KeywordRuleEntity)

    @Query("DELETE FROM keyword_rules WHERE id = :id")
    suspend fun deleteKeywordRule(id: Long)

    @Query("SELECT COALESCE(SUM(amountPaise), 0) FROM debits WHERE occurredAt >= :start AND occurredAt < :end")
    suspend fun sumBetween(start: Long, end: Long): Long

    @Query("SELECT COALESCE(SUM(amountPaise), 0) FROM debits WHERE categoryId = :categoryId AND occurredAt >= :start AND occurredAt < :end")
    suspend fun categorySumBetween(categoryId: Long, start: Long, end: Long): Long

    @Query("SELECT COALESCE(SUM(amountPaise), 0) FROM debits")
    suspend fun sumAll(): Long

    @Query("SELECT categoryId, COALESCE(SUM(amountPaise), 0) AS totalPaise FROM debits WHERE occurredAt >= :start AND occurredAt < :end GROUP BY categoryId")
    suspend fun categoryTotalsBetween(start: Long, end: Long): List<CategoryTotal>

    @Query(
        """
        SELECT (occurredAt / 86400000) * 86400000 AS dayStart,
               COALESCE(SUM(amountPaise), 0) AS totalPaise
        FROM debits
        WHERE occurredAt >= :since
        GROUP BY dayStart
        ORDER BY dayStart ASC
        """
    )
    suspend fun dailyTotalsSince(since: Long): List<DailyTotal>

    @Query(
        """
        SELECT (occurredAt / 86400000) * 86400000 AS dayStart,
               COALESCE(SUM(amountPaise), 0) AS totalPaise
        FROM debits
        WHERE occurredAt >= :start AND occurredAt < :end
        GROUP BY dayStart
        ORDER BY dayStart ASC
        """
    )
    suspend fun dailyTotalsBetween(start: Long, end: Long): List<DailyTotal>

    @Query("SELECT COUNT(DISTINCT (occurredAt / 86400000)) FROM debits WHERE occurredAt >= :start AND occurredAt < :end")
    suspend fun distinctDaysBetween(start: Long, end: Long): Int

    @Query("SELECT COUNT(DISTINCT (strftime('%Y-%m', occurredAt / 1000, 'unixepoch'))) FROM debits")
    suspend fun distinctMonthsCount(): Int

    @Query("SELECT MIN(occurredAt) FROM debits")
    suspend fun earliestDebitTime(): Long?

    @Query("SELECT COUNT(*) FROM debits")
    suspend fun debitCount(): Int
}
