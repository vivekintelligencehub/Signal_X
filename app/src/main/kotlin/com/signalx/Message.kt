package com.signalX

data class Message(
    val id: String,
    val text: String,
    val timestamp: Long,
    val sessionId: String? = null,
    val caption: String? = null
)