package com.signalX

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        setContentView(R.layout.activity_setting)

        findViewById<TextView>(R.id.btnBackSettings).setOnClickListener {
            finish()
        }
    }
}