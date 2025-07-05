package com.example.otpforwarder

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class SmsTestActivity : AppCompatActivity() {
    
    private lateinit var resultTextView: TextView
    private lateinit var testSmsAccessButton: Button
    private lateinit var testLastSmsButton: Button
    
    companion object {
        private const val TAG = "SmsTestActivity"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sms_test)
        
        resultTextView = findViewById(R.id.resultTextView)
        testSmsAccessButton = findViewById(R.id.testSmsAccessButton)
        testLastSmsButton = findViewById(R.id.testLastSmsButton)
        
        testSmsAccessButton.setOnClickListener {
            testSmsAccess()
        }
        
        testLastSmsButton.setOnClickListener {
            testLastSms()
        }
        
        // Check permissions on start
        checkPermissions()
    }
    
    private fun checkPermissions() {
        val readSms = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
        val receiveSms = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
        
        val status = StringBuilder()
        status.append("Permissions Status:\n")
        status.append("READ_SMS: ${if (readSms == PackageManager.PERMISSION_GRANTED) "✅ GRANTED" else "❌ DENIED"}\n")
        status.append("RECEIVE_SMS: ${if (receiveSms == PackageManager.PERMISSION_GRANTED) "✅ GRANTED" else "❌ DENIED"}\n\n")
        
        resultTextView.text = status.toString()
    }
    
    private fun testSmsAccess() {
        val result = StringBuilder()
        result.append("Testing SMS Access...\n\n")
        
        try {
            // Test different URI variations
            val uris = listOf(
                "content://sms/inbox",
                "content://sms",
                "content://sms/sent",
                "content://sms/draft"
            )
            
            for (uriString in uris) {
                result.append("Testing URI: $uriString\n")
                try {
                    val cursor = contentResolver.query(
                        Uri.parse(uriString),
                        null,
                        null,
                        null,
                        "_id DESC LIMIT 1"
                    )
                    
                    cursor?.use {
                        result.append("✅ Access SUCCESS - Count: ${it.count}\n")
                        
                        // List available columns
                        if (it.columnNames.isNotEmpty()) {
                            result.append("Columns: ${it.columnNames.joinToString(", ")}\n")
                        }
                    } ?: result.append("❌ Cursor is NULL\n")
                    
                } catch (e: Exception) {
                    result.append("❌ Error: ${e.message}\n")
                    Log.e(TAG, "Error accessing $uriString", e)
                }
                result.append("\n")
            }
            
        } catch (e: Exception) {
            result.append("❌ General Error: ${e.message}\n")
            Log.e(TAG, "General error in testSmsAccess", e)
        }
        
        resultTextView.text = result.toString()
    }
    
    private fun testLastSms() {
        val result = StringBuilder()
        result.append("Fetching Last SMS...\n\n")
        
        try {
            val cursor = contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id", "address", "body", "date", "read", "type"),
                null,
                null,
                "date DESC LIMIT 5"
            )
            
            cursor?.use {
                result.append("Found ${it.count} messages\n\n")
                
                var count = 0
                while (it.moveToNext() && count < 5) {
                    count++
                    val id = it.getLong(0)
                    val address = it.getString(1) ?: "Unknown"
                    val body = it.getString(2) ?: "Empty"
                    val date = it.getLong(3)
                    
                    result.append("Message #$count:\n")
                    result.append("From: $address\n")
                    result.append("Body: $body\n")
                    result.append("Date: ${java.util.Date(date)}\n")
                    
                    // Check if it contains OTP
                    val otp = `OTPForwarder`.extractOtpFromMessage(body)
                    if (otp != null) {
                        result.append("🔑 OTP FOUND: $otp\n")
                    }
                    result.append("\n")
                }
                
                if (count == 0) {
                    result.append("❌ No messages found in inbox\n")
                }
                
            } ?: result.append("❌ Failed to query SMS inbox - cursor is null\n")
            
        } catch (e: SecurityException) {
            result.append("❌ Security Exception: ${e.message}\n")
            result.append("SMS permission might be revoked by system\n")
            Log.e(TAG, "SecurityException in testLastSms", e)
        } catch (e: Exception) {
            result.append("❌ Error: ${e.message}\n")
            result.append("${e.javaClass.simpleName}\n")
            Log.e(TAG, "Error in testLastSms", e)
        }
        
        resultTextView.text = result.toString()
    }
}