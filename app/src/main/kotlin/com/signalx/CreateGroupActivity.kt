package com.signalX

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class CreateGroupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_create_group)

        findViewById<TextView>(R.id.btnBackCreateGroup).setOnClickListener {
            finish()
        }
    }
}