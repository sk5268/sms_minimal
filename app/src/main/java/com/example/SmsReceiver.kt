package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.app.NotificationCompat

class SmsReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "sms_receiver_channel"
        private var notificationIdCounter = 1000
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val sender = messages[0].displayOriginatingAddress ?: messages[0].originatingAddress ?: "Unknown"
        val body = messages.joinToString("") { it.displayMessageBody ?: "" }
        val timestamp = messages[0].timestampMillis

        // 1. Write SMS to Telephony ContentProvider inbox (Default SMS app responsibility)
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, sender)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, timestamp)
            put(Telephony.Sms.READ, 0) // Mark as unread
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
        }

        var insertedUri: Uri? = null
        try {
            insertedUri = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val smsUriString = insertedUri?.toString() ?: ""

        // 2. Intelligent OTP extraction
        val otp = extractOTP(body)

        val messageId = insertedUri?.let { uri ->
            try {
                android.content.ContentUris.parseId(uri)
            } catch (e: Exception) {
                uri.lastPathSegment?.toLongOrNull()
            }
        }

        if (messageId != null && otp != null) {
            scheduleOtpAutoDelete(context, messageId)
        }

        // 3. Query contact display name or fallback to number
        val displayName = getContactName(context, sender) ?: sender

        // 4. Trigger system notification with action buttons
        showSmsNotification(context, sender, displayName, body, otp, smsUriString)
    }

    private fun scheduleOtpAutoDelete(context: Context, messageId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager ?: return
        val intent = Intent(context, OtpDeleteReceiver::class.java).apply {
            putExtra("message_id", messageId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            messageId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerTime = System.currentTimeMillis() + 30 * 60 * 1000L
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    android.app.AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun extractOTP(body: String): String? {
        val lowercaseBody = body.lowercase()
        // Broad list of common dynamic codes and secure keywords in 2026
        val keywords = listOf("otp", "code", "pin", "verification", "passcode", "one-time", "secure", "confirm", "login", "vcode", "2fa")
        val hasKeyword = keywords.any { lowercaseBody.contains(it) }
        if (!hasKeyword) return null

        // Match numeric Sequences of 4 to 8 digits
        val digitPattern = Regex("\\b\\d{4,8}\\b")
        val matches = digitPattern.findAll(body).map { it.value }.toList()
        if (matches.isNotEmpty()) {
            // Find most likely candidate: prefers 6 digit OTP (universal standard)
            return matches.find { it.length == 6 } ?: matches.first()
        }

        // Alphanumeric fallback patterns like "code is XJ432" or "code: Y7U9"
        val alphaPattern = Regex("(?i)\\b(?:code|otp|pin|is)\\s*[:= ]\\s*([a-zA-Z0-9]{4,8})\\b")
        val alphaMatch = alphaPattern.find(body)
        if (alphaMatch != null) {
            val candidate = alphaMatch.groupValues[1]
            val containsDigit = candidate.any { it.isDigit() }
            val allCaps = candidate.all { it.isUpperCase() || it.isDigit() }
            if (containsDigit || (allCaps && candidate.length >= 4)) {
                return candidate
            }
        }
        return null
    }

    private fun getContactName(context: Context, phoneNumber: String): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return null
        }

        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (index != -1) {
                        return cursor.getString(index)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun showSmsNotification(
        context: Context,
        sender: String,
        displayName: String,
        body: String,
        otp: String?,
        smsUriString: String
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Ensure channel exits
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "New Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming SMS text messages alerts"
                enableLights(true)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notifId = notificationIdCounter++

        // Create intent to open application main layout
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("sender_number", sender)
        }
        val mainPendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val mainPendingIntent = PendingIntent.getActivity(context, notifId, mainIntent, mainPendingFlags)

        val truncatedBody = if (body.length > 300) body.take(300) else body

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(displayName)
            .setContentText(truncatedBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(truncatedBody))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(mainPendingIntent)
            .setAutoCancel(true)
            .setAllowSystemGeneratedContextualActions(false)

        val actionFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        // 1. Copy OTP Action (if parsed successfully)
        if (!otp.isNullOrEmpty()) {
            val copyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_COPY_OTP
                putExtra(NotificationActionReceiver.EXTRA_OTP, otp)
                putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, notifId)
            }
            val copyPendingIntent = PendingIntent.getBroadcast(context, notifId + 1, copyIntent, actionFlags)
            builder.addAction(
                0, // 0 means no custom icon to bypass double/overlapping icons on modern OS versions
                "COPY CODE: $otp",
                copyPendingIntent
            )
        }

        // 2. Delete SMS Action
        if (smsUriString.isNotEmpty()) {
            val deleteIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_DELETE_SMS
                putExtra(NotificationActionReceiver.EXTRA_SMS_URI, smsUriString)
                putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, notifId)
            }
            val deletePendingIntent = PendingIntent.getBroadcast(context, notifId + 2, deleteIntent, actionFlags)
            builder.addAction(
                0, // 0 handles platform-native minimalist styling
                "DELETE MESSAGE",
                deletePendingIntent
            )
        }

        notificationManager.notify(notifId, builder.build())
    }
}
