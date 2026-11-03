package com.signalX

enum class ChatLocation { NORMAL, ARCHIVED, LOCKED }
enum class ChatTabType { DIRECT, GROUP }

data class ChatItem(
    val id: String,
    val name: String,
    val subtitle: String,
    val time: String = "",
    val isActive: Boolean? = null,
    val isSelf: Boolean = false,
    val isPinned: Boolean = false,
    val isBlocked: Boolean = false,
    val location: ChatLocation = ChatLocation.NORMAL,
    val tabType: ChatTabType = ChatTabType.DIRECT
)