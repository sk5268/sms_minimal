package com.example

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)

        when (action) {
            ACTION_COPY_OTP -> {
                val otp = intent.getStringExtra(EXTRA_OTP)
                if (!otp.isNullOrEmpty()) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("OTP", otp)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "OTP Copied", Toast.LENGTH_SHORT).show()
                }
                cancelNotification(context, notifId)
            }
            ACTION_DELETE_SMS -> {
                val uriString = intent.getStringExtra(EXTRA_SMS_URI)
                if (!uriString.isNullOrEmpty()) {
                    try {
                        val uri = Uri.parse(uriString)
                        val messageId = try {
                            android.content.ContentUris.parseId(uri)
                        } catch (e: Exception) {
                            uri.lastPathSegment?.toLongOrNull()
                        }
                        if (messageId != null) {
                            val deleteManager = DeleteManager(context)
                            deleteManager.softDeleteMessage(messageId)
                            Toast.makeText(context, "SMS Deleted", Toast.LENGTH_SHORT).show()
                        } else {
                            val deletedRows = context.contentResolver.delete(uri, null, null)
                            if (deletedRows > 0) {
                                Toast.makeText(context, "SMS Deleted", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Cleared from database", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(context, "Delete failed", Toast.LENGTH_SHORT).show()
                    }
                }
                cancelNotification(context, notifId)
            }
            ACTION_CATEGORIZE -> {
                val debitId = intent.getLongExtra(EXTRA_DEBIT_ID, -1L)
                val amountPaise = intent.getLongExtra(EXTRA_AMOUNT_PAISE, 0L)
                val sender = intent.getStringExtra(EXTRA_SENDER).orEmpty()
                val snippet = intent.getStringExtra(EXTRA_SNIPPET).orEmpty()
                if (debitId > 0L) {
                    CategorizeOverlayService.start(
                        context = context,
                        debitId = debitId,
                        amountPaise = amountPaise,
                        sender = sender,
                        snippet = snippet,
                        notifId = notifId
                    )
                }
            }
            ACTION_DONT_TRACK -> {
                val smsMessageId = intent.getLongExtra(EXTRA_SMS_MESSAGE_ID, -1L)
                if (smsMessageId > 0L) {
                    runBlocking {
                        FinanceRepository.getInstance(context).dontTrack(smsMessageId)
                    }
                    Toast.makeText(context, "Removed from finance", Toast.LENGTH_SHORT).show()
                }
                cancelNotification(context, notifId)
            }
            ACTION_DISMISS -> {
                val sender = intent.getStringExtra(EXTRA_SENDER)
                if (!sender.isNullOrEmpty()) {
                    SmsReceiver.clearSenderMessages(context, sender)
                }
            }
        }
    }

    private fun cancelNotification(context: Context, notifId: Int) {
        if (notifId != -1) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(notifId)
        }
    }
}
