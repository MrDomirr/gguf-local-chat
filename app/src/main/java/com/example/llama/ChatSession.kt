package com.example.llama

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A single, independently persisted conversation. The drawer lets the user
 * create, switch between, rename, pin and delete these.
 */
data class ChatSession(
    val id: String,
    var title: String,
    var pinned: Boolean = false,
    // true once the user has manually renamed the chat; prevents us from
    // overwriting their chosen title with an auto-generated one.
    var titleManual: Boolean = false,
    val createdAt: Long,
    var updatedAt: Long,
    val messages: MutableList<Message> = mutableListOf(),
) {
    fun toJson(): JSONObject {
        val messagesArray = JSONArray()
        messages.forEach { messagesArray.put(it.toJson()) }
        return JSONObject()
            .put("id", id)
            .put("title", title)
            .put("pinned", pinned)
            .put("titleManual", titleManual)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
            .put("messages", messagesArray)
    }

    companion object {
        fun fromJson(json: JSONObject): ChatSession? = runCatching {
            val messagesArray = json.optJSONArray("messages") ?: JSONArray()
            val restoredMessages = mutableListOf<Message>()
            for (index in 0 until messagesArray.length()) {
                Message.fromJson(messagesArray.getJSONObject(index))?.let(restoredMessages::add)
            }
            val now = System.currentTimeMillis()
            ChatSession(
                id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                title = json.optString("title").ifBlank { "Новый чат" },
                pinned = json.optBoolean("pinned", false),
                titleManual = json.optBoolean("titleManual", false),
                createdAt = if (json.has("createdAt")) json.optLong("createdAt") else now,
                updatedAt = if (json.has("updatedAt")) json.optLong("updatedAt") else now,
                messages = restoredMessages,
            )
        }.getOrNull()
    }
}
