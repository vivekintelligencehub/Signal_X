package com.signalX

sealed class ChatListRow {
    data class MessageRow(val message: Message) : ChatListRow()
    data class SessionBannerRow(val session: ChatSession) : ChatListRow()
    data class RevealedSessionRow(val session: ChatSession, val messages: List<Message>) : ChatListRow()
}