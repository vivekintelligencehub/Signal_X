package com.signalX

import android.content.Context

object SelfSearchHelper {

    fun getSelfName(context: Context): String {

        val prefs = context.getSharedPreferences("signalx_prefs", Context.MODE_PRIVATE)
        val synced = prefs.getString("synced_name", null)

        return if (synced.isNullOrBlank()) "You" else synced.trim()
    }


    // Inbox search ke liye — self dikhega jab tak Locked mein na ho
    fun matchSelfForInboxSearch(context: Context, query: String): ChatItem? {

        if (ChatRepository.selfLocation == ChatLocation.LOCKED) return null

        val selfName = getSelfName(context)

        if (!selfName.contains(query.trim(), ignoreCase = true)) return null

        return ChatItem(
            id = "self",
            name = selfName,
            subtitle = "(Manage Yourself)",
            isSelf = true,
            location = ChatRepository.selfLocation
        )
    }


    // Locked search ke liye — self sirf tab dikhega jab woh actually Locked mein ho
    fun matchSelfForLockedSearch(context: Context, query: String): ChatItem? {

        if (ChatRepository.selfLocation != ChatLocation.LOCKED) return null

        val selfName = getSelfName(context)

        if (!selfName.contains(query.trim(), ignoreCase = true)) return null

        return ChatItem(
            id = "self",
            name = selfName,
            subtitle = "(Manage Yourself)",
            isSelf = true,
            location = ChatLocation.LOCKED
        )
    }
}