package com.example

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.finance.CategoryChartPalette
import com.example.finance.CategoryEntity
import com.example.finance.FinanceRepository
import com.example.finance.modernCategoryColor
import com.example.finance.pickNextCategoryColor
import com.example.ui.theme.AccentGreen
import com.example.ui.theme.AccentRed
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.OLEDBlack
import com.example.ui.theme.PureWhite
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen translucent sheet launched from notification actions.
 * Runs as an Activity so Compose has a lifecycle and no overlay permission is required.
 */
class CategorizeOverlayActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val debitId = intent.getLongExtra(EXTRA_DEBIT_ID, -1L)
        if (debitId <= 0L) {
            finish()
            return
        }

        val amountPaise = intent.getLongExtra(EXTRA_AMOUNT_PAISE, 0L)
        val sender = intent.getStringExtra(EXTRA_SENDER).orEmpty()
        val snippet = intent.getStringExtra(EXTRA_SNIPPET).orEmpty()
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)

        setContent {
            CategorizeOverlayContent(
                debitId = debitId,
                amountPaise = amountPaise,
                sender = sender,
                snippet = snippet,
                notifId = notifId,
                onDismiss = { finish() }
            )
        }
    }

    companion object {
        const val EXTRA_DEBIT_ID = "extra_debit_id"
        const val EXTRA_AMOUNT_PAISE = "extra_amount_paise"
        const val EXTRA_SENDER = "extra_sender"
        const val EXTRA_SNIPPET = "extra_snippet"
        const val EXTRA_NOTIF_ID = "extra_notif_id"

        fun start(
            context: Context,
            debitId: Long,
            amountPaise: Long,
            sender: String,
            snippet: String,
            notifId: Int
        ) {
            val intent = Intent(context, CategorizeOverlayActivity::class.java).apply {
                putExtra(EXTRA_DEBIT_ID, debitId)
                putExtra(EXTRA_AMOUNT_PAISE, amountPaise)
                putExtra(EXTRA_SENDER, sender)
                putExtra(EXTRA_SNIPPET, snippet)
                putExtra(EXTRA_NOTIF_ID, notifId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategorizeOverlayContent(
    debitId: Long,
    amountPaise: Long,
    sender: String,
    snippet: String,
    notifId: Int,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { FinanceRepository.getInstance(context) }

    var resolvedAmount by remember { mutableStateOf(amountPaise) }
    var resolvedSender by remember { mutableStateOf(sender) }
    var resolvedSnippet by remember { mutableStateOf(snippet) }

    var categories by remember { mutableStateOf<List<CategoryEntity>>(emptyList()) }
    var selectedCategoryId by remember { mutableStateOf<Long?>(null) }
    var note by remember { mutableStateOf("") }
    var showNoteError by remember { mutableStateOf(false) }
    var showCategoryError by remember { mutableStateOf(false) }

    var isAddingCategory by remember { mutableStateOf(false) }
    var newCategoryName by remember { mutableStateOf("") }
    var selectedColorArgb by remember { mutableIntStateOf(0) }

    val noteFocusRequester = remember { FocusRequester() }
    val newCategoryFocusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    LaunchedEffect(debitId) {
        val allCats = withContext(Dispatchers.IO) { repo.getCategories() }
        categories = allCats

        val existingDebit = withContext(Dispatchers.IO) { repo.getDebitById(debitId) }
        if (existingDebit != null) {
            if (resolvedAmount <= 0L) resolvedAmount = existingDebit.amountPaise
            if (resolvedSender.isBlank()) resolvedSender = existingDebit.sender
            if (resolvedSnippet.isBlank()) resolvedSnippet = existingDebit.snippet

            val uncategorized = allCats.find { it.name.equals("Uncategorized", ignoreCase = true) }
            if (existingDebit.categoryId != uncategorized?.id) {
                selectedCategoryId = existingDebit.categoryId
            }
            if (!existingDebit.note.isNullOrBlank()) {
                note = existingDebit.note
            }
        }
        try {
            noteFocusRequester.requestFocus()
        } catch (_: Exception) { }
    }

    fun cancelNotificationIfNeeded() {
        if (resolvedSender.isNotEmpty()) {
            SmsReceiver.clearSenderMessages(context, resolvedSender)
        }
        if (notifId != -1) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(notifId)
        }
    }

    fun performSave() {
        val cleanNote = note.trim()
        if (cleanNote.isBlank()) {
            showNoteError = true
            try { noteFocusRequester.requestFocus() } catch (_: Exception) { }
            Toast.makeText(context, "Note is mandatory", Toast.LENGTH_SHORT).show()
            return
        }

        val catId = selectedCategoryId
        if (catId == null) {
            showCategoryError = true
            Toast.makeText(context, "Please select a category", Toast.LENGTH_SHORT).show()
            return
        }

        val catName = categories.find { it.id == catId }?.name ?: "Category"
        scope.launch(Dispatchers.IO) {
            repo.categorizeDebit(debitId, catId, note = cleanNote)
        }
        cancelNotificationIfNeeded()
        Toast.makeText(context, "Logged · $catName", Toast.LENGTH_SHORT).show()
        onDismiss()
    }

    fun createCategoryAndSelect() {
        val name = newCategoryName.trim()
        if (name.isBlank()) {
            Toast.makeText(context, "Enter a category name", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch(Dispatchers.IO) {
            val color = if (selectedColorArgb != 0) selectedColorArgb else pickNextCategoryColor(categories)
            val newCatId = repo.addCategory(name, color)
            val refreshed = repo.getCategories()
            withContext(Dispatchers.Main) {
                categories = refreshed
                selectedCategoryId = newCatId
                isAddingCategory = false
                newCategoryName = ""
                showCategoryError = false
                Toast.makeText(context, "Category \"$name\" created", Toast.LENGTH_SHORT).show()
            }
        }
    }

    MyApplicationTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xCC07080B))
                .clickable { onDismiss() },
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .clickable(enabled = false) { },
                color = Color(0xFF161821),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Color(0xFF282B37))
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Header: Amount & Close Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatRupees(resolvedAmount),
                            color = PureWhite,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 28.sp
                        )
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color(0xFF222533), CircleShape)
                                .clickable { onDismiss() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "✕",
                                color = TextSecondary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Sender & SMS message snippet
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (resolvedSender.isNotBlank()) {
                            Text(
                                text = resolvedSender.uppercase(),
                                color = TextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.sp
                            )
                        }
                        if (resolvedSnippet.isNotBlank()) {
                            Text(
                                text = resolvedSnippet,
                                color = TextPrimary.copy(alpha = 0.75f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Mandatory Note Section
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "NOTE * (REQUIRED)",
                                color = if (showNoteError && note.isBlank()) AccentRed else TextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                            if (showNoteError && note.isBlank()) {
                                Text(
                                    text = "Mandatory",
                                    color = AccentRed,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        OutlinedTextField(
                            value = note,
                            onValueChange = {
                                note = it
                                if (it.isNotBlank()) showNoteError = false
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(noteFocusRequester),
                            placeholder = {
                                Text(
                                    text = "What was this for? (e.g. Lunch, Uber, Groceries)",
                                    color = TextSecondary.copy(alpha = 0.5f),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                )
                            },
                            singleLine = true,
                            isError = showNoteError && note.isBlank(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                cursorColor = AccentGreen,
                                focusedBorderColor = AccentGreen,
                                unfocusedBorderColor = Color(0xFF2E3346),
                                errorBorderColor = AccentRed,
                                errorCursorColor = AccentRed,
                                focusedContainerColor = Color(0xFF0F1118),
                                unfocusedContainerColor = Color(0xFF0F1118),
                                errorContainerColor = Color(0xFF0F1118)
                            ),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    performSave()
                                }
                            )
                        )
                    }

                    // Category Section
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "CATEGORY * (REQUIRED)",
                                color = if (showCategoryError && selectedCategoryId == null) AccentRed else TextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                            if (showCategoryError && selectedCategoryId == null) {
                                Text(
                                    text = "Please select a category",
                                    color = AccentRed,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val displayCategories = categories.filter { it.name != "Uncategorized" }
                            displayCategories.forEach { category ->
                                val isSelected = selectedCategoryId == category.id
                                val catColor = modernCategoryColor(category)
                                val chipBg = if (isSelected) catColor.copy(alpha = 0.35f) else catColor.copy(alpha = 0.12f)
                                val chipBorder = if (isSelected) catColor else catColor.copy(alpha = 0.4f)
                                val borderWidth = if (isSelected) 2.dp else 1.dp

                                Box(
                                    modifier = Modifier
                                        .background(chipBg, RoundedCornerShape(16.dp))
                                        .border(borderWidth, chipBorder, RoundedCornerShape(16.dp))
                                        .clickable {
                                            selectedCategoryId = category.id
                                            showCategoryError = false
                                            if (note.isBlank()) {
                                                showNoteError = true
                                                try { noteFocusRequester.requestFocus() } catch (_: Exception) { }
                                            }
                                        }
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = if (isSelected) "✓ ${category.name.uppercase()}" else category.name.uppercase(),
                                        color = if (isSelected) PureWhite else catColor,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        letterSpacing = 0.8.sp
                                    )
                                }
                            }

                            // "+ NEW CATEGORY" button right inline
                            val isNewCatOpen = isAddingCategory
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (isNewCatOpen) AccentGreen.copy(alpha = 0.25f) else Color(0xFF1E2230),
                                        RoundedCornerShape(16.dp)
                                    )
                                    .border(
                                        1.dp,
                                        if (isNewCatOpen) AccentGreen else AccentGreen.copy(alpha = 0.5f),
                                        RoundedCornerShape(16.dp)
                                    )
                                    .clickable {
                                        isAddingCategory = !isAddingCategory
                                        if (isAddingCategory) {
                                            newCategoryName = ""
                                            selectedColorArgb = pickNextCategoryColor(categories)
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = if (isNewCatOpen) "✕ CANCEL" else "+ NEW",
                                    color = AccentGreen,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp
                                )
                            }
                        }

                        // Inline Category Creator
                        if (isAddingCategory) {
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
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .focusRequester(newCategoryFocusRequester),
                                        placeholder = {
                                            Text(
                                                "Category name (e.g. Shopping, Subscriptions)",
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
                                        ),
                                        keyboardOptions = KeyboardOptions(
                                            capitalization = KeyboardCapitalization.Words,
                                            imeAction = ImeAction.Done
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onDone = { createCategoryAndSelect() }
                                        )
                                    )

                                    LaunchedEffect(isAddingCategory) {
                                        if (isAddingCategory) {
                                            try { newCategoryFocusRequester.requestFocus() } catch (_: Exception) { }
                                        }
                                    }

                                    // Color selector palette
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
                                                    .clickable { createCategoryAndSelect() }
                                                    .padding(vertical = 4.dp, horizontal = 10.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    // Primary Action: Log Debit
                    val isFormValid = note.isNotBlank() && selectedCategoryId != null
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isFormValid) AccentGreen else Color(0xFF222533),
                                RoundedCornerShape(14.dp)
                            )
                            .clickable {
                                performSave()
                            }
                            .padding(vertical = 13.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "LOG DEBIT",
                            color = if (isFormValid) OLEDBlack else TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }

                    // Don't track action
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(OLEDBlack, RoundedCornerShape(14.dp))
                            .border(1.dp, AccentRed.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                            .clickable {
                                scope.launch(Dispatchers.IO) {
                                    repo.dontTrackByDebitId(debitId)
                                }
                                cancelNotificationIfNeeded()
                                Toast.makeText(context, "Removed from finance", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "DON'T TRACK",
                            color = AccentRed,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }
    }
}
