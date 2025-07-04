package com.example.otpforwarder

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {

    companion object {
        private const val SMS_PERMISSION_REQUEST = 100
        private const val TAG = "OTPForwarder"
    }

    private lateinit var autoForwardSwitch: Switch
    private lateinit var lastOtpTextView: TextView
    private lateinit var testButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.d(TAG, "MainActivity created")

        // Initialize views
        autoForwardSwitch = findViewById(R.id.autoForwardSwitch)
        lastOtpTextView = findViewById(R.id.lastOtpTextView)
        testButton = findViewById(R.id.testButton)

        // Check and request permissions
        checkAllPermissions()

        // Set up test button
        testButton.setOnClickListener { testMakeForwarding() }

        // Set up auto-forward switch
        autoForwardSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d(TAG, "Auto-forward switch changed: $isChecked")
            if (isChecked) {
                if (checkAllPermissions()) {
                    startOTPService()
                    Toast.makeText(this, "✅ OTP forwarding enabled (background service)", Toast.LENGTH_LONG).show()
                    lastOtpTextView.text = "📱 Background service listening for SMS..."
                } else {
                    autoForwardSwitch.isChecked = false
                }
            } else {
                stopOTPService()
                Toast.makeText(this, "❌ OTP forwarding disabled", Toast.LENGTH_SHORT).show()
                lastOtpTextView.text = "🔍 No OTPs forwarded yet"
            }
        }

        // Check if service is already running
        if (OTPService.isServiceRunning) {
            autoForwardSwitch.isChecked = true
            lastOtpTextView.text = "📱 Background service listening for SMS..."
        }
    }

    private fun checkAllPermissions(): Boolean {
        val permissions = mutableListOf<String>()

        // SMS permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECEIVE_SMS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_SMS)
        }

        // Notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        return if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), SMS_PERMISSION_REQUEST)
            false
        } else {
            true
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == SMS_PERMISSION_REQUEST) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                Toast.makeText(this, "✅ All permissions granted", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "All permissions granted")
            } else {
                Toast.makeText(this, "❌ Permissions required for OTP forwarding", Toast.LENGTH_LONG).show()
                Log.w(TAG, "Some permissions denied")
                autoForwardSwitch.isChecked = false
            }
        }
    }

    private fun startOTPService() {
        val serviceIntent = Intent(this, OTPService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Log.d(TAG, "OTP service started")
    }

    private fun stopOTPService() {
        val serviceIntent = Intent(this, OTPService::class.java)
        stopService(serviceIntent)
        Log.d(TAG, "OTP service stopped")
    }

    private fun testMakeForwarding() {
        val testOtp = "123456"
        val testMessage = "Test OTP forwarding: Your verification code is $testOtp"
        val testSender = "TEST"

        Toast.makeText(this, "🧪 Sending test OTP to Make.com...", Toast.LENGTH_SHORT).show()
        OTPForwarder.forwardOtpViaMake(testOtp, testMessage, testSender, this)
    }
}

// Separate SMS Receiver class
class SMSReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SMSReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "SMS received")

        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION == intent.action) {
            val bundle = intent.extras
            bundle?.let {
                val pdus = it.get("pdus") as Array<*>?
                pdus?.forEach { pdu ->
                    try {
                        val smsMessage = SmsMessage.createFromPdu(pdu as ByteArray)
                        val messageBody = smsMessage.messageBody
                        val sender = smsMessage.originatingAddress

                        Log.d(TAG, "SMS from $sender: $messageBody")

                        val otp = OTPForwarder.extractOtpFromMessage(messageBody)
                        if (otp != null) {
                            Log.d(TAG, "OTP detected: $otp")
                            OTPForwarder.forwardOtpViaMake(otp, messageBody, sender ?: "Unknown", context)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing SMS", e)
                    }
                }
            }
        }
    }
}

// OTP Forwarding logic separated into object
object OTPForwarder {
    private const val MAKE_WEBHOOK_URL = "https://hook.eu2.make.com/bnooc4nm64eu13l89hcq9f25tjvztiam"
    private const val TAG = "OTPForwarder"
    private const val CHANNEL_ID = "OTP_FORWARDING_CHANNEL"

    private val executorService: ExecutorService = Executors.newSingleThreadExecutor()
    private val httpClient = OkHttpClient()

    // Enhanced OTP patterns
    private val otpPatterns = arrayOf(
        Pattern.compile("\\b(\\d{4,8})\\b.*(?:OTP|otp|code|Code|PIN|pin|verification|verify)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:OTP|otp|code|Code|PIN|pin|verification|verify).*\\b(\\d{4,8})\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:use|enter).*\\b(\\d{4,8})\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("your.*\\b(\\d{4,8})\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(\\d{4,8})\\b.*(?:expire|valid|minutes)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(\\d{4,8})\\b", Pattern.CASE_INSENSITIVE)
    )

    fun forwardOtpViaMake(otp: String, originalMessage: String, sender: String, context: Context) {
        Log.d(TAG, "Forwarding OTP: $otp from $sender")

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
                        "❌ Forward Failed: $otp"
                    }

                    showNotification(context, notificationTitle, "From: $sender")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error forwarding OTP", e)
                showNotification(context, "❌ Error forwarding OTP", e.message ?: "Unknown error")
            }
        }
    }

    private fun showNotification(context: Context, title: String, content: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OTP Forwarding",
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
                if (otp != null && otp.length >= 4 && otp.length <= 8) {
                    Log.d(TAG, "OTP found with pattern $index: $otp")
                    if (isLikelyOtp(otp, message)) {
                        return otp
                    }
                }
            }
        }
        Log.d(TAG, "No OTP found")
        return null
    }

    private fun isLikelyOtp(otp: String, message: String): Boolean {
        val lowerMessage = message.lowercase()

        // Skip phone numbers
        if (otp.length == 10 && (otp.startsWith("0") || otp.startsWith("1"))) {
            return false
        }

        // Skip financial amounts
        val nonOtpKeywords = listOf("amount", "balance", "credit", "debit", "payment")
        if (nonOtpKeywords.any { lowerMessage.contains(it) }) {
            return false
        }

        // Accept any message with these keywords
        val otpKeywords = listOf("otp", "code", "pin", "verification", "verify", "authenticate", "login", "confirm", "security", "your")
        return otpKeywords.any { lowerMessage.contains(it) }
    }
}

// Background service that runs continuously
class OTPService : Service() {

    companion object {
        private const val TAG = "OTPService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "OTP_SERVICE_CHANNEL"
        var isServiceRunning = false
    }

    private var smsReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OTPService created")
        isServiceRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "OTPService started")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createForegroundNotification())

        // Register SMS receiver dynamically
        try {
            smsReceiver = SMSReceiver()
            val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
            filter.priority = 999
            registerReceiver(smsReceiver, filter)
            Log.d(TAG, "SMS receiver registered in service")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering SMS receiver", e)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "OTPService destroyed")

        try {
            smsReceiver?.let {
                unregisterReceiver(it)
                Log.d(TAG, "SMS receiver unregistered")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering receiver", e)
        }

        isServiceRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OTP Forwarding Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background service for OTP forwarding"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📱 OTP Forwarder Active")
            .setContentText("Listening for SMS messages...")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }
}