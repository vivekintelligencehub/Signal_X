package com.signalX

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class CreatePasswordActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_create_password)

        val etNewPassword = findViewById<EditText>(R.id.etNewPassword)
        val etConfirmPassword = findViewById<EditText>(R.id.etConfirmPassword)
        val tvError = findViewById<TextView>(R.id.tvPasswordError)

        PasswordVisibilityHelper.attach(etNewPassword, findViewById(R.id.ivToggleNewPassword))
        PasswordVisibilityHelper.attach(etConfirmPassword, findViewById(R.id.ivToggleConfirmPassword))

        findViewById<TextView>(R.id.btnBackCreatePassword).setOnClickListener {
            finish()
        }

        findViewById<TextView>(R.id.btnSetPassword).setOnClickListener {

            val password = etNewPassword.text.toString()
            val confirm = etConfirmPassword.text.toString()

            when {

                PasswordRulesHelper.containsReservedChars(password) -> {
                    tvError.text = "Password cannot contain ',' or '/'"
                    tvError.visibility = View.VISIBLE
                }

                password.length < 4 -> {
                    tvError.text = "Password must be at least 4 characters"
                    tvError.visibility = View.VISIBLE
                }

                password != confirm -> {
                    tvError.text = "Passwords do not match"
                    tvError.visibility = View.VISIBLE
                }

                else -> {

                    tvError.visibility = View.GONE

                    val prefs = getSharedPreferences("signalx_prefs", Context.MODE_PRIVATE)

                    prefs.edit()
                        .putString("locked_chats_pass_hash", SecurityUtils.hash(password))
                        .apply()


                    val intent = Intent(this, SimpleListActivity::class.java)
                    intent.putExtra(SimpleListActivity.EXTRA_TITLE, "Locked Chats")
                    intent.putExtra(SimpleListActivity.EXTRA_SECURE, true)
                    intent.putExtra(SimpleListActivity.EXTRA_LOCATION, ChatLocation.LOCKED.name)

                    startActivity(intent)

                    finish()
                }
            }
        }
    }
}