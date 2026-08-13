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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.formatRupees
import com.example.ui.theme.AccentBlue
import com.example.ui.theme.AccentGreen
import com.example.ui.theme.AccentOrange
import com.example.ui.theme.AccentRed
import com.example.ui.theme.OLEDBlack
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FinanceScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { FinanceRepository.getInstance(context) }

    val categories by repo.observeCategories().collectAsState(initial = emptyList())
    val debits by repo.observeDebits().collectAsState(initial = emptyList())
    var stats by remember { mutableStateOf<FinanceStats?>(null) }
    var isScanning by remember { mutableStateOf(false) }
    var showManage by remember { mutableStateOf(false) }
    var showAddCategory by remember { mutableStateOf(false) }
    var recategorizeDebit by remember { mutableStateOf<DebitEntity?>(null) }
    var newCategoryName by remember { mutableStateOf("") }
    var isRecentDebitsExpanded by remember { mutableStateOf(false) }

    val categoryMap = categories.associateBy { it.id }

    val currentCal = remember { Calendar.getInstance() }
    var selectedYear by remember { mutableStateOf(currentCal.get(Calendar.YEAR)) }
    var selectedMonth by remember { mutableStateOf(currentCal.get(Calendar.MONTH)) } // 0-indexed

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
        val end = cal.timeInMillis
        Pair(start, end)
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

    val weekDateRangeLabel = remember(stats) {
        if (stats != null && stats!!.weekStartTimestamp > 0L && stats!!.weekEndTimestamp > 0L) {
            val fmt = SimpleDateFormat("dd MMM", Locale.US)
            "${fmt.format(Date(stats!!.weekStartTimestamp))} - ${fmt.format(Date(stats!!.weekEndTimestamp))}"
        } else {
            ""
        }
    }

    LaunchedEffect(debits, categories, selectedYear, selectedMonth) {
        stats = withContext(Dispatchers.IO) { repo.getStats(selectedYear, selectedMonth) }
    }

    val monthDebits = remember(debits, monthStartMs, monthEndMs) {
        debits.filter { it.occurredAt >= monthStartMs && it.occurredAt < monthEndMs }
    }

    val sparklineTotals = remember(stats, monthDebits) {
        val fromStats = stats?.dailyTotals.orEmpty().filter { it.totalPaise > 0 }
        if (fromStats.isNotEmpty()) fromStats
        else if (monthDebits.isNotEmpty()) {
            monthDebits.groupBy { localDayStart(it.occurredAt) }
                .map { (day, list) -> DailyTotal(day, list.sumOf { it.amountPaise }) }
                .sortedBy { it.dayStart }
        } else {
            emptyList()
        }
    }

    val categoryTotals = remember(stats, monthDebits, categoryMap) {
        val fromStats = stats?.categoryTotals.orEmpty()
            .map { total ->
                val name = categoryMap[total.categoryId]?.name ?: "Uncategorized"
                Triple(total.categoryId, name, total.totalPaise)
            }
            .filter { it.third > 0 }

        val source = if (fromStats.isNotEmpty()) {
            fromStats
        } else if (monthDebits.isNotEmpty()) {
            monthDebits.groupBy { it.categoryId }
                .map { (categoryId, list) ->
                    val name = categoryMap[categoryId]?.name ?: "Uncategorized"
                    Triple(categoryId, name, list.sumOf { it.amountPaise })
                }
        } else {
            emptyList()
        }

        source.sortedByDescending { it.third }.take(6)
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp, start = 20.dp, end = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            MonthHeaderSelector(
                selectedYear = selectedYear,
                selectedMonth = selectedMonth,
                monthName = monthName,
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(OLEDBlack, RoundedCornerShape(20.dp))
                    .border(1.dp, Color(0xFF1E2027), RoundedCornerShape(20.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SectionLabel("Spend pulse · $monthShortName")
                SpendSparkline(
                    dailyTotals = sparklineTotals,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = monthName.uppercase(),
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = 1.2.sp
                )
                Text(
                    text = formatRupees(stats?.monthTotalPaise ?: 0L),
                    color = PureWhite,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 34.sp
                )
            }
        }

        item {
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

        item {
            SectionLabel("Category breakdown")
            val totals = categoryTotals

            if (totals.isEmpty()) {
                EmptyHint("Debit SMS will auto-log here. Scan inbox for history.")
            } else {
                val barColors = resolveCategoryColors(
                    categoryIds = totals.map { it.first },
                    categoryMap = categoryMap
                )
                val multiLineSeries = remember(categoryDailySeries, barColors) {
                    categoryDailySeries.mapIndexed { index, series ->
                        series.copy(color = barColors[index])
                    }
                }
                val chartSlices = totals.mapIndexed { index, (_, name, amount) ->
                    CategoryChartSlice(name, amount, barColors[index])
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CategoryDonutChart(
                        slices = chartSlices,
                        modifier = Modifier.weight(0.42f)
                    )
                    Column(
                        modifier = Modifier.weight(0.58f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        totals.forEachIndexed { index, (_, name, amount) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(
                                            barColors[index],
                                            RoundedCornerShape(50)
                                        )
                                )
                                Text(
                                    text = name.uppercase(),
                                    color = TextSecondary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 8.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = formatRupees(amount),
                                    color = TextPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                CategoryMultiLineChart(
                    series = multiLineSeries,
                    dayStarts = monthDayStarts
                )
            }
        }

        if (attentionDebits.isNotEmpty()) {
            item { SectionLabel("Needs attention") }
            items(attentionDebits, key = { it.id }) { debit ->
                DebitRow(
                    debit = debit,
                    categoryName = categoryMap[debit.categoryId]?.name ?: "Uncategorized",
                    onRecategorize = { recategorizeDebit = debit },
                    onDontTrack = {
                        scope.launch(Dispatchers.IO) {
                            repo.dontTrackByDebitId(debit.id)
                        }
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
                    SectionLabel("Debits · $monthShortName")
                    Icon(
                        imageVector = if (isRecentDebitsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isRecentDebitsExpanded) "Collapse" else "Expand",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = if (isScanning) "Scanning…" else "Scan inbox",
                    color = AccentBlue,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    letterSpacing = 0.8.sp,
                    modifier = Modifier.clickable(enabled = !isScanning) {
                        isScanning = true
                        scope.launch(Dispatchers.IO) {
                            val count = repo.scanInbox()
                            withContext(Dispatchers.Main) {
                                isScanning = false
                                Toast.makeText(
                                    context,
                                    "Added ${count} debits",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                )
            }
        }

        if (isRecentDebitsExpanded) {
            if (recentDebits.isEmpty()) {
                item { EmptyHint("No debits recorded in $monthShortName. Scan inbox for history.") }
            } else {
                items(recentDebits, key = { it.id }) { debit ->
                    DebitRow(
                        debit = debit,
                        categoryName = categoryMap[debit.categoryId]?.name ?: "Uncategorized",
                        showAutoBadge = debit.autoCategorized,
                        onRecategorize = { recategorizeDebit = debit },
                        onDontTrack = {
                            scope.launch(Dispatchers.IO) {
                                repo.dontTrackByDebitId(debit.id)
                            }
                            Toast.makeText(context, "Removed from finance", Toast.LENGTH_SHORT).show()
                        }
                    )
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
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    if (recategorizeDebit != null) {
        AlertDialog(
            onDismissRequest = { recategorizeDebit = null },
            containerColor = Color(0xFF161821),
            title = {
                Text(
                    text = formatRupees(recategorizeDebit!!.amountPaise),
                    color = PureWhite,
                    fontFamily = FontFamily.Monospace
                )
            },
            text = {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categories.forEach { category ->
                        CategoryChip(category) {
                            val debit = recategorizeDebit!!
                            scope.launch(Dispatchers.IO) {
                                repo.categorizeDebit(debit.id, category.id)
                            }
                            recategorizeDebit = null
                            Toast.makeText(
                                context,
                                "Logged · ${category.name}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { recategorizeDebit = null }) {
                    Text("CANCEL", color = TextSecondary, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }

    if (showManage) {
        AlertDialog(
            onDismissRequest = { showManage = false },
            containerColor = Color(0xFF161821),
            title = {
                Text(
                    "Manage categories",
                    color = PureWhite,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.filter { !it.isSystem || it.name != "Uncategorized" }.forEach { category ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = category.name,
                                color = Color(category.colorArgb),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                            if (!category.isSystem) {
                                Text(
                                    text = "DELETE",
                                    color = AccentRed,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    modifier = Modifier.clickable {
                                        scope.launch(Dispatchers.IO) {
                                            repo.deleteCategory(category.id)
                                        }
                                    }
                                )
                            }
                        }
                    }
                    Text(
                        text = "Add category",
                        color = AccentGreen,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.clickable { showAddCategory = true }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showManage = false }) {
                    Text("CANCEL", color = TextSecondary, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }

    if (showAddCategory) {
        AlertDialog(
            onDismissRequest = { showAddCategory = false },
            containerColor = Color(0xFF161821),
            title = {
                Text(
                    "Add category",
                    color = PureWhite,
                    fontFamily = FontFamily.Monospace
                )
            },
            text = {
                OutlinedTextField(
                    value = newCategoryName,
                    onValueChange = { newCategoryName = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite,
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
                                val paletteColors = listOf(
                                    0xFFFF9F0A.toInt(), // Orange
                                    0xFF4086FF.toInt(), // Electric Blue
                                    0xFF32D74B.toInt(), // Neon Green
                                    0xFFFF453A.toInt(), // Red
                                    0xFF00E5FF.toInt(), // Neon Cyan
                                    0xFFBF5AF2.toInt(), // Purple
                                    0xFFFFD60A.toInt(), // Yellow
                                    0xFFFF375F.toInt(), // Pink
                                    0xFF64D2FF.toInt(), // Light Blue
                                    0xFFE040FB.toInt()  // Magenta
                                )
                                val existingColors = categories.map { it.colorArgb }.toSet()
                                val color = paletteColors.firstOrNull { it !in existingColors }
                                    ?: paletteColors[(categories.size) % paletteColors.size]
                                repo.addCategory(name, color)
                            }
                        }
                    }
                ) {
                    Text("+ ADD", color = AccentGreen, fontFamily = FontFamily.Monospace)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddCategory = false }) {
                    Text("CANCEL", color = TextSecondary, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }
}

@Composable
private fun MonthHeaderSelector(
    selectedYear: Int,
    selectedMonth: Int,
    monthName: String,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit
) {
    val currentCal = remember { Calendar.getInstance() }
    val isCurrentMonth = selectedYear == currentCal.get(Calendar.YEAR) && selectedMonth == currentCal.get(Calendar.MONTH)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OLEDBlack, RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFF1E2027), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .clickable { onPreviousMonth() }
                .padding(vertical = 4.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "‹",
                color = AccentBlue,
                fontFamily = FontFamily.Monospace,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "PREV",
                color = AccentBlue,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        Text(
            text = monthName.uppercase(),
            color = PureWhite,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            letterSpacing = 1.sp
        )

        Row(
            modifier = Modifier
                .clickable(enabled = !isCurrentMonth) { onNextMonth() }
                .padding(vertical = 4.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "NEXT",
                color = if (isCurrentMonth) TextSecondary.copy(alpha = 0.3f) else AccentBlue,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "›",
                color = if (isCurrentMonth) TextSecondary.copy(alpha = 0.3f) else AccentBlue,
                fontFamily = FontFamily.Monospace,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun AverageCell(label: String, amountPaise: Long, subLabel: String = "") {
    Column(modifier = Modifier.width(160.dp)) {
        Text(
            text = label.uppercase(),
            color = TextSecondary,
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            letterSpacing = 1.sp
        )
        if (subLabel.isNotBlank()) {
            Text(
                text = subLabel,
                color = TextSecondary.copy(alpha = 0.75f),
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp
            )
        }
        Text(
            text = formatRupees(amountPaise),
            color = PureWhite,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = TextSecondary,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        color = TextSecondary.copy(alpha = 0.7f),
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        lineHeight = 14.sp
    )
}

@Composable
private fun CategoryChip(category: CategoryEntity, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(Color(category.colorArgb).copy(alpha = 0.15f), RoundedCornerShape(14.dp))
            .border(1.dp, Color(category.colorArgb).copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = category.name.uppercase(),
            color = Color(category.colorArgb),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun DebitRow(
    debit: DebitEntity,
    categoryName: String,
    showAutoBadge: Boolean = false,
    onRecategorize: () -> Unit,
    onDontTrack: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM · HH:mm", Locale.US) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(OLEDBlack, RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFF1E2027), RoundedCornerShape(16.dp))
            .clickable { onRecategorize() }
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
                color = PureWhite,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Text(
                text = categoryName.uppercase(),
                color = AccentGreen,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                letterSpacing = 0.8.sp
            )
        }
        Text(
            text = debit.sender.uppercase(),
            color = TextSecondary,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp
        )
        Text(
            text = debit.snippet,
            color = TextPrimary.copy(alpha = 0.75f),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = dateFormat.format(Date(debit.occurredAt)),
                    color = TextSecondary.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp
                )
                if (showAutoBadge) {
                    Text(
                        text = "auto",
                        color = AccentBlue,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp
                    )
                }
            }
            Text(
                text = "Don't Track",
                color = AccentRed.copy(alpha = 0.85f),
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                modifier = Modifier.clickable { onDontTrack() }
            )
        }
    }
}
