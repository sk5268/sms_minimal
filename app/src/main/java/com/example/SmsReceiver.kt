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
        
        fun getNextNotificationId(context: Context): Int {
            val prefs = context.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE)
            val currentId = prefs.getInt("counter", 1000)
            val nextId = if (currentId >= 999999) 1000 else currentId + 3
            prefs.edit().putInt("counter", nextId).apply()
            return currentId
        }
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

        // 2. OTP extraction
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
        // Conservative keywords — only flag when SMS is clearly an OTP.
        // False negatives (missed OTP) are fine — message just won't auto-delete.
        val keywords = listOf("otp", "time password", "time pin", "code")
        var hasKeyword = keywords.any { lowercaseBody.contains(it) }
        
        // Refinement: If it only matched "code", filter out common false positives like "zip code", "promo code"
        if (hasKeyword && !lowercaseBody.contains("otp") && !lowercaseBody.contains("time password") && !lowercaseBody.contains("time pin")) {
            val falsePositives = listOf("zip code", "promo code", "postal code", "error code", "discount code", "coupon code", "bar code", "qr code", "booking code", "pin code")
            if (falsePositives.any { lowercaseBody.contains(it) }) {
                hasKeyword = false
            }
        }
        if (!hasKeyword) return null

        // Match numeric sequences of 4 to 8 digits
        val digitPattern = Regex("\\b\\d{4,8}\\b")
        val matches = digitPattern.findAll(body).map { it.value }.toList()
        if (matches.isNotEmpty()) {
            // Find most likely candidate: prefers 6 digit OTP (universal standard)
            return matches.find { it.length == 6 } ?: matches.first()
        }

        // Alphanumeric fallback patterns like "code is XJ432" or "code: Y7U9"
        val alphaPattern = Regex("(?i)\\b(?:code|otp)\\s*[:= ]\\s*([a-zA-Z0-9]{4,8})\\b")
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

        val notifId = getNextNotificationId(context)

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
                R.drawable.ic_content_copy,
                otp,
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
                Translator.get("delete_message_action"),
                deletePendingIntent
            )
        }

        notificationManager.notify(notifId, builder.build())
    }
}
