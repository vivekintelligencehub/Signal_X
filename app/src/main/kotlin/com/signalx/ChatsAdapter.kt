package com.signalX

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatsAdapter(
    private val items: MutableList<ChatItem>,
    private val hostLocation: ChatLocation,
    private val onItemClick: (ChatItem) -> Unit,
    private val onMenuClick: (ChatItem, View) -> Unit,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<ChatsAdapter.ChatViewHolder>() {

    var isMultiSelectMode = false
    val selectedIds = mutableSetOf<String>()


    inner class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        val tvAvatarLetter: TextView = itemView.findViewById(R.id.tvAvatarLetter)
        val ivSelectedCheck: ImageView = itemView.findViewById(R.id.ivSelectedCheck)
        val tvName: TextView = itemView.findViewById(R.id.tvChatName)
        val ivPinIndicator: TextView = itemView.findViewById(R.id.ivPinIndicator)
        val tvArchivedTag: TextView = itemView.findViewById(R.id.tvArchivedTag)
        val tvSubtitle: TextView = itemView.findViewById(R.id.tvChatSubtitle)
        val tvTime: TextView = itemView.findViewById(R.id.tvChatTime)
        val statusDot: View = itemView.findViewById(R.id.statusDot)
        val btnMenu: TextView = itemView.findViewById(R.id.btnChatMenu)
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {

        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat, parent, false)
        return ChatViewHolder(view)
    }


    override fun getItemCount(): Int = items.size

    fun currentItems(): List<ChatItem> = items


    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {

        val item = items[position]

        holder.tvAvatarLetter.text = item.name.take(1).uppercase()
        holder.tvName.text = item.name

        holder.ivPinIndicator.visibility =
            if (item.isPinned) View.VISIBLE else View.GONE

        holder.tvArchivedTag.visibility =
            if (hostLocation == ChatLocation.NORMAL && item.location == ChatLocation.ARCHIVED) View.VISIBLE
            else View.GONE

        if (item.isBlocked) {
            holder.tvSubtitle.text = "Blocked"
            holder.tvSubtitle.setTextColor(0xFFF44336.toInt())
        } else {
            holder.tvSubtitle.text = item.subtitle
            holder.tvSubtitle.setTextColor(0xFF777777.toInt())
        }

        holder.tvTime.text = item.time


        if (item.isSelf) {

            holder.tvTime.visibility = View.INVISIBLE
            holder.statusDot.visibility = View.VISIBLE
            holder.statusDot.setBackgroundResource(R.drawable.bg_dot_green)
            holder.itemView.setBackgroundColor(0xFFF5F8FF.toInt())

        } else {

            holder.tvTime.visibility = View.VISIBLE
            holder.statusDot.visibility = View.VISIBLE
            holder.itemView.setBackgroundColor(0xFFFFFFFF.toInt())

            holder.statusDot.setBackgroundResource(
                if (item.isActive == true) R.drawable.bg_dot_green
                else R.drawable.bg_dot_red
            )
        }


        if (isMultiSelectMode) {

            holder.btnMenu.visibility = View.GONE
            holder.ivSelectedCheck.visibility = View.VISIBLE

            val selected = selectedIds.contains(item.id)

            holder.ivSelectedCheck.setImageResource(
                if (selected) R.drawable.ic_check_circle_filled
                else R.drawable.ic_check_circle_empty
            )

        } else {

            holder.btnMenu.visibility = View.VISIBLE
            holder.ivSelectedCheck.visibility = View.GONE
        }


        holder.itemView.setOnClickListener {

            if (isMultiSelectMode) toggleSelection(item.id)
            else onItemClick(item)
        }


        holder.itemView.setOnLongClickListener {

            if (isMultiSelectMode) toggleSelection(item.id)
            else onMenuClick(item, holder.itemView)

            true
        }


        holder.btnMenu.setOnClickListener {
            onMenuClick(item, it)
        }
    }


    private fun toggleSelection(id: String) {

        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)

        notifyDataSetChanged()
        onSelectionChanged()
    }


    fun updateList(newItems: List<ChatItem>) {

        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}