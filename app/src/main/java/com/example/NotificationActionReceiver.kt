package com.example

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_COPY_OTP = "com.example.ACTION_COPY_OTP"
        const val ACTION_DELETE_SMS = "com.example.ACTION_DELETE_SMS"
        const val EXTRA_OTP = "com.example.EXTRA_OTP"
        const val EXTRA_SMS_URI = "com.example.EXTRA_SMS_URI"
        const val EXTRA_NOTIF_ID = "com.example.EXTRA_NOTIF_ID"
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
                    // Display micro toast without lagging main ui
                    Toast.makeText(context, Translator.get("otp_copied"), Toast.LENGTH_SHORT).show()
                }
                cancelNotification(context, notifId)
            }
            ACTION_DELETE_SMS -> {
                val uriString = intent.getStringExtra(EXTRA_SMS_URI)
                if (!uriString.isNullOrEmpty()) {
                    try {
                        val uri = Uri.parse(uriString)
                        val deletedRows = context.contentResolver.delete(uri, null, null)
                        if (deletedRows > 0) {
                            Toast.makeText(context, Translator.get("sms_deleted"), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, Translator.get("clear_from_provider"), Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(context, Translator.get("delete_failed"), Toast.LENGTH_SHORT).show()
                    }
                }
                cancelNotification(context, notifId)
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
