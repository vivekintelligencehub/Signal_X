package com.signalX

import android.graphics.Color
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SearchGroupAdapter(
    private val groups: MutableList<ChatSearchGroup>,
    private val onRowJumpClick: (ChatSearchGroup) -> Unit,
    private val onHighlightClick: (ChatSearchGroup) -> Unit,
    private val onMatchClick: (ChatSearchGroup, MessageSearchHit) -> Unit,
    private val showExpandIcon: Boolean = true
) : RecyclerView.Adapter<SearchGroupAdapter.GroupViewHolder>() {

    private val expandedChatIds = mutableSetOf<String>()

    inner class GroupViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val rowMain: View = itemView.findViewById(R.id.rowMain)
        val tvAvatarLetter: TextView = itemView.findViewById(R.id.tvAvatarLetter)
        val tvGroupName: TextView = itemView.findViewById(R.id.tvGroupName)
        val tvArchivedTag: TextView = itemView.findViewById(R.id.tvArchivedTag)
        val tvGroupSnippet: TextView = itemView.findViewById(R.id.tvGroupSnippet)
        val tvGroupTime: TextView = itemView.findViewById(R.id.tvGroupTime)
        val tvMatchCount: TextView = itemView.findViewById(R.id.tvMatchCount)
        val btnFindAll: ImageView = itemView.findViewById(R.id.btnFindAll)
        val btnExpandChevron: TextView = itemView.findViewById(R.id.btnExpandChevron)
        val btnExpandArea: View = itemView.findViewById(R.id.btnExpandArea) // Naya add kiya
        val expandedContainer: LinearLayout = itemView.findViewById(R.id.expandedContainer)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_search_group, parent, false)
        return GroupViewHolder(view)
    }

    override fun getItemCount(): Int = groups.size

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        val group = groups[position]
        val chat = group.chatItem

        holder.tvAvatarLetter.text = chat.name.take(1).uppercase()
        holder.tvGroupName.text = chat.name

        // Locked content red, normal content black
        val hasLockedSessions = group.revealedSessionIds.isNotEmpty()
        holder.tvGroupName.setTextColor(if (hasLockedSessions) Color.RED else Color.BLACK)

        holder.tvArchivedTag.visibility = if (chat.location == ChatLocation.ARCHIVED) View.VISIBLE else View.GONE

        val latestHit = group.messageHits.firstOrNull()
        if (latestHit != null) {
            holder.tvGroupSnippet.text = latestHit.message.text
            holder.tvGroupSnippet.setTextColor(if (latestHit.sessionId != null) Color.RED else Color.BLACK)
            holder.tvGroupTime.text = DateFormat.format("dd MMM, hh:mm a", latestHit.message.timestamp)
        } else {
            holder.tvGroupSnippet.text = chat.subtitle
            holder.tvGroupSnippet.setTextColor(Color.DKGRAY)
            holder.tvGroupTime.text = ""
        }

        // FIX: Find All icon ab DO cases mein dikhega:
        // 1. Password valid hua (revealedSessionIds non-empty)
        // 2. Normal search mein bahut saare messages match ho rahe hain (messageHits.size > 1)
        val showFindAll = group.revealedSessionIds.isNotEmpty() || group.messageHits.size > 1
        holder.btnFindAll.visibility = if (showFindAll) View.VISIBLE else View.GONE
        holder.btnFindAll.setOnClickListener { onHighlightClick(group) }

        // Agar sirf naam match hua hai (messageHits empty), toh Find All nahi dikhega
        if (showExpandIcon) {
            holder.tvMatchCount.visibility = View.VISIBLE
            holder.tvMatchCount.text = "${group.messageHits.size} matches"
            holder.btnExpandChevron.visibility = View.VISIBLE

            val isExpanded = expandedChatIds.contains(chat.id)
            holder.btnExpandChevron.text = if (isExpanded) "⌃" else "⌄"
            holder.expandedContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE

            if (isExpanded) {
                holder.expandedContainer.removeAllViews()
                for (hit in group.messageHits) {
                    val rowView = LayoutInflater.from(holder.itemView.context)
                        .inflate(R.layout.item_search_match_row, holder.expandedContainer, false)
                    rowView.findViewById<TextView>(R.id.tvMatchTime).text = DateFormat.format("dd MMM, hh:mm a", hit.message.timestamp)
                    rowView.findViewById<TextView>(R.id.tvMatchSnippet).text = hit.message.text
                    rowView.findViewById<TextView>(R.id.tvMatchSnippet).setTextColor(if (hit.sessionId != null) Color.RED else Color.BLACK)
                    rowView.setOnClickListener { onMatchClick(group, hit) }
                    holder.expandedContainer.addView(rowView)
                }
            }

            // CHANGE: Chevron ka listener hata kar ab btnExpandArea (invisible box) par laga diya
            holder.btnExpandArea.setOnClickListener {
                if (expandedChatIds.contains(chat.id)) expandedChatIds.remove(chat.id) else expandedChatIds.add(chat.id)
                notifyItemChanged(position)
            }
        } else {
            holder.tvMatchCount.visibility = View.GONE
            holder.btnExpandChevron.visibility = View.GONE
            holder.expandedContainer.visibility = View.GONE
        }

        holder.rowMain.setOnClickListener { onRowJumpClick(group) }
    }

    fun updateList(newGroups: List<ChatSearchGroup>) {
        groups.clear()
        groups.addAll(newGroups)
        notifyDataSetChanged()
    }
}