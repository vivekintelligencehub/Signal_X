package com.signalX

import android.app.Application
import com.vanniktech.emoji.EmojiManager
import com.vanniktech.emoji.ios.IosEmojiProvider

class SignalXApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        EmojiManager.install(IosEmojiProvider())

        MessageRepository.init(this)
        ChatRepository.init(this)
        LockedZoneGuard.init()
    }
}