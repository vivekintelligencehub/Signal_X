package com.signalX

data class ChatSession(
    val id: String,
    val startTime: Long,
    val lockTime: Long?,
    val isLocked: Boolean,
    val useCommonPassword: Boolean,
    val uniquePasswordHash: String?,
    val biometricEnabled: Boolean
)