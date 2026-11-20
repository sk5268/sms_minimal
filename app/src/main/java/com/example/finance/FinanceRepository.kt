package com.example.finance

import android.content.Context
import android.provider.Telephony
import com.example.DebitParser
import com.example.PayeeParser
import com.example.SmsIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.UUID

data class MonthTotalPoint(
    val year: Int,
    val month: Int, // 0-indexed
    val monthLabel: String,
    val totalPaise: Long
)

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

    data class IngestResult(
        val debitId: Long,
        val categoryId: Long,
        val categoryName: String,
        /** manual | payee | place | none */
        val tagSource: String,
        val isFuel: Boolean,
        val litres: Double?,
        val suggestions: List<CategoryEntity>
    ) {
        val isTagged: Boolean get() = tagSource != TagMemory.SOURCE_NONE
    }

    /**
     * Stores a debit from a bank SMS. It is tagged only when the owner's own past tags
     * for this payee (or this spot) agree; otherwise it waits with suggestions.
     */
    suspend fun ingestDebit(
        amountPaise: Long,
        sender: String,
        snippet: String,
        occurredAt: Long,
        body: String = snippet,
        fix: TagMemory.Fix? = null
    ): IngestResult? {
        ensureSeeded()
        val messageKey = SmsIdentity.key(occurredAt, sender, snippet)
        if (dao.isIgnored(messageKey)) return null
        dao.getDebitByMessageKey(messageKey)?.let { return resultFor(it) }

        val payee = PayeeParser.parse(body)
        val resolution = resolve(payee?.key, fix)
        val uncategorizedId = uncategorizedId()
        val categoryId = resolution.categoryId ?: uncategorizedId
        val category = dao.getCategoryById(categoryId)
        val isFuel = Fuel.isFuelCategory(category?.name)
        val price = if (isFuel) Fuel.pricePerLitre(appContext) else null
        val debit = DebitEntity(
            messageKey = messageKey,
            amountPaise = amountPaise,
            sender = sender,
            snippet = snippet,
            categoryId = categoryId,
            occurredAt = occurredAt,
            autoCategorized = resolution.categoryId != null,
            payeeKey = payee?.key,
            payeeName = payee?.name,
            lat = fix?.lat,
            lng = fix?.lng,
            locAccuracyM = fix?.accuracyM,
            tagSource = resolution.source,
            litres = price?.let { Fuel.litres(amountPaise, it) },
            fuelPricePerL = price
        )
        val rowId = dao.insertDebit(debit)
        val stored = if (rowId > 0L) dao.getDebitById(rowId) else dao.getDebitByMessageKey(messageKey)
        stored ?: return null
        FinanceCloudSync.push(appContext, stored.id)
        return resultFor(stored)
    }

    suspend fun resultFor(debit: DebitEntity): IngestResult {
        val category = dao.getCategoryById(debit.categoryId)
        return IngestResult(
            debitId = debit.id,
            categoryId = debit.categoryId,
            categoryName = category?.name ?: "Uncategorized",
            tagSource = if (debit.tagSource == "legacy_auto") TagMemory.SOURCE_PAYEE else debit.tagSource,
            isFuel = Fuel.isFuelCategory(category?.name),
            litres = debit.litres,
            suggestions = suggestionsFor(debit)
        )
    }

    private suspend fun uncategorizedId(): Long = dao.getCategoryByName("Uncategorized")?.id ?: 0L

    private suspend fun resolve(payeeKey: String?, fix: TagMemory.Fix?): TagMemory.Resolution {
        val payeeHistory = payeeKey?.let { dao.manualTagsForPayee(it).map(::toPoint) }.orEmpty()
        return TagMemory.resolve(payeeKey, fix, payeeHistory, nearby(fix))
    }

    private suspend fun nearby(fix: TagMemory.Fix?): List<TagMemory.TaggedPoint> {
        fix ?: return emptyList()
        val box = TagMemory.boundingBox(fix.lat, fix.lng, TagMemory.PLACE_RADIUS_M * 2)
        return dao.manualTagsInBox(box[0], box[1], box[2], box[3]).map(::toPoint)
    }

    private fun toPoint(d: DebitEntity) = TagMemory.TaggedPoint(
        categoryId = d.categoryId,
        payeeKey = d.payeeKey,
        lat = d.lat,
        lng = d.lng,
        accuracyM = d.locAccuracyM,
        occurredAt = d.occurredAt
    )

    /** Best-first categories to offer for this debit (never Uncategorized or its own tag). */
    suspend fun suggestionsFor(debit: DebitEntity, limit: Int = 3): List<CategoryEntity> {
        val fix = if (debit.lat != null && debit.lng != null) {
            TagMemory.Fix(debit.lat, debit.lng, debit.locAccuracyM ?: 999.0)
        } else null
        val payeeHistory = debit.payeeKey?.let { key ->
            dao.manualTagsForPayee(key).filter { it.id != debit.id }.map(::toPoint)
        }.orEmpty()
        val ids = TagMemory.suggestions(
            payeeKey = debit.payeeKey,
            fix = fix,
            payeeHistory = payeeHistory,
            nearbyHistory = nearby(fix).filter { it.occurredAt != debit.occurredAt },
            keywordHint = autoCategorizer.keywordHint(debit.payeeName ?: debit.sender, debit.snippet),
            frequentCategoryIds = dao.frequentCategoryIds(System.currentTimeMillis() - 90L * 86_400_000),
            excludeIds = setOf(uncategorizedId(), debit.categoryId),
            limit = limit
        )
        val byId = dao.getCategories().associateBy { it.id }
        val picked = ids.mapNotNull { byId[it] }
        if (picked.size >= limit) return picked
        val rest = byId.values.filter { it.name != "Uncategorized" && it.id != debit.categoryId && it !in picked }
            .sortedBy { it.sortOrder }
        return (picked + rest).take(limit)
    }

    /**
     * The owner tagged this debit. That is the only thing that teaches tag memory.
     * Location is stamped now if the payment had none and it happened recently.
     */
    suspend fun categorizeDebit(
        debitId: Long,
        categoryId: Long,
        note: String? = null,
        fix: TagMemory.Fix? = null
    ): DebitEntity? {
        val debit = dao.getDebitById(debitId) ?: return null
        val trimmedNote = note?.trim()?.ifBlank { null }
        val category = dao.getCategoryById(categoryId)
        val isFuel = Fuel.isFuelCategory(category?.name)
        val price = debit.fuelPricePerL ?: Fuel.pricePerLitre(appContext)
        val useFix = fix != null && debit.lat == null &&
            System.currentTimeMillis() - debit.occurredAt <= RECENT_FOR_LOCATION_MS
        val updated = debit.copy(
            categoryId = categoryId,
            note = trimmedNote ?: debit.note,
            autoCategorized = false,
            tagSource = if (categoryId == uncategorizedId()) TagMemory.SOURCE_NONE else TagMemory.SOURCE_MANUAL,
            lat = if (useFix) fix!!.lat else debit.lat,
            lng = if (useFix) fix!!.lng else debit.lng,
            locAccuracyM = if (useFix) fix!!.accuracyM else debit.locAccuracyM,
            litres = if (isFuel) Fuel.litres(debit.amountPaise, price) else null,
            fuelPricePerL = if (isFuel) price else null,
            tripKm = if (isFuel) debit.tripKm else null,
            updatedAt = System.currentTimeMillis()
        )
        dao.updateDebit(updated)
        FinanceCloudSync.push(appContext, debitId)
        return updated
    }

    /** Trip meter reading typed at this refuel. Returns the refreshed fuel stats. */
    suspend fun setTripKm(debitId: Long, km: Double): Fuel.Stats? {
        val debit = dao.getDebitById(debitId) ?: return null
        if (km <= 0.0 || km > 2_000.0) return null
        dao.updateDebit(debit.copy(tripKm = km, updatedAt = System.currentTimeMillis()))
        FinanceCloudSync.push(appContext, debitId)
        return fuelStats()
    }

    suspend fun fuelStats(now: Long = System.currentTimeMillis()): Fuel.Stats? {
        val fuelIds = dao.getCategories().filter { Fuel.isFuelCategory(it.name) }.map { it.id }
        if (fuelIds.isEmpty()) return null
        val fills = dao.debitsInCategories(fuelIds).map {
            Fuel.Fill(it.occurredAt, it.amountPaise, it.litres, it.tripKm)
        }
        if (fills.isEmpty()) return null
        return Fuel.stats(fills, startOfMonth(now), startOfNextMonth(now))
    }

    suspend fun dontTrack(messageKey: String) {
        if (messageKey.isBlank()) return
        dao.insertIgnored(IgnoredSmsEntity(messageKey))
        dao.deleteDebitByMessageKey(messageKey)
        FinanceCloudSync.markDeleted(messageKey)
    }

    suspend fun dontTrackByDebitId(debitId: Long) {
        val debit = dao.getDebitById(debitId) ?: return
        if (debit.isManualEntry()) {
            dao.deleteDebitById(debitId)
            FinanceCloudSync.markDeleted(debit.messageKey)
            return
        }
        dontTrack(debit.messageKey)
    }

    suspend fun addManualDebit(
        amountPaise: Long,
        note: String,
        payee: String = "Cash",
        categoryId: Long? = null,
        occurredAt: Long = System.currentTimeMillis()
    ): Long? {
        ensureSeeded()
        if (amountPaise <= 0L) return null
        val cleanNote = note.trim()
        val snippet = cleanNote.ifBlank { "Manual entry" }
        val sender = payee.trim().ifBlank { "Cash" }
        val messageKey = "manual:${UUID.randomUUID()}"
        val payee = manualPayee(sender)
        val resolvedCategoryId = categoryId ?: uncategorizedId()
        val isFuel = Fuel.isFuelCategory(dao.getCategoryById(resolvedCategoryId)?.name)
        val price = if (isFuel) Fuel.pricePerLitre(appContext) else null
        val debit = DebitEntity(
            messageKey = messageKey,
            amountPaise = amountPaise,
            sender = sender,
            snippet = snippet,
            categoryId = resolvedCategoryId,
            occurredAt = occurredAt,
            autoCategorized = false,
            note = cleanNote.ifBlank { null },
            payeeKey = payee?.key,
            payeeName = payee?.name,
            tagSource = if (categoryId == null) TagMemory.SOURCE_NONE else TagMemory.SOURCE_MANUAL,
            litres = price?.let { Fuel.litres(amountPaise, it) },
            fuelPricePerL = price
        )
        val rowId = dao.insertDebit(debit)
        val id = if (rowId > 0L) rowId else dao.getDebitByMessageKey(messageKey)?.id
        id?.let { FinanceCloudSync.push(appContext, it) }
        return id
    }

    suspend fun updateManualDebit(
        debitId: Long,
        amountPaise: Long,
        note: String,
        payee: String,
        categoryId: Long,
        occurredAt: Long
    ): Boolean {
        val debit = dao.getDebitById(debitId) ?: return false
        if (!debit.isManualEntry() || amountPaise <= 0L) return false
        val cleanNote = note.trim()
        val snippet = cleanNote.ifBlank { "Manual entry" }
        val sender = payee.trim().ifBlank { "Cash" }
        val payee = manualPayee(sender)
        val isFuel = Fuel.isFuelCategory(dao.getCategoryById(categoryId)?.name)
        val price = debit.fuelPricePerL ?: Fuel.pricePerLitre(appContext)
        dao.updateDebit(
            debit.copy(
                amountPaise = amountPaise,
                sender = sender,
                snippet = snippet,
                categoryId = categoryId,
                occurredAt = occurredAt,
                autoCategorized = false,
                note = cleanNote.ifBlank { null },
                payeeKey = payee?.key,
                payeeName = payee?.name,
                tagSource = if (categoryId == uncategorizedId()) TagMemory.SOURCE_NONE else TagMemory.SOURCE_MANUAL,
                litres = if (isFuel) Fuel.litres(amountPaise, price) else null,
                fuelPricePerL = if (isFuel) price else null,
                updatedAt = System.currentTimeMillis()
            )
        )
        FinanceCloudSync.push(appContext, debitId)
        return true
    }

    suspend fun deleteManualDebit(debitId: Long): Boolean {
        val debit = dao.getDebitById(debitId) ?: return false
        if (!debit.isManualEntry()) return false
        dao.deleteDebitById(debitId)
        FinanceCloudSync.markDeleted(debit.messageKey)
        return true
    }

    private fun manualPayee(name: String): PayeeParser.Payee? {
        val clean = name.trim()
        if (clean.isBlank() || clean.equals("Cash", ignoreCase = true)) return null
        return PayeeParser.Payee("N:" + PayeeParser.normaliseKey(clean), clean)
    }

    suspend fun addCategory(name: String, colorArgb: Int, parentCategoryId: Long? = null): Long {
        val categories = dao.getCategories()
        val sortOrder = (categories.maxOfOrNull { it.sortOrder } ?: 0) + 1
        return dao.insertCategory(
            CategoryEntity(
                name = name.trim(),
                colorArgb = colorArgb,
                sortOrder = sortOrder,
                isSystem = false,
                parentCategoryId = parentCategoryId
            )
        )
    }

    suspend fun setParentCategory(categoryId: Long, parentCategoryId: Long?) {
        if (categoryId == parentCategoryId) return
        dao.updateParentCategory(categoryId, parentCategoryId)
        FinanceCloudSync.syncAllInBackground(appContext)
    }

    suspend fun deleteCategory(categoryId: Long) {
        val uncategorizedId = dao.getCategoryByName("Uncategorized")?.id ?: return
        dao.unlinkSubcategories(categoryId)
        dao.reassignDebits(categoryId, uncategorizedId)
        dao.deleteCategory(categoryId)
        dao.untagCategory(uncategorizedId)
        FinanceCloudSync.syncAllInBackground(appContext)
    }

    suspend fun getMoMHistory(
        monthsCount: Int,
        targetYear: Int,
        targetMonthZeroIndexed: Int,
        categoryId: Long? = null,
        categoryIds: List<Long>? = null
    ): List<MonthTotalPoint> = withContext(Dispatchers.IO) {
        ensureSeeded()
        val list = mutableListOf<MonthTotalPoint>()
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, targetYear)
            set(Calendar.MONTH, targetMonthZeroIndexed)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val targetCategoryIds = when {
            !categoryIds.isNullOrEmpty() -> categoryIds
            categoryId != null && categoryId > 0L -> listOf(categoryId)
            else -> emptyList()
        }

        // Generate N months going backwards, then reverse to chronological order
        for (i in 0 until monthsCount) {
            val yr = cal.get(Calendar.YEAR)
            val mo = cal.get(Calendar.MONTH)
            val (startMs, endMs) = getMonthBounds(yr, mo)
            val total = if (targetCategoryIds.isNotEmpty()) {
                targetCategoryIds.sumOf { dao.categorySumBetween(it, startMs, endMs) }
            } else {
                dao.sumBetween(startMs, endMs)
            }
            val label = java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.US).format(cal.time)
            list.add(MonthTotalPoint(yr, mo, label, total))
            cal.add(Calendar.MONTH, -1)
        }
        list.reversed()
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
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)

            while (cursor.moveToNext()) {
                val body = cursor.getString(bodyIndex) ?: continue
                val sender = cursor.getString(addressIndex) ?: continue
                val date = cursor.getLong(dateIndex)
                val parsed = DebitParser.parse(body, sender) ?: continue
                val messageKey = SmsIdentity.key(date, sender, parsed.snippet)
                if (dao.isIgnored(messageKey) || dao.getDebitByMessageKey(messageKey) != null) continue

                val inserted = ingestDebit(
                    amountPaise = parsed.amountPaise,
                    sender = sender,
                    snippet = parsed.snippet,
                    occurredAt = date,
                    body = body
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
        /** A tag made this long after paying still stamps where the phone is. */
        const val RECENT_FOR_LOCATION_MS = 20 * 60 * 1000L

        @Volatile
        private var instance: FinanceRepository? = null

        fun getInstance(context: Context): FinanceRepository {
            return instance ?: synchronized(this) {
                instance ?: FinanceRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
