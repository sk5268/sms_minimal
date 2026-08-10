package com.example

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.finance.CategoryEntity
import com.example.finance.FinanceRepository
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
@androidx.compose.runtime.Composable
fun CategorizeOverlayContent(
    debitId: Long,
    amountPaise: Long,
    sender: String,
    snippet: String,
    notifId: Int,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { FinanceRepository.getInstance(context) }
    var categories by remember { mutableStateOf<List<CategoryEntity>>(emptyList()) }

    LaunchedEffect(Unit) {
        categories = withContext(Dispatchers.IO) { repo.getCategories() }
    }

    fun cancelNotificationIfNeeded() {
        // Drop merged-message cache so a later SMS from this sender
        // does not revive bodies from a notification already acted on.
        if (sender.isNotEmpty()) {
            SmsReceiver.clearSenderMessages(context, sender)
        }
        if (notifId != -1) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(notifId)
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
                    .padding(horizontal = 16.dp, vertical = 24.dp)
                    .clickable(enabled = false) { },
                color = Color(0xFF161821),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = formatRupees(amountPaise),
                        color = PureWhite,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 28.sp
                    )
                    Text(
                        text = sender.uppercase(),
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = snippet,
                        color = TextPrimary.copy(alpha = 0.8f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        categories.forEach { category ->
                            Box(
                                modifier = Modifier
                                    .background(
                                        Color(category.colorArgb).copy(alpha = 0.18f),
                                        RoundedCornerShape(16.dp)
                                    )
                                    .border(
                                        1.dp,
                                        Color(category.colorArgb).copy(alpha = 0.5f),
                                        RoundedCornerShape(16.dp)
                                    )
                                    .clickable {
                                        scope.launch(Dispatchers.IO) {
                                            repo.categorizeDebit(debitId, category.id)
                                        }
                                        cancelNotificationIfNeeded()
                                        android.widget.Toast.makeText(
                                            context,
                                            "Logged · ${category.name}",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                        onDismiss()
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = category.name.uppercase(),
                                    color = Color(category.colorArgb),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp
                                )
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(OLEDBlack, RoundedCornerShape(14.dp))
                            .border(1.dp, AccentRed.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                            .clickable {
                                scope.launch(Dispatchers.IO) {
                                    repo.dontTrackByDebitId(debitId)
                                }
                                cancelNotificationIfNeeded()
                                android.widget.Toast.makeText(
                                    context,
                                    "Removed from finance",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
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
