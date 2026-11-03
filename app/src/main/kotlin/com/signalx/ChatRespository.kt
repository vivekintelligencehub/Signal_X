package com.signalX

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object ChatRepository {

    private const val PREFS_NAME = "signalx_prefs"
    private const val KEY_CHATS = "chats_store"
    private const val KEY_SELF_LOCATION = "self_location_store"
    private const val KEY_CUSTOM_LISTS = "custom_lists_store"
    private const val KEY_LIST_MEMBERSHIPS = "list_memberships_store"

    private var appContext: Context? = null
    private var isLoading = false

    val allChats = mutableListOf<ChatItem>()

    private var _selfLocation: ChatLocation = ChatLocation.NORMAL

    var selfLocation: ChatLocation
        get() = _selfLocation
        set(value) {
            _selfLocation = value

            // Loading ke dauran saveToDisk() kabhi trigger nahi hona chahiye —
            // isi guard ki kami se pehle data har start pe khud-ba-khud khaali ho raha tha

            if (!isLoading) saveToDisk()
        }

    val customLists = mutableListOf<String>()
    private val chatListMemberships = mutableMapOf<String, MutableSet<String>>()


    fun init(context: Context) {

        if (appContext != null) return

        appContext = context.applicationContext
        loadFromDisk()
    }


    private fun prefs() =
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)


    private fun loadFromDisk() {

        val sp = prefs() ?: return

        isLoading = true

        allChats.clear()
        customLists.clear()
        chatListMemberships.clear()


        val selfLocName = sp.getString(KEY_SELF_LOCATION, null)

        if (selfLocName != null) {

            // Seedha backing field set kar rahe hain, taaki property-setter
            // (jo saveToDisk() call karta hai) bilkul bypass ho jaaye

            _selfLocation = ChatLocation.valueOf(selfLocName)
        }


        val chatsJson = sp.getString(KEY_CHATS, null)

        if (chatsJson != null) {

            val arr = JSONArray(chatsJson)

            for (i in 0 until arr.length()) {

                val obj = arr.getJSONObject(i)

                allChats.add(
                    ChatItem(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        subtitle = obj.getString("subtitle"),
                        time = obj.optString("time", ""),
                        isActive = if (obj.isNull("isActive")) null else obj.getBoolean("isActive"),
                        isSelf = obj.getBoolean("isSelf"),
                        isPinned = obj.getBoolean("isPinned"),
                        isBlocked = obj.getBoolean("isBlocked"),
                        location = ChatLocation.valueOf(obj.getString("location")),
                        tabType = ChatTabType.valueOf(obj.getString("tabType"))
                    )
                )
            }
        }


        val listsJson = sp.getString(KEY_CUSTOM_LISTS, null)

        if (listsJson != null) {

            val arr = JSONArray(listsJson)
            val seen = LinkedHashSet<String>()

            for (i in 0 until arr.length()) {
                seen.add(arr.getString(i))
            }

            customLists.addAll(seen)
        }


        val membershipsJson = sp.getString(KEY_LIST_MEMBERSHIPS, null)

        if (membershipsJson != null) {

            val root = JSONObject(membershipsJson)
            val keys = root.keys()

            while (keys.hasNext()) {

                val chatId = keys.next()
                val arr = root.getJSONArray(chatId)
                val set = mutableSetOf<String>()

                for (i in 0 until arr.length()) {
                    set.add(arr.getString(i))
                }

                chatListMemberships[chatId] = set
            }
        }

        isLoading = false
    }


    private fun saveToDisk() {

        val sp = prefs() ?: return
        val editor = sp.edit()

        editor.putString(KEY_SELF_LOCATION, _selfLocation.name)

        val chatsArr = JSONArray()

        for (item in allChats) {

            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("name", item.name)
            obj.put("subtitle", item.subtitle)
            obj.put("time", item.time)
            obj.put("isActive", item.isActive ?: JSONObject.NULL)
            obj.put("isSelf", item.isSelf)
            obj.put("isPinned", item.isPinned)
            obj.put("isBlocked", item.isBlocked)
            obj.put("location", item.location.name)
            obj.put("tabType", item.tabType.name)

            chatsArr.put(obj)
        }

        editor.putString(KEY_CHATS, chatsArr.toString())

        val listsArr = JSONArray()

        for (name in customLists.distinct()) {
            listsArr.put(name)
        }

        editor.putString(KEY_CUSTOM_LISTS, listsArr.toString())

        val membershipsRoot = JSONObject()

        for ((chatId, set) in chatListMemberships) {

            val arr = JSONArray()

            for (name in set) {
                arr.put(name)
            }

            membershipsRoot.put(chatId, arr)
        }

        editor.putString(KEY_LIST_MEMBERSHIPS, membershipsRoot.toString())

        editor.commit()
    }


    fun createList(name: String): Boolean {

        val trimmed = name.trim()

        if (trimmed.isBlank() || customLists.contains(trimmed)) return false

        customLists.add(trimmed)
        saveToDisk()
        return true
    }


    fun deleteList(name: String) {

        customLists.remove(name)

        for (id in chatListMemberships.keys) {
            chatListMemberships[id]?.remove(name)
        }

        saveToDisk()
    }


    fun addChatsToList(chatIds: List<String>, listName: String) {

        for (id in chatIds) {
            chatListMemberships.getOrPut(id) { mutableSetOf() }.add(listName)
        }

        saveToDisk()
    }


    fun removeChatsFromList(chatIds: List<String>, listName: String) {

        for (id in chatIds) {
            chatListMemberships[id]?.remove(listName)
        }

        saveToDisk()
    }


    fun isChatInList(chatId: String, listName: String): Boolean =
        chatListMemberships[chatId]?.contains(listName) == true


    fun getChats(location: ChatLocation, tabType: ChatTabType): List<ChatItem> {

        return allChats.filter {
            it.location == location && it.tabType == tabType
        }
    }


    fun searchInLocation(query: String, location: ChatLocation, tabType: ChatTabType): List<ChatItem> {

        return getChats(location, tabType).filter {
            it.name.contains(query, ignoreCase = true)
        }
    }


    fun searchNormalPlusArchived(query: String, tabType: ChatTabType): List<ChatItem> {

        return allChats.filter {
            it.tabType == tabType &&
                (it.location == ChatLocation.NORMAL || it.location == ChatLocation.ARCHIVED) &&
                it.name.contains(query, ignoreCase = true)
        }
    }


    fun updateChat(id: String, transform: (ChatItem) -> ChatItem) {

        val idx = allChats.indexOfFirst { it.id == id }

        if (idx != -1) {
            allChats[idx] = transform(allChats[idx])
            saveToDisk()
        }
    }


    fun removeChat(id: String) {
        allChats.removeAll { it.id == id }
        saveToDisk()
    }


    fun getChatById(id: String): ChatItem? =
        allChats.find { it.id == id }
}