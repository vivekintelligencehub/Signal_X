package com.signalX

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

data class RecentImage(
    val uri: Uri
)

class RecentImagesAdapter(
    private val items: MutableList<RecentImage>,
    private val onToggle: (RecentImage) -> Unit
) : RecyclerView.Adapter<RecentImagesAdapter.VH>() {

    // Selection order rakha jaata hai taaki badge par sahi number dikhe
    private val selectedOrder = mutableListOf<Uri>()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.ivRecentImage)
        val badge: TextView = v.findViewById(R.id.tvSelectionBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {

        val view =
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_recent_image, parent, false)

        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {

        val item = items[position]

        Glide.with(holder.img.context)
            .load(item.uri)
            .centerCrop()
            .into(holder.img)

        val selectedIndex = selectedOrder.indexOf(item.uri)

        if (selectedIndex >= 0) {
            holder.badge.visibility = View.VISIBLE
            holder.badge.text = (selectedIndex + 1).toString()
        } else {
            holder.badge.visibility = View.GONE
        }

        holder.itemView.setOnClickListener {
            onToggle(item)
        }
    }

    override fun getItemCount(): Int = items.size

    fun update(newList: List<RecentImage>) {
        items.clear()
        items.addAll(newList)
        notifyDataSetChanged()
    }

    /** AttachmentPanelView har selection-change par isse call karega */
    fun updateSelection(selectedUris: List<Uri>) {
        selectedOrder.clear()
        selectedOrder.addAll(selectedUris)
        notifyDataSetChanged()
    }
}