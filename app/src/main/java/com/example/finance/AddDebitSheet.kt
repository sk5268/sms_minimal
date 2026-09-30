package com.example.finance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.parseRupeesInput
import com.example.ui.theme.AccentBlue
import com.example.ui.theme.AccentGreen
import com.example.ui.theme.AccentRed
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.PureWhite
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class ManualDebitForm(
    val amountPaise: Long,
    val payee: String,
    val note: String,
    val categoryId: Long?,
    val occurredAt: Long
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddDebitSheet(
    categories: List<CategoryEntity>,
    existingDebit: DebitEntity? = null,
    onDismiss: () -> Unit,
    onSave: (ManualDebitForm) -> Unit,
    onDelete: (() -> Unit)? = null,
    onAddCategory: (suspend (name: String, colorArgb: Int) -> Long)? = null
) {
    val isEdit = existingDebit != null
    var amountText by remember(existingDebit) {
        mutableStateOf(
            existingDebit?.let {
                val rupees = it.amountPaise / 100
                val frac = it.amountPaise % 100
                if (frac == 0L) rupees.toString() else "$rupees.${frac.toString().padStart(2, '0')}"
            } ?: ""
        )
    }
    var payee by remember(existingDebit) { mutableStateOf(existingDebit?.sender ?: "Cash") }
    var note by remember(existingDebit) { mutableStateOf(existingDebit?.note ?: existingDebit?.snippet ?: "") }
    var selectedCategoryId by remember(existingDebit) {
        mutableStateOf(existingDebit?.categoryId)
    }
    var occurredAt by remember(existingDebit) {
        mutableLongStateOf(existingDebit?.occurredAt ?: System.currentTimeMillis())
    }
    var amountError by remember { mutableStateOf<String?>(null) }

    var isAddingCategory by remember { mutableStateOf(false) }
    var newCategoryName by remember { mutableStateOf("") }
    var selectedColorArgb by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.US) }

    fun shiftDay(delta: Int) {
        val cal = Calendar.getInstance().apply { timeInMillis = occurredAt }
        cal.add(Calendar.DAY_OF_MONTH, delta)
        occurredAt = cal.timeInMillis
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        title = {
            Text(
                text = if (isEdit) "Edit debit" else "Add debit",
                color = TextPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = {
                        amountText = it
                        amountError = null
                    },
                    label = { Text("Amount", fontFamily = FontFamily.Monospace) },
                    placeholder = { Text("e.g. 500 or 1,200.50", fontFamily = FontFamily.Monospace) },
                    singleLine = true,
                    isError = amountError != null,
                    supportingText = amountError?.let { { Text(it, fontFamily = FontFamily.Monospace) } },
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors()
                )

                OutlinedTextField(
                    value = payee,
                    onValueChange = { payee = it },
                    label = { Text("Payee", fontFamily = FontFamily.Monospace) },
                    placeholder = { Text("Cash, merchant name…", fontFamily = FontFamily.Monospace) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors()
                )

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)", fontFamily = FontFamily.Monospace) },
                    placeholder = { Text("Groceries, auto fare…", fontFamily = FontFamily.Monospace) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors()
                )

                Text(
                    text = "Date",
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "‹",
                        color = AccentBlue,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 20.sp,
                        modifier = Modifier
                            .clickable { shiftDay(-1) }
                            .padding(8.dp)
                    )
                    Text(
                        text = dateFormat.format(Date(occurredAt)),
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "›",
                        color = AccentBlue,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 20.sp,
                        modifier = Modifier
                            .clickable { shiftDay(1) }
                            .padding(8.dp)
                    )
                }

                Text(
                    text = "Category",
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categories.forEach { category ->
                        val selected = selectedCategoryId == category.id
                        val catColor = modernCategoryColor(category)
                        Box(
                            modifier = Modifier
                                .background(
                                    catColor.copy(alpha = if (selected) 0.25f else 0.12f),
                                    RoundedCornerShape(14.dp)
                                )
                                .border(
                                    1.dp,
                                    catColor.copy(alpha = if (selected) 0.7f else 0.3f),
                                    RoundedCornerShape(14.dp)
                                )
                                .clickable {
                                    selectedCategoryId = if (selected) null else category.id
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = category.name,
                                color = catColor,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        }
                    }

                    if (onAddCategory != null) {
                        val isNewCatOpen = isAddingCategory
                        Box(
                            modifier = Modifier
                                .background(
                                    if (isNewCatOpen) AccentGreen.copy(alpha = 0.25f) else Color(0xFF1E2230),
                                    RoundedCornerShape(14.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isNewCatOpen) AccentGreen else AccentGreen.copy(alpha = 0.5f),
                                    RoundedCornerShape(14.dp)
                                )
                                .clickable {
                                    isAddingCategory = !isAddingCategory
                                    if (isAddingCategory) {
                                        newCategoryName = ""
                                        selectedColorArgb = pickNextCategoryColor(categories)
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (isNewCatOpen) "✕ Cancel" else "+ New",
                                color = AccentGreen,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                if (isAddingCategory && onAddCategory != null) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        color = Color(0xFF1B1E2B),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color(0xFF2E3346))
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "ADD NEW CATEGORY",
                                color = AccentGreen,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )

                            OutlinedTextField(
                                value = newCategoryName,
                                onValueChange = { newCategoryName = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = {
                                    Text(
                                        "Category name (e.g. Shopping)",
                                        color = TextSecondary.copy(alpha = 0.5f),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp
                                    )
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    cursorColor = AccentGreen,
                                    focusedBorderColor = AccentGreen,
                                    unfocusedBorderColor = Color(0xFF2E3346),
                                    focusedContainerColor = Color(0xFF10121A),
                                    unfocusedContainerColor = Color(0xFF10121A)
                                )
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CategoryChartPalette.take(6).forEach { color ->
                                        val isColorSelected = color.toArgb() == selectedColorArgb
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .background(color, CircleShape)
                                                .border(
                                                    if (isColorSelected) 2.dp else 0.dp,
                                                    PureWhite,
                                                    CircleShape
                                                )
                                                .clickable {
                                                    selectedColorArgb = color.toArgb()
                                                }
                                        )
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "Cancel",
                                        color = TextSecondary,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        modifier = Modifier
                                            .clickable { isAddingCategory = false }
                                            .padding(vertical = 4.dp, horizontal = 4.dp)
                                    )
                                    Text(
                                        text = "Add & Select",
                                        color = AccentGreen,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        modifier = Modifier
                                            .background(AccentGreen.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                                            .border(1.dp, AccentGreen.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                            .clickable {
                                                val name = newCategoryName.trim()
                                                if (name.isNotBlank()) {
                                                    coroutineScope.launch {
                                                        val color = if (selectedColorArgb != 0) selectedColorArgb else pickNextCategoryColor(categories)
                                                        val newId = onAddCategory(name, color)
                                                        selectedCategoryId = newId
                                                        isAddingCategory = false
                                                        newCategoryName = ""
                                                    }
                                                }
                                            }
                                            .padding(vertical = 4.dp, horizontal = 10.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val paise = parseRupeesInput(amountText)
                    if (paise == null || paise <= 0L) {
                        amountError = "Enter a valid amount"
                        return@TextButton
                    }
                    onSave(
                        ManualDebitForm(
                            amountPaise = paise,
                            payee = payee,
                            note = note,
                            categoryId = selectedCategoryId,
                            occurredAt = occurredAt
                        )
                    )
                }
            ) {
                Text(
                    text = if (isEdit) "Save" else "Add",
                    color = AccentGreen,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = AccentRed, fontFamily = FontFamily.Monospace)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = TextSecondary, fontFamily = FontFamily.Monospace)
                }
            }
        }
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = AccentBlue,
    focusedBorderColor = AccentBlue,
    unfocusedBorderColor = TextSecondary,
    focusedLabelColor = AccentBlue,
    unfocusedLabelColor = TextSecondary
)
