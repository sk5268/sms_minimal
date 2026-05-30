package com.example

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.SmsManager
import android.net.Uri
import android.os.Build

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
                    smsManager.sendTextMessage(number, null, message, null, null)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
