package com.example

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
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
import androidx.compose.ui.platform.ComposeView
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

class CategorizeOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS) {
            removeOverlay()
            stopSelf()
            return START_NOT_STICKY
        }

        val debitId = intent?.getLongExtra(EXTRA_DEBIT_ID, -1L) ?: -1L
        val amountPaise = intent?.getLongExtra(EXTRA_AMOUNT_PAISE, 0L) ?: 0L
        val sender = intent?.getStringExtra(EXTRA_SENDER).orEmpty()
        val snippet = intent?.getStringExtra(EXTRA_SNIPPET).orEmpty()
        val notifId = intent?.getIntExtra(EXTRA_NOTIF_ID, -1) ?: -1

        if (debitId <= 0L) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(settingsIntent)
            android.widget.Toast.makeText(
                this,
                "Grant overlay permission to categorize from notifications",
                android.widget.Toast.LENGTH_LONG
            ).show()
            stopSelf()
            return START_NOT_STICKY
        }

        showOverlay(debitId, amountPaise, sender, snippet, notifId)
        return START_NOT_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay(
        debitId: Long,
        amountPaise: Long,
        sender: String,
        snippet: String,
        notifId: Int
    ) {
        removeOverlay()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        val composeView = ComposeView(this).apply {
            setContent {
                OverlayContent(
                    debitId = debitId,
                    amountPaise = amountPaise,
                    sender = sender,
                    snippet = snippet,
                    notifId = notifId,
                    onDismiss = {
                        removeOverlay()
                        stopSelf()
                    }
                )
            }
        }

        overlayView = composeView
        windowManager?.addView(composeView, params)
    }

    private fun removeOverlay() {
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {
            }
        }
        overlayView = null
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }

    @OptIn(ExperimentalLayoutApi::class)
    @androidx.compose.runtime.Composable
    private fun OverlayContent(
        debitId: Long,
        amountPaise: Long,
        sender: String,
        snippet: String,
        notifId: Int,
        onDismiss: () -> Unit
    ) {
        val scope = rememberCoroutineScope()
        val repo = remember { FinanceRepository.getInstance(this) }
        var categories by remember { mutableStateOf<List<CategoryEntity>>(emptyList()) }

        LaunchedEffect(Unit) {
            categories = withContext(Dispatchers.IO) { repo.getCategories() }
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
                                            cancelNotificationIfNeeded(notifId)
                                            android.widget.Toast.makeText(
                                                this@CategorizeOverlayService,
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
                                    cancelNotificationIfNeeded(notifId)
                                    android.widget.Toast.makeText(
                                        this@CategorizeOverlayService,
                                        "Removed from finance",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                    onDismiss()
                                }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Don't Track".uppercase(),
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

    private fun cancelNotificationIfNeeded(notifId: Int) {
        if (notifId != -1) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(notifId)
        }
    }

    companion object {
        const val ACTION_DISMISS = "com.example.ACTION_OVERLAY_DISMISS"
        const val EXTRA_DEBIT_ID = "extra_debit_id"
        const val EXTRA_AMOUNT_PAISE = "extra_amount_paise"
        const val EXTRA_SENDER = "extra_sender"
        const val EXTRA_SNIPPET = "extra_snippet"
        const val EXTRA_NOTIF_ID = "extra_notif_id"

        fun start(context: Context, debitId: Long, amountPaise: Long, sender: String, snippet: String, notifId: Int) {
            val intent = Intent(context, CategorizeOverlayService::class.java).apply {
                putExtra(EXTRA_DEBIT_ID, debitId)
                putExtra(EXTRA_AMOUNT_PAISE, amountPaise)
                putExtra(EXTRA_SENDER, sender)
                putExtra(EXTRA_SNIPPET, snippet)
                putExtra(EXTRA_NOTIF_ID, notifId)
            }
            context.startService(intent)
        }
    }
}

fun formatRupees(paise: Long): String {
    val rupees = paise / 100
    val frac = paise % 100
    return if (frac == 0L) "₹%,d".format(rupees) else "₹%,d.%02d".format(rupees, frac)
}
