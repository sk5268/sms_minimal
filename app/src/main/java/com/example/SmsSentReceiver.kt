package com.example

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.widget.Toast
import androidx.core.app.NotificationCompat

class SmsSentReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SMS_SENT = "com.example.ACTION_SMS_SENT"
        const val EXTRA_MESSAGE_URI = "message_uri"
        const val EXTRA_RECIPIENT = "recipient"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SMS_SENT) return

        val uriString = intent.getStringExtra(EXTRA_MESSAGE_URI) ?: return
        val recipient = intent.getStringExtra(EXTRA_RECIPIENT) ?: "Unknown"
        val uri = Uri.parse(uriString)

        if (resultCode == Activity.RESULT_OK) {
            // Message sent successfully. Move from OUTBOX to SENT.
            val values = ContentValues().apply {
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            }
            try {
                context.contentResolver.update(uri, values, null, null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            // Message failed to send. Move from OUTBOX to FAILED.
            val values = ContentValues().apply {
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED)
            }
            try {
                context.contentResolver.update(uri, values, null, null)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Show a toast and notification to the user
            Toast.makeText(context, "Failed to send SMS to $recipient", Toast.LENGTH_LONG).show()
            showFailureNotification(context, recipient)
        }
    }

    private fun showFailureNotification(context: Context, recipient: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "sms_failure_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Message Failures",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Message Failed")
            .setContentText("Failed to send message to $recipient.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        notificationManager.notify(recipient.hashCode(), builder.build())
    }
}
