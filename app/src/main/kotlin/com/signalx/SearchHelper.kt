package com.signalX

import android.content.Context

object SearchHelper {

    data class SearchOutcome(
        val effectiveQuery: String,
        val lockedGroups: List<ChatSearchGroup>,
        val normalGroups: List<ChatSearchGroup>
    )

    private fun isPasswordValid(context: Context, pass: String, chatIdsInScope: Set<String>): Boolean {
        if (MessageRepository.verifyCommonPassword(context, pass)) return true
        for (chatId in chatIdsInScope) {
            for (session in MessageRepository.getSessionsForChat(chatId)) {
                if (session.uniquePasswordHash != null && SecurityUtils.hash(pass) == session.uniquePasswordHash) return true
            }
        }
        return false
    }

    fun mergeGroupsByChat(locked: List<ChatSearchGroup>, normal: List<ChatSearchGroup>): List<ChatSearchGroup> {
        val mergedMap = mutableMapOf<String, ChatSearchGroup>()
        for (g in locked) mergedMap[g.chatItem.id] = g

        for (g in normal) {
            val existing = mergedMap[g.chatItem.id]
            if (existing == null) {
                mergedMap[g.chatItem.id] = g
            } else {
                val combinedHits = (existing.messageHits + g.messageHits)
                    .distinctBy { it.message.id }
                    .sortedByDescending { it.message.timestamp }
                mergedMap[g.chatItem.id] = existing.copy(
                    nameMatched = existing.nameMatched || g.nameMatched,
                    messageHits = combinedHits,
                    revealedSessionIds = existing.revealedSessionIds + g.revealedSessionIds
                )
            }
        }
        return mergedMap.values.sortedWith(
            compareByDescending<ChatSearchGroup> { it.chatItem.isPinned }
                .thenByDescending { it.messageHits.firstOrNull()?.message?.timestamp ?: 0L }
        )
    }

    fun search(
        context: Context,
        rawInput: String,
        locations: Set<ChatLocation>,
        includeNormal: Boolean,
        fallbackToNormalSearch: Boolean
    ): SearchOutcome {

        if (rawInput.isBlank()) return SearchOutcome("", emptyList(), emptyList())

        val chatsInScope = ChatRepository.allChats.filter { it.location in locations }
        val chatIdsInScope = chatsInScope.map { it.id }.toMutableSet()
        val selfIncluded = ChatRepository.selfLocation in locations
        if (selfIncluded) chatIdsInScope.add("self")

        val slashIndex = rawInput.indexOf('/')

        if (slashIndex != -1) {
            val passwordPart = rawInput.substring(0, slashIndex)
            val queryPart = rawInput.substring(slashIndex + 1).trim()
            val candidatePasswords = passwordPart.split(",").map { it.trim() }.filter { it.isNotBlank() }

            val allValid = candidatePasswords.isNotEmpty() && candidatePasswords.all { isPasswordValid(context, it, chatIdsInScope) }

            if (allValid) {
                val revealedSessionIds = mutableSetOf<String>()
                for (chatId in chatIdsInScope) {
                    for (session in MessageRepository.getSessionsForChat(chatId)) {
                        if (!session.isLocked) continue
                        val matched = if (session.useCommonPassword) {
                            candidatePasswords.any { MessageRepository.verifyCommonPassword(context, it) }
                        } else {
                            candidatePasswords.any { pass -> session.uniquePasswordHash == SecurityUtils.hash(pass) }
                        }
                        if (matched) revealedSessionIds.add(session.id)
                    }
                }

                val lockedGroups = buildSessionOnlyGroups(context, queryPart, chatsInScope, selfIncluded, revealedSessionIds)
                // FIX: Jab query blank ho, toh normal groups empty rakho
                val normalGroups = if (includeNormal && !queryPart.isBlank()) {
                    buildNormalGroups(context, queryPart, chatsInScope, selfIncluded)
                } else {
                    emptyList()
                }
                return SearchOutcome(queryPart, lockedGroups, normalGroups)
            } else {
                if (fallbackToNormalSearch) {
                    val groups = buildNormalGroups(context, rawInput, chatsInScope, selfIncluded)
                    return SearchOutcome(rawInput, emptyList(), groups)
                } else {
                    return SearchOutcome(queryPart, emptyList(), emptyList())
                }
            }
        } else {
            val groups = buildNormalGroups(context, rawInput, chatsInScope, selfIncluded)
            return SearchOutcome(rawInput, emptyList(), groups)
        }
    }

    private fun buildNormalGroups(context: Context, query: String, chatsInScope: List<ChatItem>, selfIncluded: Boolean): List<ChatSearchGroup> {
        val groups = mutableListOf<ChatSearchGroup>()
        fun buildGroup(chatId: String, chatItem: ChatItem, chatName: String): ChatSearchGroup? {
            val nameMatched = chatName.contains(query, ignoreCase = true)
            val hits = if (query.isBlank()) {
                emptyList()
            } else {
                MessageRepository.getMessages(chatId)
                    .filter { it.sessionId == null }
                    .filter { it.text.contains(query, ignoreCase = true) }
                    .sortedByDescending { it.timestamp }
                    .map { MessageSearchHit(it, null) }
            }
            if (!nameMatched && hits.isEmpty()) return null
            return ChatSearchGroup(chatItem, nameMatched, hits, emptySet())
        }

        if (selfIncluded) {
            val selfName = SelfSearchHelper.getSelfName(context) // Purana name logic
            val selfChatItem = ChatItem(id = "self", name = selfName, subtitle = "(Manage Yourself)", isSelf = true, location = ChatRepository.selfLocation)
            buildGroup("self", selfChatItem, selfName)?.let { groups.add(it) }
        }
        for (chat in chatsInScope) {
            buildGroup(chat.id, chat, chat.name)?.let { groups.add(it) } // Purana naam logic
        }
        return sortGroups(groups)
    }

    private fun buildSessionOnlyGroups(context: Context, query: String, chatsInScope: List<ChatItem>, selfIncluded: Boolean, revealedSessionIds: Set<String>): List<ChatSearchGroup> {
        val groups = mutableListOf<ChatSearchGroup>()
        fun buildGroup(chatId: String, chatItem: ChatItem): ChatSearchGroup? {
            val allSessionMessages = MessageRepository.getMessages(chatId)
                .filter { it.sessionId != null && revealedSessionIds.contains(it.sessionId) }

            val hits = if (query.isBlank()) {
                emptyList()
            } else {
                allSessionMessages.filter { it.text.contains(query, ignoreCase = true) }
                    .sortedByDescending { it.timestamp }
                    .map { MessageSearchHit(it, it.sessionId) }
            }

            if (revealedSessionIds.isNotEmpty()) {
                return ChatSearchGroup(chatItem, false, hits, allSessionMessages.mapNotNull { it.sessionId }.toSet())
            }
            if (hits.isEmpty()) return null
            return ChatSearchGroup(chatItem, false, hits, allSessionMessages.mapNotNull { it.sessionId }.toSet())
        }

        if (selfIncluded) {
            val selfName = SelfSearchHelper.getSelfName(context)
            val selfChatItem = ChatItem(id = "self", name = selfName, subtitle = "(Manage Yourself)", isSelf = true, location = ChatRepository.selfLocation)
            buildGroup("self", selfChatItem)?.let { groups.add(it) }
        }
        for (chat in chatsInScope) {
            buildGroup(chat.id, chat)?.let { groups.add(it) }
        }
        return sortGroups(groups)
    }

    private fun sortGroups(groups: List<ChatSearchGroup>): List<ChatSearchGroup> {
        return groups.sortedWith(compareByDescending<ChatSearchGroup> { it.chatItem.isPinned }.thenByDescending { it.messageHits.firstOrNull()?.message?.timestamp ?: 0L })
    }
}