package com.example.otpforwarder

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class OTPNotificationListener : NotificationListenerService() {
    
    companion object {
        private const val TAG = "OTPNotificationListener"
        private val SMS_PACKAGES = listOf(
            "com.google.android.apps.messaging",    // Google Messages
            "com.android.mms",                      // Default SMS
            "com.samsung.android.messaging",        // Samsung Messages
            "com.xiaomi.mms.cts",                  // MIUI SMS
            "com.android.messaging"                 // AOSP Messages
        )
    }
    
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Check if it's from an SMS app
        if (sbn.packageName !in SMS_PACKAGES) {
            return
        }
        
        Log.d(TAG, "Notification from SMS app: ${sbn.packageName}")
        
        try {
            val notification = sbn.notification
            val extras = notification.extras
            
            // Extract notification content
            val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
            val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
            val bigText = extras.getString(Notification.EXTRA_BIG_TEXT) ?: text
            
            Log.d(TAG, "Title: $title")
            Log.d(TAG, "Text: $text")
            Log.d(TAG, "BigText: $bigText")
            
            // Try to extract OTP from the notification text
            val messageToCheck = if (bigText.isNotEmpty()) bigText else text
            
            val otp = OTPForwarder.extractOtpFromMessage(messageToCheck)
            if (otp != null) {
                Log.d(TAG, "OTP found in notification: $otp")
                
                // Show debug notification
                OTPForwarder.showNotification(
                    this,
                    "OTP Detected from Notification",
                    "OTP: $otp from $title"
                )
                
                // Forward the OTP
                OTPForwarder.forwardOtpViaMake(
                    otp,
                    messageToCheck,
                    title,
                    this
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification", e)
        }
    }
    
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Not needed for our use case
    }
}