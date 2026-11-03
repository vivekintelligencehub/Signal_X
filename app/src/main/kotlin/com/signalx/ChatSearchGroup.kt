package com.signalX

data class ChatSearchGroup(
    val chatItem: ChatItem,
    val nameMatched: Boolean,
    val messageHits: List<MessageSearchHit>,
    val revealedSessionIds: Set<String>
)