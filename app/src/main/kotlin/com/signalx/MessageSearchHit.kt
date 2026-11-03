package com.signalX

data class MessageSearchHit(
    val message: Message,
    val sessionId: String?
)