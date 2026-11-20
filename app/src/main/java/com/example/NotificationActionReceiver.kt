package com.example

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.RemoteInput
import com.example.finance.DebitNotifier
import com.example.finance.FinanceRepository
import com.example.finance.LocationCapture
import kotlinx.coroutines.runBlocking

class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_COPY_OTP = "com.example.ACTION_COPY_OTP"
        const val ACTION_DELETE_SMS = "com.example.ACTION_DELETE_SMS"
        const val ACTION_DISMISS = "com.example.ACTION_DISMISS"
        const val ACTION_CATEGORIZE = "com.example.ACTION_CATEGORIZE"
        const val ACTION_DONT_TRACK = "com.example.ACTION_DONT_TRACK"
        const val ACTION_TAG = "com.example.ACTION_TAG"
        const val ACTION_TRIP_KM = "com.example.ACTION_TRIP_KM"
        const val EXTRA_CATEGORY_ID = "com.example.EXTRA_CATEGORY_ID"
        const val EXTRA_OTP = "com.example.EXTRA_OTP"
        const val EXTRA_SMS_URI = "com.example.EXTRA_SMS_URI"
        const val EXTRA_NOTIF_ID = "com.example.EXTRA_NOTIF_ID"
        const val EXTRA_SENDER = "com.example.EXTRA_SENDER"
        const val EXTRA_DEBIT_ID = "com.example.EXTRA_DEBIT_ID"
        const val EXTRA_AMOUNT_PAISE = "com.example.EXTRA_AMOUNT_PAISE"
        const val EXTRA_SNIPPET = "com.example.EXTRA_SNIPPET"
        const val EXTRA_SMS_MESSAGE_ID = "com.example.EXTRA_SMS_MESSAGE_ID"
        const val EXTRA_MESSAGE_KEY = "com.example.EXTRA_MESSAGE_KEY"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
        val appContext = context.applicationContext

        when (action) {
            ACTION_COPY_OTP -> {
                val otp = intent.getStringExtra(EXTRA_OTP)
                if (!otp.isNullOrEmpty()) {
                    val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("OTP", otp)
                    clipboard.setPrimaryClip(clip)
                    deleteSmsFromNotification(appContext, intent)
                    Toast.makeText(appContext, "OTP Copied", Toast.LENGTH_SHORT).show()
                }
                dismissSenderNotification(appContext, intent.getStringExtra(EXTRA_SENDER), notifId)
            }
            ACTION_DELETE_SMS -> {
                val pendingResult = goAsync()
                Thread {
                    try {
                        val deleted = deleteSmsFromNotification(appContext, intent)
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(
                                appContext,
                                if (deleted) "SMS Deleted" else "Delete failed",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(appContext, "Delete failed", Toast.LENGTH_SHORT).show()
                        }
                    } finally {
                        dismissSenderNotification(appContext, intent.getStringExtra(EXTRA_SENDER), notifId)
                        pendingResult.finish()
                    }
                }.start()
            }
            ACTION_CATEGORIZE -> {
                val debitId = intent.getLongExtra(EXTRA_DEBIT_ID, -1L)
                val amountPaise = intent.getLongExtra(EXTRA_AMOUNT_PAISE, 0L)
                val sender = intent.getStringExtra(EXTRA_SENDER).orEmpty()
                val snippet = intent.getStringExtra(EXTRA_SNIPPET).orEmpty()
                if (debitId > 0L) {
                    CategorizeOverlayActivity.start(
                        context = appContext,
                        debitId = debitId,
                        amountPaise = amountPaise,
                        sender = sender,
                        snippet = snippet,
                        notifId = notifId
                    )
                }
            }
            ACTION_DONT_TRACK -> {
                val messageKey = intent.getStringExtra(EXTRA_MESSAGE_KEY).orEmpty()
                if (messageKey.isNotBlank()) {
                    runBlocking {
                        FinanceRepository.getInstance(appContext).dontTrack(messageKey)
                    }
                    Toast.makeText(appContext, "Removed from finance", Toast.LENGTH_SHORT).show()
                }
                dismissSenderNotification(appContext, intent.getStringExtra(EXTRA_SENDER), notifId)
            }
            ACTION_TAG -> {
                val debitId = intent.getLongExtra(EXTRA_DEBIT_ID, -1L)
                val categoryId = intent.getLongExtra(EXTRA_CATEGORY_ID, -1L)
                val sender = intent.getStringExtra(EXTRA_SENDER)
                if (debitId <= 0L || categoryId <= 0L) return
                val pendingResult = goAsync()
                Thread {
                    try {
                        val repo = FinanceRepository.getInstance(appContext)
                        val updated = runBlocking {
                            val fix = if (LocationCapture.hasBackgroundPermission(appContext)) {
                                LocationCapture.currentFix(appContext, timeoutMs = 3_000L)
                            } else null
                            repo.categorizeDebit(debitId, categoryId, fix = fix)
                        }
                        val result = updated?.let { runBlocking { repo.resultFor(it) } }
                        if (updated != null && result?.isFuel == true && notifId != -1) {
                            DebitNotifier.showTripPrompt(
                                appContext, notifId, debitId, updated.amountPaise, updated.litres, sender.orEmpty()
                            )
                        } else {
                            dismissSenderNotification(appContext, sender, notifId)
                        }
                        toast(appContext, "Logged · ${result?.categoryName ?: "tagged"}")
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        pendingResult.finish()
                    }
                }.start()
            }
            ACTION_TRIP_KM -> {
                val debitId = intent.getLongExtra(EXTRA_DEBIT_ID, -1L)
                val km = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(DebitNotifier.KEY_TRIP_KM)
                    ?.toString()?.trim()?.replace(",", "")?.toDoubleOrNull()
                val pendingResult = goAsync()
                Thread {
                    try {
                        val stats = if (debitId > 0L && km != null) {
                            runBlocking { FinanceRepository.getInstance(appContext).setTripKm(debitId, km) }
                        } else null
                        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        if (notifId != -1) nm.cancel(notifId)
                        val message = when {
                            km == null -> "Couldn't read that number"
                            stats == null -> "Saved trip km"
                            stats.lastKmpl != null -> String.format(
                                java.util.Locale.US, "%.1f km/L · avg %.1f", stats.lastKmpl, stats.avgKmpl ?: stats.lastKmpl
                            )
                            else -> "Saved. Mileage shows from the next fill."
                        }
                        toast(appContext, message)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        pendingResult.finish()
                    }
                }.start()
            }
            ACTION_DISMISS -> {
                val sender = intent.getStringExtra(EXTRA_SENDER)
                if (!sender.isNullOrEmpty()) {
                    SmsReceiver.clearSenderMessages(appContext, sender)
                }
            }
        }
    }

    private fun toast(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteSmsFromNotification(context: Context, intent: Intent): Boolean {
        var messageId = intent.getLongExtra(EXTRA_SMS_MESSAGE_ID, -1L)
        if (messageId <= 0L) {
            val uriString = intent.getStringExtra(EXTRA_SMS_URI)
            if (!uriString.isNullOrEmpty()) {
                val uri = Uri.parse(uriString)
                messageId = try {
                    ContentUris.parseId(uri)
                } catch (e: Exception) {
                    uri.lastPathSegment?.toLongOrNull() ?: -1L
                }
            }
        }

        // Soft delete only. Hard-deleting here frees the row's id while the id stays
        // blacklisted for six hours, and the telephony provider hands that same id to
        // the next incoming SMS, which then gets hidden and later destroyed.
        // It also keeps the message restorable from Recently Deleted.
        if (messageId <= 0L) return false
        return DeleteManager(context).softDeleteMessage(context, messageId)
    }

    private fun dismissSenderNotification(context: Context, sender: String?, notifId: Int) {
        if (!sender.isNullOrEmpty()) {
            SmsReceiver.clearSenderMessages(context, sender)
        }
        if (notifId != -1) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(notifId)
        }
    }
}
