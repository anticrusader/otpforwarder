package com.example.otpforwarder

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

class MainActivity : AppCompatActivity() {

    companion object {
        private const val SMS_PERMISSION_REQUEST = 100
        private const val TAG = "OTPForwarder"
        var isAppActive = false
    }

    private lateinit var autoForwardSwitch: Switch
    private lateinit var lastOtpTextView: TextView
    private lateinit var testButton: Button
    private lateinit var debugButton: Button
    private var smsObserver: SmsObserver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.d(TAG, "MainActivity created")

        autoForwardSwitch = findViewById(R.id.autoForwardSwitch)
        lastOtpTextView = findViewById(R.id.lastOtpTextView)
        testButton = findViewById(R.id.testButton)
        debugButton = findViewById(R.id.debugButton)

        checkAllPermissions()

        testButton.setOnClickListener { testMakeForwarding() }

        debugButton.setOnClickListener {
            val intent = Intent(this, SmsTestActivity::class.java)
            startActivity(intent)
        }

        autoForwardSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d(TAG, "Auto-forward switch changed: $isChecked")

            getSharedPreferences("OTPForwarder", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("auto_forward_enabled", isChecked)
                .apply()

            if (isChecked) {
                if (checkAllPermissions()) {
                    if (!isNotificationServiceEnabled()) {
                        Toast.makeText(this, "⚠️ Please enable Notification Access", Toast.LENGTH_LONG).show()
                        openNotificationAccessSettings()
                        autoForwardSwitch.isChecked = false
                        return@setOnCheckedChangeListener
                    }

                    startOTPService()
                    startSmsObserver()
                    Toast.makeText(this, "✅ OTP forwarding enabled", Toast.LENGTH_LONG).show()
                    lastOtpTextView.text = "📱 Monitoring SMS via notifications..."
                } else {
                    autoForwardSwitch.isChecked = false
                }
            } else {
                stopOTPService()
                stopSmsObserver()
                Toast.makeText(this, "❌ OTP forwarding disabled", Toast.LENGTH_SHORT).show()
                lastOtpTextView.text = "🔍 No OTPs forwarded yet"
            }
        }

        val prefs = getSharedPreferences("OTPForwarder", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("auto_forward_enabled", false)
        if (isEnabled) {
            autoForwardSwitch.isChecked = true
        }
    }

    override fun onResume() {
        super.onResume()
        isAppActive = true
        if (autoForwardSwitch.isChecked) {
            checkRecentSms()
        }
    }

    override fun onPause() {
        super.onPause()
        isAppActive = false
    }

    private fun checkAllPermissions(): Boolean {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECEIVE_SMS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_SMS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        Log.d(TAG, "Permissions needed: ${permissions.size}")

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

    private fun startSmsObserver() {
        try {
            smsObserver = SmsObserver(this, Handler(Looper.getMainLooper()))
            contentResolver.registerContentObserver(
                Uri.parse("content://sms/"),
                true,
                smsObserver!!
            )
            Log.d(TAG, "SMS Observer started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting SMS observer", e)
        }
    }

    private fun stopSmsObserver() {
        try {
            smsObserver?.let {
                contentResolver.unregisterContentObserver(it)
                smsObserver = null
                Log.d(TAG, "SMS Observer stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping SMS observer", e)
        }
    }

    private fun checkRecentSms() {
        try {
            val cursor = contentResolver.query(
                Uri.parse("content://sms/inbox"),
                null,
                "date > ?",
                arrayOf((System.currentTimeMillis() - 60000).toString()),
                "date DESC"
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val bodyIndex = it.getColumnIndex("body")
                    val addressIndex = it.getColumnIndex("address")

                    if (bodyIndex >= 0 && addressIndex >= 0) {
                        val body = it.getString(bodyIndex)
                        val address = it.getString(addressIndex)

                        Log.d(TAG, "Recent SMS found: $body")

                        val otp = OTPForwarder.extractOtpFromMessage(body)
                        if (otp != null && OtpCache.isNewOtp(otp, body)) {
                            Log.d(TAG, "OTP found in recent SMS: $otp")
                            OTPForwarder.forwardOtpViaMake(otp, body, address, this)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking recent SMS", e)
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = android.provider.Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        )
        return flat != null && flat.contains(pkgName)
    }

    private fun openNotificationAccessSettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Please enable Notification Access manually in settings", Toast.LENGTH_LONG).show()
            Log.e(TAG, "Failed to open Notification Access settings", e)
        }
    }


    private fun testMakeForwarding() {
        val testOtp = "123456"
        val testMessage = "Test OTP forwarding: Your verification code is $testOtp"
        val testSender = "TEST"

        Toast.makeText(this, "🧪 Sending test OTP to Make.com...", Toast.LENGTH_SHORT).show()
        OTPForwarder.forwardOtpViaMake(testOtp, testMessage, testSender, this)
    }
}
// SMS Observer to monitor SMS database changes (backup method for MIUI)
class SmsObserver(private val context: Context, handler: Handler) : ContentObserver(handler) {
    companion object {
        private const val TAG = "SmsObserver"
        private var lastSmsId = -1L
    }

    override fun onChange(selfChange: Boolean) {
        super.onChange(selfChange)

        try {
            val cursor = context.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                null,
                null,
                null,
                "date DESC LIMIT 1"
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val idIndex = it.getColumnIndex("_id")
                    val bodyIndex = it.getColumnIndex("body")
                    val addressIndex = it.getColumnIndex("address")

                    if (idIndex >= 0 && bodyIndex >= 0 && addressIndex >= 0) {
                        val smsId = it.getLong(idIndex)

                        // Check if this is a new SMS
                        if (smsId != lastSmsId) {
                            lastSmsId = smsId

                            val body = it.getString(bodyIndex)
                            val address = it.getString(addressIndex)

                            Log.d(TAG, "New SMS detected via observer: $body")

                            // Show notification
                            showDebugNotification(context, "SMS Detected", body)

                            val otp = `OTPForwarder`.extractOtpFromMessage(body)
                            if (otp != null && OtpCache.isNewOtp(otp,body)) {
                                Log.d(TAG, "OTP found via observer: $otp")
                                `OTPForwarder`.forwardOtpViaMake(otp, body, address, context)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in SMS observer", e)
        }
    }

    private fun showDebugNotification(context: Context, title: String, content: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "SMS_DEBUG_CHANNEL"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "SMS Debug",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}

// Separate SMS Receiver class
class SMSReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SMSReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive called - Action: ${intent.action}")

        // Show notification immediately
        showDebugNotification(context, "Broadcast Received", "Processing SMS...")

        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION == intent.action) {
            Log.d(TAG, "SMS_RECEIVED_ACTION confirmed")

            val bundle = intent.extras
            if (bundle == null) {
                Log.e(TAG, "Bundle is null")
                return
            }

            val pdus = bundle.get("pdus") as Array<*>?
            if (pdus == null) {
                Log.e(TAG, "PDUs array is null")
                return
            }

            Log.d(TAG, "Number of PDUs: ${pdus.size}")

            pdus.forEach { pdu ->
                try {
                    val format = bundle.getString("format")
                    val smsMessage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && format != null) {
                        SmsMessage.createFromPdu(pdu as ByteArray, format)
                    } else {
                        SmsMessage.createFromPdu(pdu as ByteArray)
                    }

                    val messageBody = smsMessage.messageBody
                    val sender = smsMessage.originatingAddress

                    Log.d(TAG, "SMS from $sender: $messageBody")
                    showDebugNotification(context, "SMS from $sender", messageBody)

                    val otp = `OTPForwarder`.extractOtpFromMessage(messageBody)
                    if (otp != null && OtpCache.isNewOtp(otp, messageBody)) {
                        Log.d(TAG, "OTP detected: $otp")
                        `OTPForwarder`.forwardOtpViaMake(otp, messageBody, sender ?: "Unknown", context)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing SMS", e)
                }
            }
        }
    }

    private fun showDebugNotification(context: Context, title: String, content: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "SMS_DEBUG_CHANNEL"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "SMS Debug",
                    NotificationManager.IMPORTANCE_HIGH
                )
                notificationManager.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing notification", e)
        }
    }
}



// Background service
class OTPService : Service() {

    companion object {
        private const val TAG = "OTPService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "OTP_SERVICE_CHANNEL"
        var isServiceRunning = false
    }

    private var smsReceiver: BroadcastReceiver? = null
    private var smsObserver: SmsObserver? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OTPService onCreate")
        isServiceRunning = true
    }

    private var pollingHandler: Handler? = null
    private var pollingRunnable: Runnable? = null
    private var lastProcessedSmsId: Long = -1

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "OTPService onStartCommand")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createForegroundNotification())

        // Register SMS receiver
        registerSmsReceiver()

        // Also start SMS observer as backup for MIUI
        startSmsObserver()

        // Start polling as last resort for aggressive MIUI
        startSmsPolling()

        return START_STICKY
    }

    private fun startSmsPolling() {
        Log.d(TAG, "Starting SMS polling")

        // Get the last SMS ID to avoid processing old messages
        getLastSmsId()?.let { lastProcessedSmsId = it }

        pollingHandler = Handler(Looper.getMainLooper())
        pollingRunnable = object : Runnable {
            override fun run() {
                checkForNewSms()
                pollingHandler?.postDelayed(this, 2000) // Check every 2 seconds
            }
        }
        pollingHandler?.post(pollingRunnable!!)
    }

    private fun getLastSmsId(): Long? {
        try {
            val cursor = contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id"),
                null,
                null,
                "_id DESC LIMIT 1"
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    return it.getLong(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting last SMS ID", e)
        }
        return null
    }

    private fun checkForNewSms() {
        try {
            val cursor = contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id", "address", "body", "date"),
                "_id > ?",
                arrayOf(lastProcessedSmsId.toString()),
                "_id ASC"
            )

            cursor?.use {
                while (it.moveToNext()) {
                    val smsId = it.getLong(0)
                    val address = it.getString(1)
                    val body = it.getString(2)
                    val date = it.getLong(3)

                    // Check if this SMS is recent (within last 30 seconds)
                    if (System.currentTimeMillis() - date < 30000) {
                        Log.d(TAG, "New SMS detected via polling: $body")

                        // Show debug notification
                        showDebugNotification("SMS Detected (Polling)", body)

                        val otp = `OTPForwarder`.extractOtpFromMessage(body)
                        if (otp != null && OtpCache.isNewOtp(otp,body)) {
                            Log.d(TAG, "OTP found via polling: $otp")
                            `OTPForwarder`.forwardOtpViaMake(otp, body, address, this)
                        }

                        lastProcessedSmsId = smsId
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in SMS polling", e)
        }
    }

    private fun showDebugNotification(title: String, content: String) {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "SMS_DEBUG_CHANNEL"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "SMS Debug",
                    NotificationManager.IMPORTANCE_HIGH
                )
                notificationManager.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing notification", e)
        }
    }

    private fun registerSmsReceiver() {
        try {
            if (smsReceiver != null) {
                Log.d(TAG, "SMS receiver already registered")
                return
            }

            smsReceiver = SMSReceiver()
            val filter = IntentFilter().apply {
                addAction(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
                priority = 2147483647 // Maximum priority
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(smsReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(smsReceiver, filter)
            }

            Log.d(TAG, "SMS receiver registered successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error registering SMS receiver", e)
        }
    }

    private fun startSmsObserver() {
        try {
            smsObserver = SmsObserver(this, Handler(Looper.getMainLooper()))
            contentResolver.registerContentObserver(
                Uri.parse("content://sms/"),
                true,
                smsObserver!!
            )
            Log.d(TAG, "SMS Observer started in service")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting SMS observer in service", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "OTPService onDestroy")

        try {
            smsReceiver?.let {
                unregisterReceiver(it)
                smsReceiver = null
            }

            smsObserver?.let {
                contentResolver.unregisterContentObserver(it)
                smsObserver = null
            }

            // Stop polling
            pollingRunnable?.let {
                pollingHandler?.removeCallbacks(it)
                pollingHandler = null
                pollingRunnable = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error in cleanup", e)
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
            .setContentText("Monitoring SMS messages...")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }
}