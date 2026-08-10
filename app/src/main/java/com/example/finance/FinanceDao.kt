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
    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, name ASC")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, name ASC")
    suspend fun getCategories(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    suspend fun getCategoryById(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories WHERE name = :name LIMIT 1")
    suspend fun getCategoryByName(name: String): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCategory(category: CategoryEntity): Long

    @Query("DELETE FROM categories WHERE id = :id AND isSystem = 0")
    suspend fun deleteCategory(id: Long)

    @Query("UPDATE debits SET categoryId = :newCategoryId WHERE categoryId = :oldCategoryId")
    suspend fun reassignDebits(oldCategoryId: Long, newCategoryId: Long)

    @Query("SELECT * FROM debits ORDER BY occurredAt DESC")
    fun observeDebits(): Flow<List<DebitEntity>>

    @Query("SELECT * FROM debits WHERE categoryId = :categoryId ORDER BY occurredAt DESC")
    fun observeDebitsByCategory(categoryId: Long): Flow<List<DebitEntity>>

    @Query("SELECT * FROM debits WHERE id = :id LIMIT 1")
    suspend fun getDebitById(id: Long): DebitEntity?

    @Query("SELECT * FROM debits WHERE smsMessageId = :smsMessageId LIMIT 1")
    suspend fun getDebitBySmsMessageId(smsMessageId: Long): DebitEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDebit(debit: DebitEntity): Long

    @Update
    suspend fun updateDebit(debit: DebitEntity)

    @Query("DELETE FROM debits WHERE id = :id")
    suspend fun deleteDebitById(id: Long)

    @Query("DELETE FROM debits WHERE smsMessageId = :smsMessageId")
    suspend fun deleteDebitBySmsMessageId(smsMessageId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM ignored_sms WHERE smsMessageId = :smsMessageId)")
    suspend fun isIgnored(smsMessageId: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIgnored(ignored: IgnoredSmsEntity)

    @Query("SELECT categoryId FROM sender_category_memory WHERE senderKey = :senderKey LIMIT 1")
    suspend fun getSenderCategory(senderKey: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSenderMemory(memory: SenderCategoryMemoryEntity)

    @Query("DELETE FROM sender_category_memory WHERE senderKey = :senderKey")
    suspend fun deleteSenderMemory(senderKey: String)

    @Query("SELECT * FROM keyword_rules")
    suspend fun getKeywordRules(): List<KeywordRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKeywordRule(rule: KeywordRuleEntity)

    @Query("DELETE FROM keyword_rules WHERE id = :id")
    suspend fun deleteKeywordRule(id: Long)

    @Query("SELECT COALESCE(SUM(amountPaise), 0) FROM debits WHERE occurredAt >= :start AND occurredAt < :end")
    suspend fun sumBetween(start: Long, end: Long): Long

    @Query("SELECT COALESCE(SUM(amountPaise), 0) FROM debits")
    suspend fun sumAll(): Long

    @Query("SELECT categoryId, COALESCE(SUM(amountPaise), 0) AS totalPaise FROM debits GROUP BY categoryId")
    suspend fun categoryTotals(): List<CategoryTotal>

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

    @Query("SELECT COUNT(DISTINCT (occurredAt / 86400000)) FROM debits WHERE occurredAt >= :start AND occurredAt < :end")
    suspend fun distinctDaysBetween(start: Long, end: Long): Int

    @Query("SELECT MIN(occurredAt) FROM debits")
    suspend fun earliestDebitTime(): Long?

    @Query("SELECT COUNT(*) FROM debits")
    suspend fun debitCount(): Int
}
