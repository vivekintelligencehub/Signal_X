package com.signalX

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object MessageRepository {

    private const val PREFS_NAME = "signalx_prefs"
    private const val KEY_MESSAGES = "messages_store"
    private const val KEY_SESSIONS = "sessions_store"
    private const val KEY_ACTIVE_SESSIONS = "active_session_store"

    private var appContext: Context? = null

    private val messagesByChat = mutableMapOf<String, MutableList<Message>>()
    private val sessionsByChat = mutableMapOf<String, MutableList<ChatSession>>()
    private val activeSessionIdByChat = mutableMapOf<String, String?>()

    private var sessionCounter = 0


    fun init(context: Context) {

        if (appContext != null) return

        appContext = context.applicationContext
        loadFromDisk()
    }


    private fun prefs() =
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)


    private fun loadFromDisk() {

        val sp = prefs() ?: return


        val messagesJson = sp.getString(KEY_MESSAGES, null)

        if (messagesJson != null) {

            val root = JSONObject(messagesJson)
            val keys = root.keys()

            while (keys.hasNext()) {

                val chatId = keys.next()
                val arr = root.getJSONArray(chatId)
                val list = mutableListOf<Message>()

                for (i in 0 until arr.length()) {

                    val obj = arr.getJSONObject(i)

                    list.add(
                        Message(
                            id = obj.getString("id"),
                            text = obj.getString("text"),
                            timestamp = obj.getLong("timestamp"),
                            sessionId = if (obj.isNull("sessionId")) null else obj.getString("sessionId"),
                            caption = if (!obj.has("caption") || obj.isNull("caption")) null else obj.getString("caption")
                        )
                    )
                }

                messagesByChat[chatId] = list
            }
        }


        val sessionsJson = sp.getString(KEY_SESSIONS, null)

        if (sessionsJson != null) {

            val root = JSONObject(sessionsJson)
            val keys = root.keys()

            while (keys.hasNext()) {

                val chatId = keys.next()
                val arr = root.getJSONArray(chatId)
                val list = mutableListOf<ChatSession>()

                for (i in 0 until arr.length()) {

                    val obj = arr.getJSONObject(i)

                    list.add(
                        ChatSession(
                            id = obj.getString("id"),
                            startTime = obj.getLong("startTime"),
                            lockTime = if (obj.isNull("lockTime")) null else obj.getLong("lockTime"),
                            isLocked = obj.getBoolean("isLocked"),
                            useCommonPassword = obj.getBoolean("useCommonPassword"),
                            uniquePasswordHash = if (obj.isNull("uniquePasswordHash")) null else obj.getString("uniquePasswordHash"),
                            biometricEnabled = obj.getBoolean("biometricEnabled")
                        )
                    )
                }

                sessionsByChat[chatId] = list
            }
        }


        val activeJson = sp.getString(KEY_ACTIVE_SESSIONS, null)

        if (activeJson != null) {

            val root = JSONObject(activeJson)
            val keys = root.keys()

            while (keys.hasNext()) {

                val chatId = keys.next()
                activeSessionIdByChat[chatId] = if (root.isNull(chatId)) null else root.getString(chatId)
            }
        }


        var maxCounter = 0

        for (list in sessionsByChat.values) {

            for (session in list) {

                val suffix = session.id.substringAfterLast("_").toIntOrNull()

                if (suffix != null && suffix > maxCounter) {
                    maxCounter = suffix
                }
            }
        }

        sessionCounter = maxCounter
    }


    private fun saveToDisk() {

        val sp = prefs() ?: return
        val editor = sp.edit()


        val messagesRoot = JSONObject()

        for ((chatId, list) in messagesByChat) {

            val arr = JSONArray()

            for (m in list) {

                val obj = JSONObject()
                obj.put("id", m.id)
                obj.put("text", m.text)
                obj.put("timestamp", m.timestamp)
                obj.put("sessionId", m.sessionId ?: JSONObject.NULL)
                obj.put("caption", m.caption ?: JSONObject.NULL)

                arr.put(obj)
            }

            messagesRoot.put(chatId, arr)
        }

        editor.putString(KEY_MESSAGES, messagesRoot.toString())


        val sessionsRoot = JSONObject()

        for ((chatId, list) in sessionsByChat) {

            val arr = JSONArray()

            for (s in list) {

                val obj = JSONObject()
                obj.put("id", s.id)
                obj.put("startTime", s.startTime)
                obj.put("lockTime", s.lockTime ?: JSONObject.NULL)
                obj.put("isLocked", s.isLocked)
                obj.put("useCommonPassword", s.useCommonPassword)
                obj.put("uniquePasswordHash", s.uniquePasswordHash ?: JSONObject.NULL)
                obj.put("biometricEnabled", s.biometricEnabled)

                arr.put(obj)
            }

            sessionsRoot.put(chatId, arr)
        }

        editor.putString(KEY_SESSIONS, sessionsRoot.toString())


        val activeRoot = JSONObject()

        for ((chatId, sessionId) in activeSessionIdByChat) {
            activeRoot.put(chatId, sessionId ?: JSONObject.NULL)
        }

        editor.putString(KEY_ACTIVE_SESSIONS, activeRoot.toString())


        // commit() = turant, synchronous save — chahe app usi pal band ho jaaye, data disk pe pahunch chuka hoga

        editor.commit()
    }


    fun getMessages(chatId: String): List<Message> =
        messagesByChat[chatId] ?: emptyList()


    fun addMessage(chatId: String, text: String, caption: String? = null): Message {

        val message = Message(
            id = "msg_${System.currentTimeMillis()}_${(0..999).random()}",
            text = text,
            timestamp = System.currentTimeMillis(),
            sessionId = activeSessionIdByChat[chatId],
            caption = caption
        )

        messagesByChat.getOrPut(chatId) { mutableListOf() }.add(message)

        saveToDisk()

        return message
    }


    fun getActiveSessionId(chatId: String): String? =
        activeSessionIdByChat[chatId]


    fun startSession(
        chatId: String,
        useCommonPassword: Boolean,
        uniquePasswordHash: String?,
        biometricEnabled: Boolean
    ): ChatSession {

        sessionCounter++

        val session = ChatSession(
            id = "session_${chatId}_$sessionCounter",
            startTime = System.currentTimeMillis(),
            lockTime = null,
            isLocked = false,
            useCommonPassword = useCommonPassword,
            uniquePasswordHash = uniquePasswordHash,
            biometricEnabled = biometricEnabled
        )

        sessionsByChat.getOrPut(chatId) { mutableListOf() }.add(session)
        activeSessionIdByChat[chatId] = session.id

        saveToDisk()

        return session
    }


    fun lockActiveSession(chatId: String) {

        val activeId = activeSessionIdByChat[chatId] ?: return
        val list = sessionsByChat[chatId] ?: return
        val idx = list.indexOfFirst { it.id == activeId }

        if (idx != -1) {

            list[idx] = list[idx].copy(
                isLocked = true,
                lockTime = System.currentTimeMillis()
            )
        }

        activeSessionIdByChat[chatId] = null

        saveToDisk()
    }


    fun getSession(chatId: String, sessionId: String): ChatSession? =
        sessionsByChat[chatId]?.find { it.id == sessionId }


    fun getSessionsForChat(chatId: String): List<ChatSession> =
        sessionsByChat[chatId] ?: emptyList()


    fun hasCommonPassword(context: Context): Boolean {

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains("common_session_pass_hash")
    }


    fun setCommonPassword(context: Context, password: String) {

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("common_session_pass_hash", SecurityUtils.hash(password)).apply()
    }


    fun verifyCommonPassword(context: Context, password: String): Boolean {

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString("common_session_pass_hash", null) ?: return false

        return SecurityUtils.hash(password) == saved
    }
}