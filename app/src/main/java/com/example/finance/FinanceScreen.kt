package com.example.finance

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.formatRupees
import com.example.ui.theme.AccentBlue
import com.example.ui.theme.AccentGreen
import com.example.ui.theme.AccentRed
import com.example.ui.theme.BorderColor
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.PureWhite
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun localDayStart(timeMs: Long): Long {
    return Calendar.getInstance().apply {
        timeInMillis = timeMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

private fun monthDayStartsBetween(monthStartMs: Long, monthEndMs: Long): List<Long> {
    val days = mutableListOf<Long>()
    val cal = Calendar.getInstance().apply { timeInMillis = monthStartMs }
    while (cal.timeInMillis < monthEndMs) {
        days.add(cal.timeInMillis)
        cal.add(Calendar.DAY_OF_MONTH, 1)
    }
    return days
}

private fun previousMonth(year: Int, month: Int): Pair<Int, Int> {
    val cal = Calendar.getInstance().apply {
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, month)
        add(Calendar.MONTH, -1)
    }
    return cal.get(Calendar.YEAR) to cal.get(Calendar.MONTH)
}

private fun monthOverMonthLabel(
    currentTotal: Long,
    prevTotal: Long,
    prevMonthName: String
): String? {
    if (prevTotal <= 0L && currentTotal <= 0L) return null
    if (prevTotal <= 0L) return "First month with spending"
    val delta = ((currentTotal - prevTotal).toDouble() / prevTotal * 100).toInt()
    return when {
        delta > 0 -> "↑ $delta% vs $prevMonthName"
        delta < 0 -> "↓ ${-delta}% vs $prevMonthName"
        else -> "Same as $prevMonthName"
    }
}

data class ParentCategoryGroup(
    val parentCategory: CategoryEntity,
    val totalAmount: Long,
    val subcategories: List<Pair<CategoryEntity, Long>>,
    val allCategoryIds: Set<Long>
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FinanceScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { FinanceRepository.getInstance(context) }

    val categories by repo.observeCategories().collectAsState(initial = emptyList())
    val debits by repo.observeDebits().collectAsState(initial = emptyList())
    var stats by remember { mutableStateOf<FinanceStats?>(null) }
    var prevMonthStats by remember { mutableStateOf<FinanceStats?>(null) }
    var isScanning by remember { mutableStateOf(false) }
    var showManage by remember { mutableStateOf(false) }
    var showAddCategory by remember { mutableStateOf(false) }
    var recategorizeDebit by remember { mutableStateOf<DebitEntity?>(null) }
    var editManualDebit by remember { mutableStateOf<DebitEntity?>(null) }
    var showAddDebit by remember { mutableStateOf(false) }
    var newCategoryName by remember { mutableStateOf("") }
    var isRecentDebitsExpanded by remember { mutableStateOf(false) }
    var selectedCategoryId by remember { mutableStateOf<Long?>(null) }
    var isParentCategoryMode by remember { mutableStateOf(false) }
    var expandedParentCategoryId by remember { mutableStateOf<Long?>(null) }
    var isCategoryBreakdownExpanded by remember { mutableStateOf(false) }
    var momMonthsCount by remember { mutableStateOf(6) }
    var momHistory by remember { mutableStateOf<List<MonthTotalPoint>>(emptyList()) }

    val categoryMap = categories.associateBy { it.id }

    val currentCal = remember { Calendar.getInstance() }
    var selectedYear by remember { mutableStateOf(currentCal.get(Calendar.YEAR)) }
    var selectedMonth by remember { mutableStateOf(currentCal.get(Calendar.MONTH)) }

    LaunchedEffect(selectedYear, selectedMonth) {
        selectedCategoryId = null
        expandedParentCategoryId = null
    }

    val (monthStartMs, monthEndMs) = remember(selectedYear, selectedMonth) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, selectedYear)
            set(Calendar.MONTH, selectedMonth)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        Pair(start, cal.timeInMillis)
    }

    val monthName = remember(selectedYear, selectedMonth) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, selectedYear)
            set(Calendar.MONTH, selectedMonth)
        }
        SimpleDateFormat("MMMM yyyy", Locale.US).format(cal.time)
    }

    val monthShortName = remember(selectedYear, selectedMonth) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, selectedYear)
            set(Calendar.MONTH, selectedMonth)
        }
        SimpleDateFormat("MMM yyyy", Locale.US).format(cal.time)
    }

    val prevMonthShortName = remember(selectedYear, selectedMonth) {
        val (py, pm) = previousMonth(selectedYear, selectedMonth)
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, py)
            set(Calendar.MONTH, pm)
        }
        SimpleDateFormat("MMM", Locale.US).format(cal.time)
    }

    val weekDateRangeLabel = remember(stats) {
        if (stats != null && stats!!.weekStartTimestamp > 0L && stats!!.weekEndTimestamp > 0L) {
            val fmt = SimpleDateFormat("dd MMM", Locale.US)
            "${fmt.format(Date(stats!!.weekStartTimestamp))} – ${fmt.format(Date(stats!!.weekEndTimestamp))}"
        } else ""
    }

    val monthDebits = remember(debits, monthStartMs, monthEndMs) {
        debits.filter { it.occurredAt >= monthStartMs && it.occurredAt < monthEndMs }
    }

    LaunchedEffect(debits, categories, selectedYear, selectedMonth) {
        val (py, pm) = previousMonth(selectedYear, selectedMonth)
        val (current, prev) = withContext(Dispatchers.IO) {
            repo.getStats(selectedYear, selectedMonth) to repo.getStats(py, pm)
        }
        stats = current
        prevMonthStats = prev
    }

    // Compute parent category groups
    val subcategoryParentMap = remember(categories) {
        categories.filter { it.parentCategoryId != null }
            .groupBy { it.parentCategoryId!! }
    }

    val parentCategoryIds = remember(categories, subcategoryParentMap) {
        categories.filter { cat ->
            cat.id in subcategoryParentMap.keys || categories.any { sub -> sub.parentCategoryId == cat.id }
        }.map { it.id }.toSet()
    }

    val parentCategoryGroups = remember(categories, monthDebits, subcategoryParentMap, parentCategoryIds) {
        // Parent categories
        val parentGroups = parentCategoryIds.mapNotNull { parentId ->
            val parentCat = categoryMap[parentId] ?: return@mapNotNull null
            val subs = subcategoryParentMap[parentId].orEmpty()
            val allIds = setOf(parentId) + subs.map { it.id }
            val totalAmount = monthDebits.filter { it.categoryId in allIds }.sumOf { it.amountPaise }
            val subTotals = subs.map { sub ->
                sub to monthDebits.filter { it.categoryId == sub.id }.sumOf { it.amountPaise }
            }
            ParentCategoryGroup(
                parentCategory = parentCat,
                totalAmount = totalAmount,
                subcategories = subTotals,
                allCategoryIds = allIds
            )
        }

        // Standalone categories (neither a parent nor belonging to any parent)
        val standaloneGroups = categories.filter { cat ->
            cat.parentCategoryId == null && cat.id !in parentCategoryIds
        }.map { cat ->
            val totalAmount = monthDebits.filter { it.categoryId == cat.id }.sumOf { it.amountPaise }
            ParentCategoryGroup(
                parentCategory = cat,
                totalAmount = totalAmount,
                subcategories = emptyList(),
                allCategoryIds = setOf(cat.id)
            )
        }

        (parentGroups + standaloneGroups).sortedByDescending { it.totalAmount }
    }

    val selectedParentGroup = remember(selectedCategoryId, isParentCategoryMode, parentCategoryGroups) {
        if (isParentCategoryMode && selectedCategoryId != null) {
            parentCategoryGroups.find { it.parentCategory.id == selectedCategoryId }
        } else null
    }

    val selectedCategoryIdsFilter = remember(selectedCategoryId, isParentCategoryMode, selectedParentGroup) {
        val catId = selectedCategoryId
        if (catId == null) null
        else if (isParentCategoryMode && selectedParentGroup != null) selectedParentGroup.allCategoryIds
        else setOf(catId)
    }

    LaunchedEffect(debits, selectedYear, selectedMonth, momMonthsCount, selectedCategoryIdsFilter) {
        val history = withContext(Dispatchers.IO) {
            repo.getMoMHistory(
                monthsCount = momMonthsCount,
                targetYear = selectedYear,
                targetMonthZeroIndexed = selectedMonth,
                categoryIds = selectedCategoryIdsFilter?.toList()
            )
        }
        momHistory = history
    }

    val sparklineTotals = remember(stats, monthDebits) {
        val fromStats = stats?.dailyTotals.orEmpty().filter { it.totalPaise > 0 }
        if (fromStats.isNotEmpty()) fromStats
        else if (monthDebits.isNotEmpty()) {
            monthDebits.groupBy { localDayStart(it.occurredAt) }
                .map { (day, list) -> DailyTotal(day, list.sumOf { it.amountPaise }) }
                .sortedBy { it.dayStart }
        } else emptyList()
    }

    val categoryTotals = remember(stats, monthDebits, categoryMap) {
        val fromStats = stats?.categoryTotals.orEmpty()
            .map { total ->
                val name = categoryMap[total.categoryId]?.name ?: "Uncategorized"
                Triple(total.categoryId, name, total.totalPaise)
            }
            .filter { it.third > 0 }

        val source = if (fromStats.isNotEmpty()) fromStats
        else if (monthDebits.isNotEmpty()) {
            monthDebits.groupBy { it.categoryId }
                .map { (categoryId, list) ->
                    val name = categoryMap[categoryId]?.name ?: "Uncategorized"
                    Triple(categoryId, name, list.sumOf { it.amountPaise })
                }
                .filter { it.third > 0 }
        } else emptyList()

        source.sortedByDescending { it.third }
    }

    val monthDayStarts = remember(monthStartMs, monthEndMs) {
        monthDayStartsBetween(monthStartMs, monthEndMs)
    }

    val categoryDailySeries = remember(categoryTotals, monthDebits, monthDayStarts) {
        categoryTotals.map { (categoryId, name, _) ->
            val dailyMap = monthDebits
                .filter { it.categoryId == categoryId }
                .groupBy { localDayStart(it.occurredAt) }
                .mapValues { (_, debits) -> debits.sumOf { it.amountPaise } }
            CategorySeries(
                categoryId = categoryId,
                name = name,
                color = Color.Transparent,
                points = monthDayStarts.map { day -> day to (dailyMap[day] ?: 0L) }
            )
        }
    }

    val uncategorizedId = categories.find { it.name == "Uncategorized" }?.id
    val uncategorizedDebits = monthDebits.filter { it.categoryId == uncategorizedId }
    val attentionDebits = uncategorizedDebits.take(8)
    val attentionIds = attentionDebits.map { it.id }.toSet()
    val recentDebits = monthDebits.filter { it.id !in attentionIds }

    val monthTotal = stats?.monthTotalPaise ?: 0L
    val momLabel = monthOverMonthLabel(
        monthTotal,
        prevMonthStats?.monthTotalPaise ?: 0L,
        prevMonthShortName
    )
    val uncategorizedShare = if (monthTotal > 0L && uncategorizedId != null) {
        val uncategorizedTotal = categoryTotals.find { it.first == uncategorizedId }?.third ?: 0L
        uncategorizedTotal.toDouble() / monthTotal
    } else 0.0

    val selectedCategory = selectedCategoryId?.let { categoryMap[it] }
    val selectedCategoryDebits = remember(selectedCategoryId, selectedCategoryIdsFilter, monthDebits) {
        val catId = selectedCategoryId
        if (catId != null) {
            val filterIds = selectedCategoryIdsFilter ?: setOf(catId)
            monthDebits.filter { it.categoryId in filterIds }
        } else emptyList()
    }
    val selectedCategoryColor = selectedCategory?.let { modernCategoryColor(it) }

    val fabGradient = Brush.linearGradient(
        colors = listOf(Color(0xFF6366F1), Color(0xFF38BDF8))
    )

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp, start = 20.dp, end = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                MonthHeaderSelector(
                    monthName = monthName,
                    selectedYear = selectedYear,
                    selectedMonth = selectedMonth,
                    onPreviousMonth = {
                        val cal = Calendar.getInstance().apply {
                            set(Calendar.YEAR, selectedYear)
                            set(Calendar.MONTH, selectedMonth)
                            add(Calendar.MONTH, -1)
                        }
                        selectedYear = cal.get(Calendar.YEAR)
                        selectedMonth = cal.get(Calendar.MONTH)
                    },
                    onNextMonth = {
                        val cal = Calendar.getInstance().apply {
                            set(Calendar.YEAR, selectedYear)
                            set(Calendar.MONTH, selectedMonth)
                            add(Calendar.MONTH, 1)
                        }
                        selectedYear = cal.get(Calendar.YEAR)
                        selectedMonth = cal.get(Calendar.MONTH)
                    }
                )
            }

            item {
                FinanceCard {
                    SectionLabel("Spend pulse · $monthShortName")
                    SpendSparkline(
                        dailyTotals = sparklineTotals,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            item {
                FinanceCard {
                    val momTitle = if (selectedCategory != null) {
                        "${selectedCategory.name} · MoM Trend"
                    } else {
                        "Overall Spending · MoM Trend"
                    }
                    val momSubtitle = if (selectedCategory != null) {
                        "Monthly spending history for ${selectedCategory.name}"
                    } else {
                        "Month-over-month total spending trend"
                    }
                    val chartColor = selectedCategoryColor ?: AccentBlue

                    MoMChart(
                        history = momHistory,
                        selectedMonthsCount = momMonthsCount,
                        onMonthsCountSelected = { momMonthsCount = it },
                        title = momTitle,
                        subtitle = momSubtitle,
                        chartColor = chartColor
                    )
                }
            }

            item {
                FinanceCard {
                    Text(
                        text = monthName,
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                    Text(
                        text = formatRupees(monthTotal),
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 32.sp
                    )
                    if (momLabel != null) {
                        Text(
                            text = momLabel,
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        AverageCell("Daily avg", stats?.dailyAveragePaise ?: 0L)
                        AverageCell(
                            label = "Weekly avg",
                            amountPaise = stats?.weeklyAveragePaise ?: 0L,
                            subLabel = weekDateRangeLabel
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        AverageCell("Monthly avg", stats?.monthlyAveragePaise ?: 0L)
                        AverageCell("Overall avg", stats?.overallAveragePaise ?: 0L)
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionLabel("Category breakdown")

                    Row(
                        modifier = Modifier
                            .background(DarkSurfaceElevated, RoundedCornerShape(8.dp))
                            .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                            .padding(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .background(
                                    if (!isParentCategoryMode) AccentBlue.copy(alpha = 0.25f) else Color.Transparent,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable {
                                    isParentCategoryMode = false
                                    selectedCategoryId = null
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Base",
                                color = if (!isParentCategoryMode) AccentBlue else TextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = if (!isParentCategoryMode) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                        Box(
                            modifier = Modifier
                                .background(
                                    if (isParentCategoryMode) AccentBlue.copy(alpha = 0.25f) else Color.Transparent,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable {
                                    isParentCategoryMode = true
                                    selectedCategoryId = null
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Parent",
                                color = if (isParentCategoryMode) AccentBlue else TextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = if (isParentCategoryMode) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                if (!isParentCategoryMode) {
                    val totals = categoryTotals
                    if (totals.isEmpty()) {
                        EmptyHint("Debit SMS will auto-log here. Scan inbox for history or add a manual debit.")
                    } else {
                        val barColors = resolveCategoryColors(
                            categoryIds = totals.map { it.first },
                            categoryMap = categoryMap
                        )
                        val multiLineSeries = categoryDailySeries.mapIndexed { index, series ->
                            series.copy(categoryId = totals[index].first, color = barColors[index])
                        }
                        val chartSlices = totals.mapIndexed { index, (catId, name, amount) ->
                            CategoryChartSlice(catId, name, amount, barColors[index])
                        }

                        val selectedSlice = chartSlices.find { it.categoryId == selectedCategoryId }
                        val centerSubtitle = if (selectedSlice != null) selectedSlice.name else "Total Spend"
                        val centerAmount = if (selectedSlice != null) formatRupees(selectedSlice.amount) else formatRupees(monthTotal)
                        val centerTitle = if (selectedSlice != null) {
                            val pct = if (monthTotal > 0) (selectedSlice.amount * 100 / monthTotal).toInt() else 0
                            "$pct% of total"
                        } else {
                            "$monthShortName"
                        }

                        CategoryDonutChart(
                            slices = chartSlices,
                            selectedCategoryId = selectedCategoryId,
                            onSliceClick = { clickedId ->
                                selectedCategoryId = if (clickedId == -1L || selectedCategoryId == clickedId) null else clickedId
                            },
                            centerSubtitle = centerSubtitle,
                            centerAmount = centerAmount,
                            centerTitle = centerTitle,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        )

                        val visibleTotals = if (isCategoryBreakdownExpanded || totals.size <= 5) totals else totals.take(5)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            visibleTotals.forEach { (catId, name, amount) ->
                                val index = totals.indexOfFirst { it.first == catId }
                                val pct = if (monthTotal > 0) (amount * 100 / monthTotal).toInt() else 0
                                val isSelected = selectedCategoryId == catId
                                val isAnySelected = selectedCategoryId != null
                                val rowAlpha = if (isAnySelected && !isSelected) 0.40f else 1.0f
                                val catColor = if (index >= 0 && index < barColors.size) barColors[index] else AccentBlue

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (isSelected) catColor.copy(alpha = 0.14f) else Color.Transparent,
                                            RoundedCornerShape(10.dp)
                                        )
                                        .then(
                                            if (isSelected) Modifier.border(
                                                1.dp,
                                                catColor.copy(alpha = 0.45f),
                                                RoundedCornerShape(10.dp)
                                            ) else Modifier
                                        )
                                        .clickable {
                                            selectedCategoryId = if (selectedCategoryId == catId) null else catId
                                        }
                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(if (isSelected) 12.dp else 10.dp)
                                            .background(catColor.copy(alpha = rowAlpha), RoundedCornerShape(50))
                                    )
                                    Text(
                                        text = name,
                                        color = TextPrimary.copy(alpha = rowAlpha),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "$pct%",
                                        color = TextSecondary.copy(alpha = rowAlpha),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        text = formatRupees(amount),
                                        color = if (isSelected) catColor else TextPrimary.copy(alpha = rowAlpha),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }

                            if (totals.size > 5) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { isCategoryBreakdownExpanded = !isCategoryBreakdownExpanded }
                                        .padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isCategoryBreakdownExpanded) "Show top 5 categories ▲" else "Show all ${totals.size} categories (${totals.size - 5} more) ▼",
                                        color = AccentBlue,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        CategoryMultiLineChart(
                            series = multiLineSeries,
                            dayStarts = monthDayStarts,
                            selectedCategoryId = selectedCategoryId
                        )
                    }
                } else {
                    val parentTotals = parentCategoryGroups.filter { it.totalAmount > 0 }
                    if (parentTotals.isEmpty()) {
                        EmptyHint("No spending recorded under parent categories for $monthShortName.")
                    } else {
                        val barColors = resolveCategoryColors(
                            categoryIds = parentTotals.map { it.parentCategory.id },
                            categoryMap = categoryMap
                        )
                        val chartSlices = parentTotals.mapIndexed { index, group ->
                            CategoryChartSlice(
                                group.parentCategory.id,
                                group.parentCategory.name,
                                group.totalAmount,
                                barColors[index]
                            )
                        }

                        val selectedSlice = chartSlices.find { it.categoryId == selectedCategoryId }
                        val centerSubtitle = if (selectedSlice != null) selectedSlice.name else "Total Spend"
                        val centerAmount = if (selectedSlice != null) formatRupees(selectedSlice.amount) else formatRupees(monthTotal)
                        val centerTitle = if (selectedSlice != null) {
                            val pct = if (monthTotal > 0) (selectedSlice.amount * 100 / monthTotal).toInt() else 0
                            "$pct% of total"
                        } else {
                            "$monthShortName"
                        }

                        CategoryDonutChart(
                            slices = chartSlices,
                            selectedCategoryId = selectedCategoryId,
                            onSliceClick = { clickedId ->
                                selectedCategoryId = if (clickedId == -1L || selectedCategoryId == clickedId) null else clickedId
                            },
                            centerSubtitle = centerSubtitle,
                            centerAmount = centerAmount,
                            centerTitle = centerTitle,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        )

                        val visibleParentTotals = if (isCategoryBreakdownExpanded || parentTotals.size <= 5) parentTotals else parentTotals.take(5)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            visibleParentTotals.forEach { group ->
                                val catId = group.parentCategory.id
                                val name = group.parentCategory.name
                                val amount = group.totalAmount
                                val index = parentTotals.indexOfFirst { it.parentCategory.id == catId }
                                val pct = if (monthTotal > 0) (amount * 100 / monthTotal).toInt() else 0
                                val isSelected = selectedCategoryId == catId
                                val isExpanded = expandedParentCategoryId == catId
                                val isAnySelected = selectedCategoryId != null
                                val rowAlpha = if (isAnySelected && !isSelected) 0.40f else 1.0f
                                val catColor = if (index >= 0 && index < barColors.size) barColors[index] else AccentBlue

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (isSelected) catColor.copy(alpha = 0.14f) else Color.Transparent,
                                            RoundedCornerShape(10.dp)
                                        )
                                        .then(
                                            if (isSelected) Modifier.border(
                                                1.dp,
                                                catColor.copy(alpha = 0.45f),
                                                RoundedCornerShape(10.dp)
                                            ) else Modifier
                                        )
                                        .padding(horizontal = 10.dp, vertical = 7.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedCategoryId = if (selectedCategoryId == catId) null else catId
                                                if (group.subcategories.isNotEmpty()) {
                                                    expandedParentCategoryId = if (expandedParentCategoryId == catId) null else catId
                                                }
                                            },
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(if (isSelected) 12.dp else 10.dp)
                                                .background(catColor.copy(alpha = rowAlpha), RoundedCornerShape(50))
                                        )
                                        Text(
                                            text = name,
                                            color = TextPrimary.copy(alpha = rowAlpha),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (group.subcategories.isNotEmpty()) {
                                            Text(
                                                text = if (isExpanded) "▲" else "▼",
                                                color = TextSecondary.copy(alpha = rowAlpha),
                                                fontSize = 10.sp
                                            )
                                        }
                                        Text(
                                            text = "$pct%",
                                            color = TextSecondary.copy(alpha = rowAlpha),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp
                                        )
                                        Text(
                                            text = formatRupees(amount),
                                            color = if (isSelected) catColor else TextPrimary.copy(alpha = rowAlpha),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }

                                    if (isExpanded && group.subcategories.isNotEmpty()) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 8.dp, start = 22.dp, end = 4.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            group.subcategories.forEach { (subCat, subAmount) ->
                                                val subPct = if (amount > 0) (subAmount * 100 / amount).toInt() else 0
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "└ ${subCat.name}",
                                                        color = TextSecondary,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 11.sp
                                                    )
                                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        Text(
                                                            text = "$subPct%",
                                                            color = TextSecondary.copy(alpha = 0.7f),
                                                            fontFamily = FontFamily.Monospace,
                                                            fontSize = 10.sp
                                                        )
                                                        Text(
                                                            text = formatRupees(subAmount),
                                                            color = TextPrimary.copy(alpha = 0.9f),
                                                            fontFamily = FontFamily.Monospace,
                                                            fontSize = 11.sp
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if (parentTotals.size > 5) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { isCategoryBreakdownExpanded = !isCategoryBreakdownExpanded }
                                        .padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isCategoryBreakdownExpanded) "Show top 5 parents ▲" else "Show all ${parentTotals.size} parent categories (${parentTotals.size - 5} more) ▼",
                                        color = AccentBlue,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (selectedCategoryId != null) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(selectedCategoryColor ?: AccentBlue, RoundedCornerShape(50))
                            )
                            SectionLabel("${selectedCategory?.name ?: "Category"} debits (${selectedCategoryDebits.size})")
                        }
                        Box(
                            modifier = Modifier
                                .background(DarkSurfaceElevated, RoundedCornerShape(8.dp))
                                .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                                .clickable { selectedCategoryId = null }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "✕ Clear filter",
                                color = AccentBlue,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                if (selectedCategoryDebits.isEmpty()) {
                    item { EmptyHint("No debits recorded in ${selectedCategory?.name ?: "this category"} for $monthShortName.") }
                } else {
                    items(selectedCategoryDebits, key = { it.id }) { debit ->
                        DebitRow(
                            debit = debit,
                            categoryName = selectedCategory?.name ?: "Uncategorized",
                            categoryColor = selectedCategoryColor,
                            showAutoBadge = debit.autoCategorized,
                            onTap = {
                                if (debit.isManualEntry()) editManualDebit = debit
                                else recategorizeDebit = debit
                            },
                            onDontTrack = {
                                scope.launch(Dispatchers.IO) { repo.dontTrackByDebitId(debit.id) }
                                Toast.makeText(context, "Removed from finance", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            } else {
                if (uncategorizedShare > 0.5 && attentionDebits.isNotEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AccentBlue.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                .border(1.dp, AccentBlue.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "Most spending is uncategorized — tap a debit to assign a category.",
                                color = TextPrimary.copy(alpha = 0.85f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }

                if (attentionDebits.isNotEmpty()) {
                    item { SectionLabel("Needs attention") }
                    items(attentionDebits, key = { it.id }) { debit ->
                        DebitRow(
                            debit = debit,
                            categoryName = categoryMap[debit.categoryId]?.name ?: "Uncategorized",
                            categoryColor = categoryMap[debit.categoryId]?.let { modernCategoryColor(it) },
                            onTap = {
                                if (debit.isManualEntry()) editManualDebit = debit
                                else recategorizeDebit = debit
                            },
                            onDontTrack = {
                                scope.launch(Dispatchers.IO) { repo.dontTrackByDebitId(debit.id) }
                                Toast.makeText(context, "Removed from finance", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isRecentDebitsExpanded = !isRecentDebitsExpanded }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SectionLabel("Debits · $monthShortName (${monthDebits.size})")
                            Icon(
                                imageVector = if (isRecentDebitsExpanded) Icons.Default.KeyboardArrowUp
                                else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (isRecentDebitsExpanded) "Collapse" else "Expand",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "Add debit",
                                color = AccentGreen,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier.clickable { showAddDebit = true }
                            )
                            Text(
                                text = if (isScanning) "Scanning…" else "Scan inbox",
                                color = AccentBlue,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier.clickable(enabled = !isScanning) {
                                    isScanning = true
                                    scope.launch(Dispatchers.IO) {
                                        val count = repo.scanInbox()
                                        withContext(Dispatchers.Main) {
                                            isScanning = false
                                            Toast.makeText(context, "Added $count debits", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                if (isRecentDebitsExpanded) {
                    if (recentDebits.isEmpty()) {
                        item { EmptyHint("No other debits in $monthShortName.") }
                    } else {
                        items(recentDebits, key = { it.id }) { debit ->
                            DebitRow(
                                debit = debit,
                                categoryName = categoryMap[debit.categoryId]?.name ?: "Uncategorized",
                                categoryColor = categoryMap[debit.categoryId]?.let { modernCategoryColor(it) },
                                showAutoBadge = debit.autoCategorized,
                                onTap = {
                                    if (debit.isManualEntry()) editManualDebit = debit
                                    else recategorizeDebit = debit
                                },
                                onDontTrack = {
                                    scope.launch(Dispatchers.IO) { repo.dontTrackByDebitId(debit.id) }
                                    Toast.makeText(context, "Removed from finance", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showManage = true }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionLabel("Manage categories")
                    Text(
                        text = "+",
                        color = AccentGreen,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 28.dp, end = 24.dp)
                .size(60.dp)
                .background(fabGradient, RoundedCornerShape(30.dp))
                .clickable { showAddDebit = true },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add debit",
                tint = Color(0xFF07080B),
                modifier = Modifier.size(28.dp)
            )
        }
    }

    if (showAddDebit) {
        AddDebitSheet(
            categories = categories,
            onDismiss = { showAddDebit = false },
            onSave = { form ->
                scope.launch(Dispatchers.IO) {
                    repo.addManualDebit(
                        amountPaise = form.amountPaise,
                        note = form.note,
                        payee = form.payee,
                        categoryId = form.categoryId,
                        occurredAt = form.occurredAt
                    )
                }
                showAddDebit = false
                Toast.makeText(context, "Debit added", Toast.LENGTH_SHORT).show()
            },
            onAddCategory = { name, color ->
                repo.addCategory(name, color)
            }
        )
    }

    if (editManualDebit != null) {
        val debit = editManualDebit!!
        AddDebitSheet(
            categories = categories,
            existingDebit = debit,
            onDismiss = { editManualDebit = null },
            onSave = { form ->
                scope.launch(Dispatchers.IO) {
                    val categoryId = form.categoryId
                        ?: categories.find { it.name == "Uncategorized" }?.id
                        ?: return@launch
                    repo.updateManualDebit(
                        debitId = debit.id,
                        amountPaise = form.amountPaise,
                        note = form.note,
                        payee = form.payee,
                        categoryId = categoryId,
                        occurredAt = form.occurredAt
                    )
                }
                editManualDebit = null
                Toast.makeText(context, "Debit updated", Toast.LENGTH_SHORT).show()
            },
            onDelete = {
                scope.launch(Dispatchers.IO) { repo.deleteManualDebit(debit.id) }
                editManualDebit = null
                Toast.makeText(context, "Debit deleted", Toast.LENGTH_SHORT).show()
            },
            onAddCategory = { name, color ->
                repo.addCategory(name, color)
            }
        )
    }

    if (recategorizeDebit != null) {
        val debitToRecat = recategorizeDebit!!
        var recatNote by remember(debitToRecat.id) {
            mutableStateOf(debitToRecat.note ?: "")
        }
        var recatCategoryId by remember(debitToRecat.id) {
            mutableStateOf(debitToRecat.categoryId)
        }
        var recatNoteError by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { recategorizeDebit = null },
            containerColor = DarkSurface,
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = formatRupees(debitToRecat.amountPaise),
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = debitToRecat.sender,
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "NOTE * (REQUIRED)",
                            color = if (recatNoteError && recatNote.isBlank()) AccentRed else TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        OutlinedTextField(
                            value = recatNote,
                            onValueChange = {
                                recatNote = it
                                if (it.isNotBlank()) recatNoteError = false
                            },
                            singleLine = true,
                            isError = recatNoteError && recatNote.isBlank(),
                            placeholder = {
                                Text(
                                    "e.g. Lunch, Grocery...",
                                    color = TextSecondary.copy(alpha = 0.5f),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                cursorColor = AccentGreen,
                                focusedBorderColor = AccentGreen,
                                unfocusedBorderColor = BorderColor,
                                errorBorderColor = AccentRed
                            )
                        )
                    }

                    Text(
                        text = "CATEGORY",
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        categories.filter { it.name != "Uncategorized" }.forEach { category ->
                            val isSelected = recatCategoryId == category.id
                            val catColor = modernCategoryColor(category)
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (isSelected) catColor.copy(alpha = 0.35f) else catColor.copy(alpha = 0.12f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .border(
                                        if (isSelected) 2.dp else 1.dp,
                                        if (isSelected) catColor else catColor.copy(alpha = 0.4f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable {
                                        recatCategoryId = category.id
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = if (isSelected) "✓ ${category.name}" else category.name,
                                    color = if (isSelected) PureWhite else catColor,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val cleanNote = recatNote.trim()
                        if (cleanNote.isBlank()) {
                            recatNoteError = true
                            Toast.makeText(context, "Note is mandatory", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        val catId = recatCategoryId ?: debitToRecat.categoryId
                        scope.launch(Dispatchers.IO) {
                            repo.categorizeDebit(debitToRecat.id, catId, note = cleanNote)
                        }
                        recategorizeDebit = null
                        val catName = categories.find { it.id == catId }?.name ?: "Category"
                        Toast.makeText(context, "Logged · $catName", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Save", color = AccentGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { recategorizeDebit = null }) {
                    Text("Cancel", color = TextSecondary, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }

    if (showManage) {
        var manageTab by remember { mutableStateOf(0) } // 0 = Categories, 1 = Parent Categories
        var categoryForParentSelector by remember { mutableStateOf<CategoryEntity?>(null) }
        var parentForSubAssigning by remember { mutableStateOf<CategoryEntity?>(null) }

        AlertDialog(
            onDismissRequest = { showManage = false },
            containerColor = DarkSurface,
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Manage categories",
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkSurfaceElevated, RoundedCornerShape(8.dp))
                            .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                            .padding(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(
                                    if (manageTab == 0) AccentBlue.copy(alpha = 0.25f) else Color.Transparent,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable { manageTab = 0 }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Categories",
                                color = if (manageTab == 0) AccentBlue else TextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = if (manageTab == 0) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(
                                    if (manageTab == 1) AccentBlue.copy(alpha = 0.25f) else Color.Transparent,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable { manageTab = 1 }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Parent Categories",
                                color = if (manageTab == 1) AccentBlue else TextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = if (manageTab == 1) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (manageTab == 0) {
                        // All Categories tab
                        categories.filter { !it.isSystem || it.name != "Uncategorized" }.forEach { category ->
                            val parentName = category.parentCategoryId?.let { categoryMap[it]?.name }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(DarkSurfaceElevated, RoundedCornerShape(10.dp))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = category.name,
                                        color = modernCategoryColor(category),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (!category.isSystem) {
                                        Text(
                                            text = "Delete",
                                            color = AccentRed,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            modifier = Modifier.clickable {
                                                scope.launch(Dispatchers.IO) {
                                                    repo.deleteCategory(category.id)
                                                }
                                            }
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (parentName != null) "Parent: $parentName" else "Parent: None",
                                        color = TextSecondary,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        text = "Change",
                                        color = AccentBlue,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        modifier = Modifier.clickable {
                                            categoryForParentSelector = category
                                        }
                                    )
                                }
                            }
                        }

                        Text(
                            text = "+ Add category",
                            color = AccentGreen,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { showAddCategory = true }
                                .padding(vertical = 4.dp)
                        )
                    } else {
                        // Parent Categories tab (Option B)
                        val potentialParents = categories.filter { it.name != "Uncategorized" }

                        if (potentialParents.isEmpty()) {
                            Text(
                                text = "No categories available to set as parent.",
                                color = TextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        } else {
                            potentialParents.forEach { parent ->
                                val subs = categories.filter { it.parentCategoryId == parent.id }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(DarkSurfaceElevated, RoundedCornerShape(12.dp))
                                        .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .background(modernCategoryColor(parent), RoundedCornerShape(50))
                                            )
                                            Text(
                                                text = parent.name,
                                                color = TextPrimary,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Text(
                                            text = "+ Assign subcategory",
                                            color = AccentGreen,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            modifier = Modifier.clickable {
                                                parentForSubAssigning = parent
                                            }
                                        )
                                    }

                                    if (subs.isEmpty()) {
                                        Text(
                                            text = "No subcategories assigned.",
                                            color = TextSecondary.copy(alpha = 0.6f),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp
                                        )
                                    } else {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            subs.forEach { sub ->
                                                val subColor = modernCategoryColor(sub)
                                                Box(
                                                    modifier = Modifier
                                                        .background(subColor.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                                                        .border(1.dp, subColor.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                                ) {
                                                    Row(
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = sub.name,
                                                            color = subColor,
                                                            fontFamily = FontFamily.Monospace,
                                                            fontSize = 11.sp
                                                        )
                                                        Text(
                                                            text = "✕",
                                                            color = AccentRed.copy(alpha = 0.8f),
                                                            fontSize = 10.sp,
                                                            modifier = Modifier.clickable {
                                                                scope.launch(Dispatchers.IO) {
                                                                    repo.setParentCategory(sub.id, null)
                                                                }
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Text(
                            text = "+ Create category",
                            color = AccentGreen,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { showAddCategory = true }
                                .padding(vertical = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showManage = false }) {
                    Text("Close", color = AccentBlue, fontFamily = FontFamily.Monospace)
                }
            }
        )

        // Subcategory assignment dialog
        if (parentForSubAssigning != null) {
            val parent = parentForSubAssigning!!
            val candidateSubs = categories.filter {
                it.id != parent.id && it.parentCategoryId != parent.id && it.name != "Uncategorized"
            }

            AlertDialog(
                onDismissRequest = { parentForSubAssigning = null },
                containerColor = DarkSurface,
                title = {
                    Text(
                        "Assign subcategory to ${parent.name}",
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (candidateSubs.isEmpty()) {
                            Text(
                                "No available categories to assign.",
                                color = TextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        } else {
                            candidateSubs.forEach { candidate ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(DarkSurfaceElevated, RoundedCornerShape(8.dp))
                                        .clickable {
                                            scope.launch(Dispatchers.IO) {
                                                repo.setParentCategory(candidate.id, parent.id)
                                            }
                                            parentForSubAssigning = null
                                        }
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = candidate.name,
                                        color = modernCategoryColor(candidate),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = "+ Select",
                                        color = AccentGreen,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { parentForSubAssigning = null }) {
                        Text("Cancel", color = TextSecondary, fontFamily = FontFamily.Monospace)
                    }
                }
            )
        }

        // Parent selector dialog for a category
        if (categoryForParentSelector != null) {
            val cat = categoryForParentSelector!!
            val candidateParents = categories.filter {
                it.id != cat.id && it.parentCategoryId != cat.id && it.name != "Uncategorized"
            }

            AlertDialog(
                onDismissRequest = { categoryForParentSelector = null },
                containerColor = DarkSurface,
                title = {
                    Text(
                        "Select parent for ${cat.name}",
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(DarkSurfaceElevated, RoundedCornerShape(8.dp))
                                .clickable {
                                    scope.launch(Dispatchers.IO) {
                                        repo.setParentCategory(cat.id, null)
                                    }
                                    categoryForParentSelector = null
                                }
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "None (Standalone)",
                                color = TextPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                            if (cat.parentCategoryId == null) {
                                Text("✓", color = AccentGreen, fontSize = 12.sp)
                            }
                        }

                        candidateParents.forEach { parent ->
                            val isSelected = cat.parentCategoryId == parent.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(DarkSurfaceElevated, RoundedCornerShape(8.dp))
                                    .clickable {
                                        scope.launch(Dispatchers.IO) {
                                            repo.setParentCategory(cat.id, parent.id)
                                        }
                                        categoryForParentSelector = null
                                    }
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = parent.name,
                                    color = modernCategoryColor(parent),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                )
                                if (isSelected) {
                                    Text("✓", color = AccentGreen, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { categoryForParentSelector = null }) {
                        Text("Cancel", color = TextSecondary, fontFamily = FontFamily.Monospace)
                    }
                }
            )
        }
    }

    if (showAddCategory) {
        AlertDialog(
            onDismissRequest = { showAddCategory = false },
            containerColor = DarkSurface,
            title = {
                Text("Add category", color = TextPrimary, fontFamily = FontFamily.Monospace)
            },
            text = {
                OutlinedTextField(
                    value = newCategoryName,
                    onValueChange = { newCategoryName = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = AccentBlue,
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = TextSecondary
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newCategoryName.trim()
                        if (name.isNotBlank()) {
                            newCategoryName = ""
                            showAddCategory = false
                            scope.launch(Dispatchers.IO) {
                                val color = pickNextCategoryColor(categories)
                                repo.addCategory(name, color)
                            }
                        }
                    }
                ) {
                    Text("Add", color = AccentGreen, fontFamily = FontFamily.Monospace)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddCategory = false }) {
                    Text("Cancel", color = TextSecondary, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }
}

@Composable
private fun FinanceCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurface, RoundedCornerShape(20.dp))
            .border(1.dp, BorderColor, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = { content() }
    )
}

@Composable
private fun MonthHeaderSelector(
    monthName: String,
    selectedYear: Int,
    selectedMonth: Int,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit
) {
    val currentCal = remember { Calendar.getInstance() }
    val isCurrentMonth = selectedYear == currentCal.get(Calendar.YEAR) &&
        selectedMonth == currentCal.get(Calendar.MONTH)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurface, RoundedCornerShape(16.dp))
            .border(1.dp, BorderColor, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .clickable { onPreviousMonth() }
                .padding(vertical = 4.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("‹", color = AccentBlue, fontFamily = FontFamily.Monospace, fontSize = 22.sp)
            Spacer(modifier = Modifier.width(4.dp))
            Text("Prev", color = AccentBlue, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }

        Text(
            text = monthName,
            color = TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp
        )

        Row(
            modifier = Modifier
                .clickable(enabled = !isCurrentMonth) { onNextMonth() }
                .padding(vertical = 4.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Next",
                color = if (isCurrentMonth) TextSecondary.copy(alpha = 0.35f) else AccentBlue,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "›",
                color = if (isCurrentMonth) TextSecondary.copy(alpha = 0.35f) else AccentBlue,
                fontFamily = FontFamily.Monospace,
                fontSize = 22.sp
            )
        }
    }
}

@Composable
private fun AverageCell(label: String, amountPaise: Long, subLabel: String = "") {
    Column(modifier = Modifier.width(155.dp)) {
        Text(
            text = label,
            color = TextSecondary,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
        if (subLabel.isNotBlank()) {
            Text(
                text = subLabel,
                color = TextSecondary.copy(alpha = 0.75f),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }
        Text(
            text = formatRupees(amountPaise),
            color = TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = TextSecondary,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        color = TextSecondary.copy(alpha = 0.8f),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 17.sp
    )
}

@Composable
private fun CategoryChip(category: CategoryEntity, onClick: () -> Unit) {
    val catColor = modernCategoryColor(category)
    Box(
        modifier = Modifier
            .background(catColor.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
            .border(1.dp, catColor.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = category.name,
            color = catColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun DebitRow(
    debit: DebitEntity,
    categoryName: String,
    categoryColor: Color? = null,
    showAutoBadge: Boolean = false,
    onTap: () -> Unit,
    onDontTrack: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM · HH:mm", Locale.US) }
    val pillColor = categoryColor ?: TextSecondary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurfaceElevated, RoundedCornerShape(16.dp))
            .border(1.dp, BorderColor, RoundedCornerShape(16.dp))
            .clickable { onTap() }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatRupees(debit.amountPaise),
                color = TextPrimary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
            Box(
                modifier = Modifier
                    .background(pillColor.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                    .border(1.dp, pillColor.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = categoryName,
                    color = pillColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        }
        Text(
            text = debit.sender,
            color = TextSecondary,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
        val noteText = debit.note?.trim()
        val textToShow = if (!noteText.isNullOrBlank()) {
            noteText
        } else {
            debit.snippet.takeIf { it.isNotBlank() && it != "Manual entry" }
        }
        if (!textToShow.isNullOrBlank()) {
            Text(
                text = textToShow,
                color = if (!noteText.isNullOrBlank()) TextPrimary else TextPrimary.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = dateFormat.format(Date(debit.occurredAt)),
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
                if (debit.isManualEntry()) {
                    Box(
                        modifier = Modifier
                            .background(AccentBlue.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Manual",
                            color = AccentBlue,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                } else if (showAutoBadge) {
                    Text(
                        text = "auto",
                        color = AccentBlue.copy(alpha = 0.8f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
            }
            if (!debit.isManualEntry()) {
                Text(
                    text = "Don't track",
                    color = AccentRed.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.clickable { onDontTrack() }
                )
            } else {
                Text(
                    text = "Edit",
                    color = AccentBlue.copy(alpha = 0.8f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.clickable { onTap() }
                )
            }
        }
    }
}
