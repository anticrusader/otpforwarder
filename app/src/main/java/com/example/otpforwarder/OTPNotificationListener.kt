package com.example.otpforwarder

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.content.SharedPreferences

class `OTPNotificationListener` : NotificationListenerService() {

    companion object {
        private const val TAG = "OTPNotificationListener"
        private const val PREFS_NAME = "OTPForwarderPrefs"
        private const val LAST_PROCESSED_KEY = "last_processed_notifications"
        private val SMS_PACKAGES = listOf(
            "com.google.android.apps.messaging",    // Google Messages
            "com.android.mms",                      // Default SMS
            "com.samsung.android.messaging",        // Samsung Messages
            "com.xiaomi.mms.cts",                  // MIUI SMS
            "com.android.messaging"                 // AOSP Messages
        )
    }

    private lateinit var sharedPrefs: SharedPreferences
    private val processedNotifications = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        sharedPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        // Load previously processed notifications
        loadProcessedNotifications()
    }

    private fun loadProcessedNotifications() {
        val saved = sharedPrefs.getStringSet(LAST_PROCESSED_KEY, emptySet()) ?: emptySet()
        processedNotifications.addAll(saved)
        // Keep only recent entries (last 50)
        if (processedNotifications.size > 50) {
            val toKeep = processedNotifications.toList().takeLast(50).toSet()
            processedNotifications.clear()
            processedNotifications.addAll(toKeep)
            saveProcessedNotifications()
        }
    }

    private fun saveProcessedNotifications() {
        sharedPrefs.edit().putStringSet(LAST_PROCESSED_KEY, processedNotifications).apply()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val notification = sbn.notification
            val extras = notification.extras

            // Extract notification content
            val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
            val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
            val bigText = extras.getString(Notification.EXTRA_BIG_TEXT) ?: text

            // Create a content-based key (not time-based)
            val contentKey = "${sbn.packageName}_${title}_${text.take(50)}"

            // Skip if already processed
            if (contentKey in processedNotifications) {
                Log.d(TAG, "Skipping already processed notification with same content")
                return
            }

            Log.d(TAG, "New notification from: ${sbn.packageName}")
            Log.d(TAG, "Title: $title")
            Log.d(TAG, "Text: $text")

            // Check if this notification is too old (more than 1 minute)
            val notificationTime = sbn.postTime
            val currentTime = System.currentTimeMillis()
            if (currentTime - notificationTime > 60000) { // 1 minute
                Log.d(TAG, "Notification is too old, skipping")
                return
            }

            // Combine all text fields
            val fullMessage = "$title $text $bigText".trim()

            // Skip if message is too short
            if (fullMessage.length < 10) {
                return
            }

            // Check if message contains a potential OTP
            val hasOtpPattern = fullMessage.contains(Regex("\\b\\d{4,8}\\b"))
            Log.d("pattern",fullMessage)
            if (!hasOtpPattern) {
                return
            }

            // Check if it's from SMS app or looks like SMS
            val packageLower = sbn.packageName.lowercase()
            val isSmsApp = packageLower.contains("sms") ||
                    packageLower.contains("message") ||
                    packageLower.contains("mms") ||
                    packageLower.contains("msg") ||
                    sbn.packageName in SMS_PACKAGES

            val looksLikeSms = looksLikeSmsNotification(fullMessage, title)

            if (!isSmsApp && !looksLikeSms) {
                return
            }

            // Try to extract OTP
            val otp = `OTPForwarder`.extractOtpFromMessage(fullMessage)
            if (otp != null && OtpCache.isNewOtp(otp)) {
                Log.d(TAG, "OTP found: $otp - Forwarding ONCE")

                processedNotifications.add(contentKey)
                saveProcessedNotifications()

                // Show debug notification
                `OTPForwarder`.showNotification(
                    this,
                    "OTP Detected: $otp",
                    "From: ${title.ifEmpty { sbn.packageName }}"
                )

                // Forward the OTP
                `OTPForwarder`.forwardOtpViaMake(
                    otp,
                    fullMessage,
                    title.ifEmpty { sbn.packageName },
                    this
                )
            } else {
                Log.d(TAG, "Duplicate OTP skipped: $otp")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification", e)
        }
    }

    private fun looksLikeSmsNotification(message: String, title: String): Boolean {
        val lowerMessage = message.lowercase()
        val lowerTitle = title.lowercase()

        val otpKeywords = listOf(
            "otp", "code", "pin", "verification", "verify",
            "authenticate", "confirm", "password", "passcode",
            "one-time", "one time", "2fa", "auth", "login",
            "security", "token", "validation", "access"
        )

        val smsKeywords = listOf(
            "message", "sms", "text", "msg", "notification",
            "+92", "pk", "from", "sent", "received"
        )

        val hasOtpNumber = message.contains(Regex("\\b\\d{4,8}\\b"))
        val hasOtpKeyword = otpKeywords.any { lowerMessage.contains(it) }
        val hasSmsKeyword = smsKeywords.any { lowerMessage.contains(it) || lowerTitle.contains(it) }

        return (hasOtpKeyword && hasOtpNumber) ||
                (hasSmsKeyword && hasOtpNumber) ||
                (hasOtpNumber && message.length < 200)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Not needed
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification listener connected")
        loadProcessedNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "Notification listener disconnected")
        // Save state before disconnecting
        saveProcessedNotifications()
    }
}