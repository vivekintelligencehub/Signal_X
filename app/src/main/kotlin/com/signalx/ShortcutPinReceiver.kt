package com.signalX

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class ShortcutPinReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {

        // Yeh sirf tabhi call hota hai jab launcher ne shortcut GENUINELY pin kar diya ho

        Toast.makeText(context, "Shortcut added to home screen", Toast.LENGTH_SHORT).show()
    }
}