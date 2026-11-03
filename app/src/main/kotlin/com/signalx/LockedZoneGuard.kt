package com.signalX

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

object LockedZoneGuard {

    private var isUnlocked = false
    private var initialized = false


    fun markUnlocked() {
        isUnlocked = true
    }


    fun init() {

        if (initialized) return
        initialized = true

        // Yeh sirf tabhi trigger hota hai jab POORA APP background mein jaata hai
        // (phone band hona, home button, app-switch) — Activity-se-Activity jaane par nahi

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {

            override fun onStop(owner: LifecycleOwner) {
                isUnlocked = false
            }
        })
    }


    // Locked-zone ki Activity apne onResume() mein isko call karegi.
    // True aaye to Activity apna normal kaam kare, false aaye to kuch mat karo —
    // isi function ne password-gate launch kar diya hai.

    fun checkAccessOnResume(activity: Activity): Boolean {

        if (isUnlocked) {
            return true
        }

        val intent = Intent(activity, LockedChatsGateActivity::class.java)
        activity.startActivity(intent)

        return false
    }
}