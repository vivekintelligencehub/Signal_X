package com.signalx

data class MessageSearchHit(
    val message: Message,
    val sessionId: String?
)