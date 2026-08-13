package com.example.finance

import android.content.Context
import android.provider.Telephony
import com.example.DebitParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.Calendar

data class FinanceStats(
    val monthTotalPaise: Long,
    val dailyAveragePaise: Long,
    val weeklyAveragePaise: Long,
    val monthlyAveragePaise: Long,
    val overallAveragePaise: Long,
    val overallTotalPaise: Long,
    val dailyTotals: List<DailyTotal>,
    val categoryTotals: List<CategoryTotal>,
    val weekStartTimestamp: Long = 0L,
    val weekEndTimestamp: Long = 0L
)

class FinanceRepository(context: Context) {

    private val dao = FinanceDatabase.getInstance(context).financeDao()
    private val autoCategorizer = AutoCategorizer(dao)
    private val appContext = context.applicationContext
    private var seeded = false

    private suspend fun ensureSeeded() {
        if (!seeded) {
            autoCategorizer.seedKeywordRulesIfEmpty()
            seeded = true
        }
    }

    fun observeCategories(): Flow<List<CategoryEntity>> = dao.observeCategories()

    fun observeDebits(): Flow<List<DebitEntity>> = dao.observeDebits()

    suspend fun getCategories(): List<CategoryEntity> {
        ensureSeeded()
        return dao.getCategories()
    }

    suspend fun getDebitById(id: Long): DebitEntity? = dao.getDebitById(id)

    suspend fun getDebitBySmsMessageId(smsMessageId: Long): DebitEntity? =
        dao.getDebitBySmsMessageId(smsMessageId)

    suspend fun ingestDebit(
        smsMessageId: Long,
        amountPaise: Long,
        sender: String,
        snippet: String,
        occurredAt: Long
    ): Long? {
        ensureSeeded()
        if (dao.isIgnored(smsMessageId)) return null
        if (dao.getDebitBySmsMessageId(smsMessageId) != null) {
            return dao.getDebitBySmsMessageId(smsMessageId)?.id
        }

        val match = autoCategorizer.resolveCategory(sender, snippet)
        val debit = DebitEntity(
            smsMessageId = smsMessageId,
            amountPaise = amountPaise,
            sender = sender,
            snippet = snippet,
            categoryId = match.categoryId,
            occurredAt = occurredAt,
            autoCategorized = match.autoCategorized
        )
        val rowId = dao.insertDebit(debit)
        if (rowId > 0L) return rowId
        return dao.getDebitBySmsMessageId(smsMessageId)?.id
    }

    suspend fun categorizeDebit(debitId: Long, categoryId: Long, learnSender: Boolean = true) {
        val debit = dao.getDebitById(debitId) ?: return
        dao.updateDebit(
            debit.copy(
                categoryId = categoryId,
                autoCategorized = false
            )
        )
        if (learnSender) {
            autoCategorizer.rememberSenderCategory(debit.sender, categoryId)
        }
    }

    suspend fun dontTrack(smsMessageId: Long) {
        dao.insertIgnored(IgnoredSmsEntity(smsMessageId))
        dao.deleteDebitBySmsMessageId(smsMessageId)
    }

    suspend fun dontTrackByDebitId(debitId: Long) {
        val debit = dao.getDebitById(debitId) ?: return
        dontTrack(debit.smsMessageId)
    }

    suspend fun addCategory(name: String, colorArgb: Int): Long {
        val categories = dao.getCategories()
        val sortOrder = (categories.maxOfOrNull { it.sortOrder } ?: 0) + 1
        return dao.insertCategory(
            CategoryEntity(
                name = name.trim(),
                colorArgb = colorArgb,
                sortOrder = sortOrder,
                isSystem = false
            )
        )
    }

    suspend fun deleteCategory(categoryId: Long) {
        val uncategorizedId = dao.getCategoryByName("Uncategorized")?.id ?: return
        dao.reassignDebits(categoryId, uncategorizedId)
        dao.deleteCategory(categoryId)
    }

    suspend fun getStats(targetYear: Int, targetMonthZeroIndexed: Int): FinanceStats = withContext(Dispatchers.IO) {
        ensureSeeded()
        val now = System.currentTimeMillis()
        val (monthStart, monthEnd) = getMonthBounds(targetYear, targetMonthZeroIndexed)

        val monthTotal = dao.sumBetween(monthStart, monthEnd)
        val dailyTotals = dao.dailyTotalsBetween(monthStart, monthEnd)

        val isCurrentMonth = now >= monthStart && now < monthEnd
        val effectiveEnd = if (isCurrentMonth) now else monthEnd
        val daysInMonthSoFar = Calendar.getInstance().apply {
            timeInMillis = effectiveEnd - 1
        }.get(Calendar.DAY_OF_MONTH).coerceAtLeast(1)

        val dailyAveragePaise = if (daysInMonthSoFar > 0) monthTotal / daysInMonthSoFar else 0L

        val weekEnd = if (isCurrentMonth) now else (monthEnd - 1)
        val rawWeekStart = weekEnd - 7L * 24 * 60 * 60 * 1000
        val weekStart = if (rawWeekStart < monthStart) monthStart else rawWeekStart
        val weekTotal = dao.sumBetween(weekStart, weekEnd)
        val weekDaysSpan = (((weekEnd - weekStart) / (24 * 60 * 60 * 1000)).toInt() + 1).coerceAtLeast(1)
        val weeklyAveragePaise = weekTotal / weekDaysSpan

        val overallTotal = dao.sumAll()
        val earliest = dao.earliestDebitTime()
        val overallDays = if (earliest != null) {
            val span = ((now - earliest) / (24 * 60 * 60 * 1000)).toInt() + 1
            span.coerceAtLeast(1)
        } else 1

        val distinctMonthsCount = dao.distinctMonthsCount().coerceAtLeast(1)

        FinanceStats(
            monthTotalPaise = monthTotal,
            dailyAveragePaise = dailyAveragePaise,
            weeklyAveragePaise = weeklyAveragePaise,
            monthlyAveragePaise = overallTotal / distinctMonthsCount,
            overallAveragePaise = overallTotal / overallDays,
            overallTotalPaise = overallTotal,
            dailyTotals = dailyTotals,
            categoryTotals = dao.categoryTotalsBetween(monthStart, monthEnd),
            weekStartTimestamp = weekStart,
            weekEndTimestamp = weekEnd
        )
    }

    private fun getMonthBounds(year: Int, monthZeroIndexed: Int): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, monthZeroIndexed)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        val end = cal.timeInMillis
        return Pair(start, end)
    }

    suspend fun scanInbox(days: Int = 90): Int = withContext(Dispatchers.IO) {
        ensureSeeded()
        val since = System.currentTimeMillis() - days.toLong() * 24 * 60 * 60 * 1000
        var ingested = 0
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE
        )
        val selection = "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.TYPE} = ?"
        val selectionArgs = arrayOf(since.toString(), Telephony.Sms.MESSAGE_TYPE_INBOX.toString())

        appContext.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)

            while (cursor.moveToNext()) {
                val messageId = cursor.getLong(idIndex)
                if (dao.isIgnored(messageId)) continue
                if (dao.getDebitBySmsMessageId(messageId) != null) continue

                val body = cursor.getString(bodyIndex) ?: continue
                val sender = cursor.getString(addressIndex) ?: continue
                val date = cursor.getLong(dateIndex)
                val parsed = DebitParser.parse(body, sender) ?: continue

                val inserted = ingestDebit(
                    smsMessageId = messageId,
                    amountPaise = parsed.amountPaise,
                    sender = sender,
                    snippet = parsed.snippet,
                    occurredAt = date
                )
                if (inserted != null) ingested++
            }
        }
        ingested
    }

    private fun startOfMonth(time: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = time
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun startOfNextMonth(time: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = time
            set(Calendar.DAY_OF_MONTH, 1)
            add(Calendar.MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    companion object {
        @Volatile
        private var instance: FinanceRepository? = null

        fun getInstance(context: Context): FinanceRepository {
            return instance ?: synchronized(this) {
                instance ?: FinanceRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
