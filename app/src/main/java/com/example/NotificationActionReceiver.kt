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
import com.example.finance.FinanceRepository
import kotlinx.coroutines.runBlocking

class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_COPY_OTP = "com.example.ACTION_COPY_OTP"
        const val ACTION_DELETE_SMS = "com.example.ACTION_DELETE_SMS"
        const val ACTION_DISMISS = "com.example.ACTION_DISMISS"
        const val ACTION_CATEGORIZE = "com.example.ACTION_CATEGORIZE"
        const val ACTION_DONT_TRACK = "com.example.ACTION_DONT_TRACK"
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
            ACTION_DISMISS -> {
                val sender = intent.getStringExtra(EXTRA_SENDER)
                if (!sender.isNullOrEmpty()) {
                    SmsReceiver.clearSenderMessages(appContext, sender)
                }
            }
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
