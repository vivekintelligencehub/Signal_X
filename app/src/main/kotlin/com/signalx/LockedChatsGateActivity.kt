package com.signalX

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

class LockedChatsGateActivity : AppCompatActivity() {

    private lateinit var etPassword: EditText
    private lateinit var tvError: TextView


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_locked_gate)

        etPassword = findViewById(R.id.etGatePassword)
        tvError = findViewById(R.id.tvGateError)

        PasswordVisibilityHelper.attach(etPassword, findViewById(R.id.ivToggleGatePassword))

        findViewById<TextView>(R.id.btnGateUnlock).setOnClickListener {
            attemptUnlock()
        }

        findViewById<TextView>(R.id.btnGateFingerprint).setOnClickListener {
            tryBiometric()
        }

        tryBiometric()
    }


    override fun onBackPressed() {

        // Gate ke peeche se back dabakar bhaga nahi ja sakta —
        // poore Locked task ko band karke seedha Inbox pe le jao

        finishAffinity()
    }


    private fun tryBiometric() {

        val biometricManager = BiometricManager.from(this)

        val canAuthenticate = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
        )

        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) return

        val executor = ContextCompat.getMainExecutor(this)

        val prompt = BiometricPrompt(
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

        prompt.authenticate(promptInfo)
    }


    private fun attemptUnlock() {

        val entered = etPassword.text.toString()

        val prefs = getSharedPreferences("signalx_prefs", Context.MODE_PRIVATE)
        val savedHash = prefs.getString("locked_chats_pass_hash", null)

        if (savedHash != null && SecurityUtils.hash(entered) == savedHash) {
            unlockSuccess()
        } else {
            tvError.text = "Incorrect password"
            tvError.visibility = View.VISIBLE
        }
    }


    private fun unlockSuccess() {

        LockedZoneGuard.markUnlocked()
        finish()   // gate hat jaata hai — jo bhi Activity uske peeche thi wahi wapas dikhegi
    }
}