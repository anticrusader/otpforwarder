package com.example.otpforwarder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.regex.Pattern

object OTPForwarder {
    private const val MAKE_WEBHOOK_URL = "https://hook.eu2.make.com/bnooc4nm64eu13l89hcq9f25tjvztiam"
    private const val TAG = "OTPForwarder"
    private const val CHANNEL_ID = "OTP_FORWARDING_CHANNEL"

    private val executorService: ExecutorService = Executors.newSingleThreadExecutor()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // Track recently forwarded OTPs to prevent duplicates
    private val recentlyForwardedOtps = mutableMapOf<String, Long>()
    private const val DUPLICATE_PREVENTION_WINDOW = 300000L // 5 minutes

    // Enhanced OTP patterns - ordered by specificity
    private val otpPatterns = arrayOf(
        // Specific OTP patterns
        Pattern.compile("(?:OTP|otp|Code|code|PIN|pin)\\s*(?:is|:)?\\s*(\\d{4,8})\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(\\d{4,8})\\s*(?:is your|is the)\\s*(?:OTP|otp|code|Code|PIN|pin)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:Your|your)\\s*(?:OTP|otp|code|Code|PIN|pin)\\s*(?:is|:)?\\s*(\\d{4,8})\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:verification|verify|authentication)\\s*(?:code|Code)?\\s*(?:is|:)?\\s*(\\d{4,8})\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:use|enter|input)\\s*(\\d{4,8})\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(\\d{4,8})\\s*(?:for|to)\\s*(?:verify|authenticate|confirm)", Pattern.CASE_INSENSITIVE),

        // Common message patterns
        Pattern.compile("(?:code|Code)\\s*:?\\s*(\\d{4,8})\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(\\d{4,8})\\s*is your\\s*\\w+\\s*code", Pattern.CASE_INSENSITIVE),

        // Loose patterns - any 4-8 digit number
        Pattern.compile("\\b(\\d{4,8})\\b")
    )

    fun wasRecentlyForwarded(otpKey: String): Boolean {
        val lastForwarded = recentlyForwardedOtps[otpKey] ?: 0
        val now = System.currentTimeMillis()
        return (now - lastForwarded) < DUPLICATE_PREVENTION_WINDOW
    }

    fun forwardOtpViaMake(otp: String, originalMessage: String, sender: String, context: Context) {
        Log.d(TAG, "Preparing to forward OTP: $otp from $sender")

        // Create a unique key for this OTP
        val otpKey = "OTP_${otp}_${sender.take(20)}"

        // Check for duplicate
        if (wasRecentlyForwarded(otpKey)) {
            Log.d(TAG, "DUPLICATE DETECTED: OTP $otp was already forwarded recently")
            showNotification(context, "⚠️ Duplicate OTP Skipped", "OTP $otp already sent")
            return
        }

        // Mark as forwarded IMMEDIATELY
        recentlyForwardedOtps[otpKey] = System.currentTimeMillis()

        // Clean up old entries (older than 10 minutes)
        val now = System.currentTimeMillis()
        recentlyForwardedOtps.entries.removeIf {
            now - it.value > 600000L // 10 minutes
        }

        Log.d(TAG, "Forwarding NEW OTP: $otp")

        executorService.execute {
            try {
                val currentTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

                val payload = JSONObject().apply {
                    put("otp", otp)
                    put("original_message", originalMessage)
                    put("sender", sender)
                    put("timestamp", currentTime)
                    put("device_model", Build.MODEL)
                    put("device_brand", Build.BRAND)
                    put("android_version", Build.VERSION.RELEASE)
                }

                Log.d(TAG, "Sending payload: $payload")

                val body = RequestBody.create(
                    "application/json; charset=utf-8".toMediaType(),
                    payload.toString()
                )

                val request = Request.Builder()
                    .url(MAKE_WEBHOOK_URL)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("User-Agent", "OTP-Forwarder-Android/1.0")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    Log.d(TAG, "Response code: ${response.code}")

                    val notificationTitle = if (response.isSuccessful) {
                        "✅ OTP Forwarded: $otp"
                    } else {
                        "❌ Forward Failed: $otp (Code: ${response.code})"
                    }

                    showNotification(context, notificationTitle, "From: $sender")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error forwarding OTP", e)
                showNotification(context, "❌ Error forwarding OTP", e.message ?: "Unknown error")
            }
        }
        val prefs = context.getSharedPreferences("OTPForwarder", Context.MODE_PRIVATE)
        prefs.edit().putString("last_sent_otp", otp).apply()

    }

    fun showNotification(context: Context, title: String, content: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OTP Forwarding Results",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    fun extractOtpFromMessage(message: String): String? {
        Log.d(TAG, "Extracting OTP from: '$message'")

        for ((index, pattern) in otpPatterns.withIndex()) {
            val matcher = pattern.matcher(message)
            if (matcher.find()) {
                val otp = matcher.group(1)
                if (otp != null && otp.length >= 5 && otp.length <= 10) {
                    Log.d(TAG, "OTP found with pattern $index: $otp")

                    // For the loosest pattern, check if message likely contains OTP
                    if (index == otpPatterns.size - 1) {
                        if (!isLikelyOtpMessage(message)) {
                            continue
                        }
                    }

                    if (isLikelyOtp(otp, message)) {
                        return otp
                    }
                }
            }
        }
        Log.d(TAG, "No OTP found")
        return null
    }

    private fun isLikelyOtpMessage(message: String): Boolean {
        val lowerMessage = message.lowercase()
        //val otpKeywords = listOf("otp", "code", "pin", "verification", "verify", "authenticate",
           // "confirm", "login", "security", "your", "use", "enter")
        val otpKeywords = listOf("otp", "code", "pin", "verification", "verify", "authenticate")
        return otpKeywords.any { lowerMessage.contains(it) }
    }

    private fun isLikelyOtp(otp: String, message: String): Boolean {
        val lowerMessage = message.lowercase()

        // Skip phone numbers
        if (otp.length == 11) {
            return false
        }

        // Skip financial amounts unless OTP keyword present
        val financeKeywords = listOf("amount", "balance", "credit", "debit", "payment", "rs", "inr", "$")
        val hasFinanceKeyword = financeKeywords.any { lowerMessage.contains(it) }
        val hasOtpKeyword = listOf("otp", "code", "pin", "verification").any { lowerMessage.contains(it) }

        if (hasFinanceKeyword && !hasOtpKeyword) {
            return false
        }

        return true
    }
}
// File: OtpCache.kt
object OtpCache {
    private var lastOtp: String? = null
    private var lastMessageHash: Int = 0
    private var lastTimestamp: Long = 0

    private const val DUPLICATE_TIME_WINDOW_MS = 5000L // 5 seconds

    fun isNewOtp(otp: String, fullMessage: String? = null): Boolean {
        val now = System.currentTimeMillis()
        val hash = fullMessage?.hashCode() ?: otp.hashCode()

        if (otp == lastOtp && hash == lastMessageHash && (now - lastTimestamp) < DUPLICATE_TIME_WINDOW_MS) {
            return false
        }

        lastOtp = otp
        lastMessageHash = hash
        lastTimestamp = now
        return true
    }
}


