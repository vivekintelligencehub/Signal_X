package com.signalX

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SearchResultAdapter(
    private val items: MutableList<ChatItem>,
    private val onItemClick: (ChatItem) -> Unit
) : RecyclerView.Adapter<SearchResultAdapter.ResultViewHolder>() {

    inner class ResultViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        val tvAvatarLetter: TextView = itemView.findViewById(R.id.tvAvatarLetter)
        val tvName: TextView = itemView.findViewById(R.id.tvChatName)
        val tvArchivedTag: TextView = itemView.findViewById(R.id.tvArchivedTag)
        val tvSubtitle: TextView = itemView.findViewById(R.id.tvChatSubtitle)
        val tvTime: TextView = itemView.findViewById(R.id.tvChatTime)
        val statusDot: View = itemView.findViewById(R.id.statusDot)
        val btnMenu: View = itemView.findViewById(R.id.btnChatMenu)
        val ivSelectedCheck: View = itemView.findViewById(R.id.ivSelectedCheck)
        val ivPinIndicator: View = itemView.findViewById(R.id.ivPinIndicator)
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultViewHolder {

        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat, parent, false)
        return ResultViewHolder(view)
    }


    override fun getItemCount(): Int = items.size


    override fun onBindViewHolder(holder: ResultViewHolder, position: Int) {

        val item = items[position]

        holder.tvAvatarLetter.text = item.name.take(1).uppercase()
        holder.tvName.text = item.name
        holder.tvSubtitle.text = item.subtitle

        holder.ivPinIndicator.visibility = if (item.isPinned) View.VISIBLE else View.GONE
        holder.tvArchivedTag.visibility = if (item.location == ChatLocation.ARCHIVED) View.VISIBLE else View.GONE

        holder.tvTime.text = item.time

        if (item.isSelf) {

            holder.tvTime.visibility = View.INVISIBLE
            holder.statusDot.visibility = View.VISIBLE
            holder.statusDot.setBackgroundResource(R.drawable.bg_dot_green)

        } else {

            holder.tvTime.visibility = View.VISIBLE
            holder.statusDot.visibility = View.VISIBLE

            holder.statusDot.setBackgroundResource(
                if (item.isActive == true) R.drawable.bg_dot_green else R.drawable.bg_dot_red
            )
        }

        holder.btnMenu.visibility = View.GONE
        holder.ivSelectedCheck.visibility = View.GONE

        holder.itemView.setOnClickListener {
            onItemClick(item)
        }
    }


    fun updateList(newItems: List<ChatItem>) {

        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}