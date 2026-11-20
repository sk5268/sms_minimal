package com.example

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions as KbOptions
import androidx.compose.ui.text.input.KeyboardType
import com.example.finance.DebitEntity
import com.example.finance.Fuel
import com.example.finance.LocationCapture
import com.example.finance.TagMemory
import com.example.finance.displayPayee
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
 * Bottom sheet for tagging one debit, launched from the notification ("More…",
 * "Change") and from the Finance screen. One tap on a category saves it; the note is
 * optional. Fuel asks for the trip reading before saving.
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

    var debit by remember { mutableStateOf<DebitEntity?>(null) }
    var categories by remember { mutableStateOf<List<CategoryEntity>>(emptyList()) }
    var suggestions by remember { mutableStateOf<List<CategoryEntity>>(emptyList()) }
    var note by remember { mutableStateOf("") }
    var pendingFuelCategory by remember { mutableStateOf<CategoryEntity?>(null) }
    var tripKm by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    var isAddingCategory by remember { mutableStateOf(false) }
    var newCategoryName by remember { mutableStateOf("") }
    var selectedColorArgb by remember { mutableIntStateOf(0) }
    val newCategoryFocusRequester = remember { FocusRequester() }
    val tripFocusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    var hasLocation by remember { mutableStateOf(LocationCapture.hasForegroundPermission(context)) }
    var hasBackgroundLocation by remember { mutableStateOf(LocationCapture.hasBackgroundPermission(context)) }
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasBackgroundLocation = granted
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasLocation = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (hasLocation && !hasBackgroundLocation) {
            // Android 11+ sends this one to Settings ("Allow all the time"), which lets
            // the SMS receiver stamp the spot the moment a payment lands.
            backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    LaunchedEffect(debitId) {
        val loaded = withContext(Dispatchers.IO) { repo.getDebitById(debitId) }
        debit = loaded
        categories = withContext(Dispatchers.IO) { repo.getCategories() }
        if (loaded != null) {
            note = loaded.note.orEmpty()
            tripKm = loaded.tripKm?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }.orEmpty()
            suggestions = withContext(Dispatchers.IO) { repo.suggestionsFor(loaded, limit = 4) }
        }
    }

    val shownAmount = debit?.amountPaise?.takeIf { it > 0 } ?: amountPaise
    val payeeLine = debit?.displayPayee() ?: sender
    val currentCategoryId = debit?.takeIf { it.tagSource != TagMemory.SOURCE_NONE }?.categoryId

    fun cancelNotificationIfNeeded() {
        if (sender.isNotEmpty()) SmsReceiver.clearSenderMessages(context, sender)
        if (notifId != -1) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(notifId)
        }
    }

    fun save(category: CategoryEntity, trip: Double?) {
        if (saving) return
        saving = true
        scope.launch(Dispatchers.IO) {
            val fix = if (hasLocation && debit?.lat == null) LocationCapture.currentFix(context, 3_000L) else null
            repo.categorizeDebit(debitId, category.id, note = note, fix = fix)
            val stats = if (trip != null) repo.setTripKm(debitId, trip) else null
            withContext(Dispatchers.Main) {
                cancelNotificationIfNeeded()
                val extra = stats?.lastKmpl?.let { String.format(java.util.Locale.US, " · %.1f km/L", it) }.orEmpty()
                Toast.makeText(context, "Logged · ${category.name}$extra", Toast.LENGTH_SHORT).show()
                onDismiss()
            }
        }
    }

    fun onCategoryTap(category: CategoryEntity) {
        if (Fuel.isFuelCategory(category.name)) {
            pendingFuelCategory = category
        } else {
            save(category, null)
        }
    }

    fun createCategoryAndSave() {
        val name = newCategoryName.trim()
        if (name.isBlank()) {
            Toast.makeText(context, "Enter a category name", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch(Dispatchers.IO) {
            val color = if (selectedColorArgb != 0) selectedColorArgb else pickNextCategoryColor(categories)
            val newCatId = repo.addCategory(name, color)
            val created = repo.getCategories().find { it.id == newCatId }
            withContext(Dispatchers.Main) {
                isAddingCategory = false
                newCategoryName = ""
                created?.let { onCategoryTap(it) }
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
                    .padding(16.dp)
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = formatRupees(shownAmount),
                                color = PureWhite,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 28.sp
                            )
                            Text(
                                text = payeeLine,
                                color = TextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color(0xFF222533), CircleShape)
                                .clickable { onDismiss() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "✕", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    val fuelCategory = pendingFuelCategory
                    if (fuelCategory != null) {
                        FuelStep(
                            amountPaise = shownAmount,
                            pricePerLitre = debit?.fuelPricePerL ?: Fuel.pricePerLitre(context),
                            tripKm = tripKm,
                            onTripKmChange = { tripKm = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            focusRequester = tripFocusRequester,
                            onSave = { save(fuelCategory, tripKm.toDoubleOrNull()) },
                            onSkip = { save(fuelCategory, null) }
                        )
                        LaunchedEffect(fuelCategory.id) {
                            try { tripFocusRequester.requestFocus() } catch (_: Exception) { }
                        }
                    } else {
                        if (suggestions.isNotEmpty()) {
                            SheetLabel("SUGGESTED")
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                suggestions.forEach { category ->
                                    CategoryPill(category, selected = false, large = true) { onCategoryTap(category) }
                                }
                            }
                        }

                        SheetLabel("ALL CATEGORIES")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val suggestedIds = suggestions.map { it.id }.toSet()
                            categories.filter { it.name != "Uncategorized" && it.id !in suggestedIds }.forEach { category ->
                                CategoryPill(category, selected = category.id == currentCategoryId, large = false) {
                                    onCategoryTap(category)
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFF1E2230), RoundedCornerShape(16.dp))
                                    .border(1.dp, AccentGreen.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
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
                                    text = if (isAddingCategory) "✕ CANCEL" else "+ NEW",
                                    color = AccentGreen,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp
                                )
                            }
                        }

                        if (isAddingCategory) {
                            NewCategoryRow(
                                name = newCategoryName,
                                onNameChange = { newCategoryName = it },
                                selectedColorArgb = selectedColorArgb,
                                onColorPick = { selectedColorArgb = it },
                                focusRequester = newCategoryFocusRequester,
                                onDone = { createCategoryAndSave() }
                            )
                            LaunchedEffect(Unit) {
                                try { newCategoryFocusRequester.requestFocus() } catch (_: Exception) { }
                            }
                        }

                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(
                                    text = "Note (optional)",
                                    color = TextSecondary.copy(alpha = 0.5f),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = sheetFieldColors(),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Done
                            )
                        )
                    }

                    if (!hasLocation || !hasBackgroundLocation) {
                        Text(
                            text = if (!hasLocation) "📍 Remember places: tag a spot once, it's tagged next time · Allow"
                            else "📍 Allow location \"all the time\" so payments are stamped as they arrive",
                            color = AccentGreen.copy(alpha = 0.85f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.clickable {
                                if (!hasLocation) {
                                    locationLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                } else {
                                    backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                }
                            }
                        )
                    }

                    Text(
                        text = "Don't track this payment",
                        color = AccentRed.copy(alpha = 0.8f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.clickable {
                            scope.launch(Dispatchers.IO) { repo.dontTrackByDebitId(debitId) }
                            cancelNotificationIfNeeded()
                            Toast.makeText(context, "Removed from finance", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text = text,
        color = TextSecondary,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp
    )
}

@Composable
private fun CategoryPill(category: CategoryEntity, selected: Boolean, large: Boolean, onClick: () -> Unit) {
    val catColor = modernCategoryColor(category)
    Box(
        modifier = Modifier
            .background(catColor.copy(alpha = if (selected) 0.35f else 0.14f), RoundedCornerShape(16.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) catColor else catColor.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(horizontal = if (large) 18.dp else 14.dp, vertical = if (large) 12.dp else 8.dp)
    ) {
        Text(
            text = if (selected) "✓ ${category.name.uppercase()}" else category.name.uppercase(),
            color = if (selected) PureWhite else catColor,
            fontFamily = FontFamily.Monospace,
            fontSize = if (large) 12.sp else 10.sp,
            fontWeight = if (large || selected) FontWeight.Bold else FontWeight.Medium,
            letterSpacing = 0.8.sp
        )
    }
}

@Composable
private fun FuelStep(
    amountPaise: Long,
    pricePerLitre: Double,
    tripKm: String,
    onTripKmChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onSave: () -> Unit,
    onSkip: () -> Unit
) {
    val litres = Fuel.litres(amountPaise, pricePerLitre)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = String.format(java.util.Locale.US, "⛽ FUEL · %.2f L at ₹%.2f/L", litres, pricePerLitre),
            color = TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        OutlinedTextField(
            value = tripKm,
            onValueChange = onTripKmChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            placeholder = {
                Text(
                    "Trip km since last fill",
                    color = TextSecondary.copy(alpha = 0.5f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = sheetFieldColors(),
            keyboardOptions = KbOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave() })
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(AccentGreen, RoundedCornerShape(14.dp))
                    .clickable { onSave() }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("SAVE", color = OLEDBlack, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = "Skip",
                color = TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable { onSkip() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
        Text(
            text = "Reset the trip meter after filling.",
            color = TextSecondary.copy(alpha = 0.7f),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun NewCategoryRow(
    name: String,
    onNameChange: (String) -> Unit,
    selectedColorArgb: Int,
    onColorPick: (Int) -> Unit,
    focusRequester: FocusRequester,
    onDone: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF1B1E2B),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(0xFF2E3346))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = {
                    Text(
                        "New category (e.g. Pani Puri)",
                        color = TextSecondary.copy(alpha = 0.5f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = sheetFieldColors(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone() })
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CategoryChartPalette.take(6).forEach { color ->
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .background(color, CircleShape)
                                .border(if (color.toArgb() == selectedColorArgb) 2.dp else 0.dp, PureWhite, CircleShape)
                                .clickable { onColorPick(color.toArgb()) }
                        )
                    }
                }
                Text(
                    text = "Add & tag",
                    color = AccentGreen,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .background(AccentGreen.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                        .clickable { onDone() }
                        .padding(vertical = 4.dp, horizontal = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun sheetFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = AccentGreen,
    focusedBorderColor = AccentGreen,
    unfocusedBorderColor = Color(0xFF2E3346),
    focusedContainerColor = Color(0xFF0F1118),
    unfocusedContainerColor = Color(0xFF0F1118)
)
