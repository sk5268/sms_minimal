package com.example

import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsManager

class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == android.telephony.TelephonyManager.ACTION_RESPOND_VIA_MESSAGE) {
            val message = intent.getStringExtra(Intent.EXTRA_TEXT)
            val uriString = intent.dataString
            
            if (message != null && uriString != null) {
                val uri = Uri.parse(uriString)
                val number = uri.schemeSpecificPart
                
                try {
                    val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        getSystemService(SmsManager::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        SmsManager.getDefault()
                    }
                    
                    val parts = smsManager.divideMessage(message)
                    if (parts.size == 1) {
                        smsManager.sendTextMessage(number, null, message, null, null)
                    } else {
                        smsManager.sendMultipartTextMessage(number, null, parts, null, null)
                    }

                    // Write to Sent content provider
                    val values = ContentValues().apply {
                        put(Telephony.Sms.ADDRESS, number)
                        put(Telephony.Sms.BODY, message)
                        put(Telephony.Sms.DATE, System.currentTimeMillis())
                        put(Telephony.Sms.READ, 1)
                        put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                    }
                    contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
