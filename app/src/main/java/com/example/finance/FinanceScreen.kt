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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import java.util.Date
import java.util.Locale

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

    LaunchedEffect(debits) {
        stats = withContext(Dispatchers.IO) { repo.getStats() }
    }

    val uncategorizedId = categories.find { it.name == "Uncategorized" }?.id
    val uncategorizedDebits = debits.filter { it.categoryId == uncategorizedId }
    val categoryMap = categories.associateBy { it.id }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp, start = 20.dp, end = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "This month",
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
                SpendSparkline(
                    dailyTotals = stats?.dailyTotals.orEmpty(),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                AverageCell("Daily avg", stats?.dailyAveragePaise ?: 0L)
                AverageCell("Weekly avg", stats?.weeklyAveragePaise ?: 0L)
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
            SectionLabel("Category heat")
            val totals = stats?.categoryTotals.orEmpty()
                .mapNotNull { total ->
                    val cat = categoryMap[total.categoryId] ?: return@mapNotNull null
                    cat.name to total.totalPaise
                }
                .sortedByDescending { it.second }
                .take(6)

            if (totals.isEmpty()) {
                EmptyHint("Debit SMS will auto-log here. Scan inbox for history.")
            } else {
                CategoryBarChart(
                    entries = totals,
                    colors = totals.map { entry ->
                        val cat = categories.find { it.name == entry.first }
                        Color(cat?.colorArgb ?: AccentBlue.toArgb())
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                totals.forEach { (name, amount) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = name.uppercase(),
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = formatRupees(amount),
                            color = TextPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        if (uncategorizedDebits.isNotEmpty()) {
            item { SectionLabel("Needs attention") }
            items(uncategorizedDebits.take(8), key = { it.id }) { debit ->
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
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionLabel("Recent debits")
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

        if (debits.isEmpty()) {
            item { EmptyHint("Debit SMS will auto-log here. Scan inbox for history.") }
        } else {
            items(debits.take(20), key = { it.id }) { debit ->
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
                        if (newCategoryName.isNotBlank()) {
                            scope.launch(Dispatchers.IO) {
                                repo.addCategory(newCategoryName, AccentOrange.toArgb())
                            }
                            newCategoryName = ""
                            showAddCategory = false
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
private fun AverageCell(label: String, amountPaise: Long) {
    Column(modifier = Modifier.width(160.dp)) {
        Text(
            text = label.uppercase(),
            color = TextSecondary,
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            letterSpacing = 1.sp
        )
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

private fun Color.toArgb(): Int {
    return android.graphics.Color.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt()
    )
}
