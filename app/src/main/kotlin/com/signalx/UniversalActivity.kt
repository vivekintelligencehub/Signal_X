package com.signalX

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class UniversalActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_universal)

        findViewById<TextView>(R.id.btnBackUniversal).setOnClickListener {
            finish()
        }
    }
}