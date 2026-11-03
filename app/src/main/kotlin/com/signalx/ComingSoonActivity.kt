package com.signalX

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ComingSoonActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SECURE = "EXTRA_SECURE"
        const val EXTRA_TITLE = "EXTRA_TITLE"
    }

    private var isSecure = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        isSecure = intent.getBooleanExtra(EXTRA_SECURE, false)

        if (isSecure) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        setContentView(R.layout.activity_coming_soon)

        val sourceTitle = intent.getStringExtra(EXTRA_TITLE) ?: "Chats"

        findViewById<TextView>(R.id.tvComingSoonTitle).text = "$sourceTitle Options"

        findViewById<TextView>(R.id.btnBackComingSoon).setOnClickListener {
            finish()
        }
    }


    override fun onResume() {
        super.onResume()

        if (isSecure) {
            LockedZoneGuard.checkAccessOnResume(this)
        }
    }
}