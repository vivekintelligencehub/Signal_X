package com.signalX

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

class UnlockLockedChatsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "EXTRA_MODE"
        const val MODE_UNLOCK_FOLDER = "UNLOCK_FOLDER"
        const val MODE_VERIFY_ONLY = "VERIFY_ONLY"
    }

    private var mode = MODE_UNLOCK_FOLDER

    private lateinit var etUnlockPassword: EditText
    private lateinit var tvUnlockError: TextView


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_UNLOCK_FOLDER

        setContentView(R.layout.activity_unlock_locked_chats)

        etUnlockPassword = findViewById(R.id.etUnlockPassword)
        tvUnlockError = findViewById(R.id.tvUnlockError)

        PasswordVisibilityHelper.attach(etUnlockPassword, findViewById(R.id.ivToggleUnlockPassword))

        findViewById<TextView>(R.id.btnBackUnlock).setOnClickListener {
            finish()
        }

        findViewById<TextView>(R.id.btnUnlockWithPassword).setOnClickListener {
            attemptPasswordUnlock()
        }

        findViewById<TextView>(R.id.btnUseFingerprint).setOnClickListener {
            tryBiometricUnlock()
        }

        tryBiometricUnlock()
    }


    private fun tryBiometricUnlock() {

        val biometricManager = BiometricManager.from(this)

        val canAuthenticate = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
        )

        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            return
        }

        val executor = ContextCompat.getMainExecutor(this)

        val biometricPrompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    unlockSuccess()
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Locked Chats")
            .setSubtitle("Use fingerprint or enter password below")
            .setNegativeButtonText("Cancel")
            .build()

        biometricPrompt.authenticate(promptInfo)
    }


    private fun attemptPasswordUnlock() {

        val entered = etUnlockPassword.text.toString()

        val prefs = getSharedPreferences("signalx_prefs", Context.MODE_PRIVATE)
        val savedHash = prefs.getString("locked_chats_pass_hash", null)

        if (savedHash != null && SecurityUtils.hash(entered) == savedHash) {
            unlockSuccess()
        } else {
            tvUnlockError.text = "Incorrect password"
            tvUnlockError.visibility = View.VISIBLE
        }
    }


    private fun unlockSuccess() {
        
        LockedZoneGuard.markUnlocked()

        if (mode == MODE_VERIFY_ONLY) {
            setResult(Activity.RESULT_OK)
            finish()
            return
        }

        val intent = Intent(this, SimpleListActivity::class.java)
        intent.putExtra(SimpleListActivity.EXTRA_TITLE, "Locked Chats")
        intent.putExtra(SimpleListActivity.EXTRA_SECURE, true)
        intent.putExtra(SimpleListActivity.EXTRA_LOCATION, ChatLocation.LOCKED.name)

        startActivity(intent)
        finish()
    }
}