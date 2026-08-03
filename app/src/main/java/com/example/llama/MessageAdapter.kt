package com.example.llama

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

data class Message(
    val id: String,
    val content: String,
    val isUser: Boolean,
    val speaker: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("content", content)
        .put("isUser", isUser)
        .put("speaker", speaker)

    companion object {
        fun fromJson(json: JSONObject): Message? = runCatching {
            Message(
                id = json.optString("id"),
                content = json.optString("content"),
                isUser = json.optBoolean("isUser"),
                speaker = json.optString("speaker").ifBlank { if (json.optBoolean("isUser")) "Вы" else "Модель" }
            )
        }.getOrNull()
    }
}

class MessageAdapter(
    private val messages: List<Message>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_ASSISTANT = 2
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isUser) VIEW_TYPE_USER else VIEW_TYPE_ASSISTANT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_USER) {
            val view = layoutInflater.inflate(R.layout.item_message_user, parent, false)
            UserMessageViewHolder(view)
        } else {
            val view = layoutInflater.inflate(R.layout.item_message_assistant, parent, false)
            AssistantMessageViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        holder.itemView.findViewById<TextView?>(R.id.msg_speaker)?.text = message.speaker
        holder.itemView.findViewById<TextView>(R.id.msg_content).text = message.content
    }

    override fun getItemCount(): Int = messages.size

    class UserMessageViewHolder(view: View) : RecyclerView.ViewHolder(view)
    class AssistantMessageViewHolder(view: View) : RecyclerView.ViewHolder(view)
}
