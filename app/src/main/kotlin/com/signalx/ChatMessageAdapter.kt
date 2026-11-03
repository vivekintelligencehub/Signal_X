package com.signalX

import com.bumptech.glide.Glide
import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class ChatMessageAdapter(
    private val rows: MutableList<ChatListRow>,
    private val onBannerClick: (ChatSession) -> Unit,
    private val onCloseRevealedSessionClick: (ChatSession) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_MESSAGE = 0
        private const val TYPE_BANNER = 1
        private const val TYPE_REVEALED_BLOCK = 2
    }
    
    private val revealedSessionIdsAlreadyAnimated = mutableSetOf<String>()

    // Bulk mode (Find All / Close All / Wapas aana) ke liye
    var isBulkReveal: Boolean = false

    // Opening Animation with Delay (Specific open ke liye)
    private fun playExpandAnimation(view: View) {

        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(view.width.takeIf { it > 0 } ?: 1000, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.UNSPECIFIED
        )

        val targetHeight = view.measuredHeight

        view.layoutParams.height = 0
        view.visibility = View.VISIBLE
        view.requestLayout()

        val animator = ValueAnimator.ofInt(0, targetHeight)

        animator.duration = 320L
        animator.startDelay = 450L

        animator.addUpdateListener { animation ->
            view.layoutParams.height = animation.animatedValue as Int
            view.requestLayout()
        }

        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                view.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                view.requestLayout()
            }
        })

        animator.start()
    }
    
    private fun buildImageGrid(
    container: android.widget.FrameLayout,
    uris: List<String>,
    onClick: (Int) -> Unit
) {
    container.removeAllViews()

    val density = container.resources.displayMetrics.density
    val sizePx = (220 * density).toInt()           // container 220dp

    // ============================================================
    // GAP MANAGEMENT: Yahan gap change karo (dp mein)
    // - 2 = bahut patla gap
    // - 4 = WhatsApp jaisa (default)
    // - 6 = thoda zyada
    // - 8 = aur zyada
    // ============================================================
    val gapDp = 4
    val gapPx = (gapDp * density).toInt()

    val halfPx = (sizePx - gapPx) / 2

    fun makeImage(uri: String, w: Int, h: Int, left: Int, top: Int, idx: Int) {
        val iv = android.widget.ImageView(container.context).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(w, h).apply {
                leftMargin = left
                topMargin = top
            }
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        }
        Glide.with(iv).load(android.net.Uri.parse(uri)).centerCrop().into(iv)
        iv.setOnClickListener { onClick(idx) }
        container.addView(iv)
    }

    when (uris.size) {

        2 -> {
            // Side by side, each half width, full height
            makeImage(uris[0], halfPx, sizePx, 0, 0, 0)
            makeImage(uris[1], halfPx, sizePx, halfPx + gapPx, 0, 1)
        }

        3 -> {
            // Left: 1 big (half width, full height)
            // Right: 2 stacked (half width, half height each)
            makeImage(uris[0], halfPx, sizePx, 0, 0, 0)
            makeImage(uris[1], halfPx, halfPx, halfPx + gapPx, 0, 1)
            makeImage(uris[2], halfPx, halfPx, halfPx + gapPx, halfPx + gapPx, 2)
        }

        4 -> {
            // 2x2 grid
            makeImage(uris[0], halfPx, halfPx, 0, 0, 0)
            makeImage(uris[1], halfPx, halfPx, halfPx + gapPx, 0, 1)
            makeImage(uris[2], halfPx, halfPx, 0, halfPx + gapPx, 2)
            makeImage(uris[3], halfPx, halfPx, halfPx + gapPx, halfPx + gapPx, 3)
        }

        else -> {
            // 5+ images: 2x2 grid with +N overlay on last
            makeImage(uris[0], halfPx, halfPx, 0, 0, 0)
            makeImage(uris[1], halfPx, halfPx, halfPx + gapPx, 0, 1)
            makeImage(uris[2], halfPx, halfPx, 0, halfPx + gapPx, 2)
            makeImage(uris[3], halfPx, halfPx, halfPx + gapPx, halfPx + gapPx, 3)

            // +N overlay
            val overlay = android.widget.TextView(container.context).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(halfPx, halfPx).apply {
                    leftMargin = halfPx + gapPx
                    topMargin = halfPx + gapPx
                }
                text = "+${uris.size - 3}"
                setBackgroundColor(0x99000000.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 20f
                gravity = android.view.Gravity.CENTER
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            overlay.setOnClickListener { onClick(3) }
            container.addView(overlay)
        }
    }
}

    // Closing Animation with State Reset (Specific close ke liye)
    private fun playCollapseAnimation(view: View, session: ChatSession) {

        val currentHeight = view.height

        val animator = ValueAnimator.ofInt(currentHeight, 0)

        animator.duration = 300L
        animator.startDelay = 0L

        animator.addUpdateListener { animation ->
            view.layoutParams.height = animation.animatedValue as Int
            view.requestLayout()
        }

        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                view.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                revealedSessionIdsAlreadyAnimated.remove(session.id)
                onCloseRevealedSessionClick(session)
            }
        })

        animator.start()
    }

    var highlightedMessageIds: Set<String> = emptySet()
    var currentHighlightMessageId: String? = null

    inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    val bubbleContainer: View = itemView.findViewById(R.id.bubbleContainer)
    val tvText: TextView = itemView.findViewById(R.id.tvMessageText)
    val tvTime: TextView = itemView.findViewById(R.id.tvMessageTime)
    val ivSingleImage: ImageView = itemView.findViewById(R.id.ivSingleImage)
    val imageGridContainer: android.widget.FrameLayout = itemView.findViewById(R.id.imageGridContainer)
    val tvCaption: TextView = itemView.findViewById(R.id.tvMessageCaption)
}

    inner class BannerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvBanner: TextView = itemView.findViewById(R.id.tvBannerText)
    }

    inner class RevealedBlockViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val container: LinearLayout = itemView.findViewById(R.id.revealedMessagesContainer)
        val btnClose: TextView = itemView.findViewById(R.id.btnCloseRevealedSession)
    }


    override fun getItemViewType(position: Int): Int =
        when (rows[position]) {
            is ChatListRow.MessageRow -> TYPE_MESSAGE
            is ChatListRow.SessionBannerRow -> TYPE_BANNER
            is ChatListRow.RevealedSessionRow -> TYPE_REVEALED_BLOCK
        }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {

        return when (viewType) {

            TYPE_MESSAGE -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message_bubble, parent, false)
                MessageViewHolder(view)
            }

            TYPE_BANNER -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_session_banner, parent, false)
                BannerViewHolder(view)
            }

            else -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_revealed_session_block, parent, false)
                RevealedBlockViewHolder(view)
            }
        }
    }


    override fun getItemCount(): Int = rows.size


    private fun applyBubbleHighlight(bubbleContainer: View, messageId: String) {

        val drawableRes = when {
            messageId == currentHighlightMessageId -> R.drawable.bg_message_bubble_highlight_current
            highlightedMessageIds.contains(messageId) -> R.drawable.bg_message_bubble_highlight
            else -> R.drawable.bg_message_bubble
        }

        bubbleContainer.background = ContextCompat.getDrawable(bubbleContainer.context, drawableRes)
    }


    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {

        when (val row = rows[position]) {

           is ChatListRow.MessageRow -> {
    val h = holder as MessageViewHolder
    h.tvTime.text = DateFormat.format("hh:mm a", row.message.timestamp)
    applyBubbleHighlight(h.bubbleContainer, row.message.id)

    val text = row.message.text
    if (text.startsWith("IMG::")) {
        val uriStr = text.removePrefix("IMG::")
        val uris = uriStr.split("||").filter { it.isNotBlank() }

        h.tvText.visibility = View.GONE

        if (uris.size == 1) {
            h.ivSingleImage.visibility = View.VISIBLE
            h.imageGridContainer.visibility = View.GONE
            Glide.with(h.ivSingleImage.context)
                .load(android.net.Uri.parse(uris[0]))
                .centerCrop()
                .into(h.ivSingleImage)
            h.ivSingleImage.setOnClickListener {
                ImageViewActivity.start(h.itemView.context, uris, 0)
            }
        } else {
            h.ivSingleImage.visibility = View.GONE
            h.imageGridContainer.visibility = View.VISIBLE
            buildImageGrid(h.imageGridContainer, uris) { index ->
                ImageViewActivity.start(h.itemView.context, uris, index)
            }
        }

        val caption = row.message.caption
        if (!caption.isNullOrBlank()) {
            h.tvCaption.visibility = View.VISIBLE
            h.tvCaption.text = caption
        } else {
            h.tvCaption.visibility = View.GONE
        }
    } else {
        h.ivSingleImage.visibility = View.GONE
        h.imageGridContainer.visibility = View.GONE
        h.tvCaption.visibility = View.GONE
        h.tvText.visibility = View.VISIBLE
        h.tvText.text = text
    }
}

            is ChatListRow.SessionBannerRow -> {

                val h = holder as BannerViewHolder
                val lockTime = row.session.lockTime

                val timeText =
                    if (lockTime != null) DateFormat.format("dd MMM, hh:mm a", lockTime)
                    else "Locked"

                h.tvBanner.text = "🔒  Session Locked — $timeText"

                h.itemView.setOnClickListener {
                    onBannerClick(row.session)
                }
            }

            is ChatListRow.RevealedSessionRow -> {

                val h = holder as RevealedBlockViewHolder
                h.container.removeAllViews()

                for (message in row.messages) {

                    val bubbleView = LayoutInflater.from(h.container.context)
                        .inflate(R.layout.item_message_bubble, h.container, false)

                    bubbleView.findViewById<TextView>(R.id.tvMessageText).text = message.text
                    bubbleView.findViewById<TextView>(R.id.tvMessageTime).text =
                        DateFormat.format("hh:mm a", message.timestamp)

                    applyBubbleHighlight(bubbleView.findViewById(R.id.bubbleContainer), message.id)

                    h.container.addView(bubbleView)
                }

                // FIX: Specific close ke waqt isBulkReveal ko force false karo, chahe bulk pehle hua ho
                h.btnClose.setOnClickListener {
                    // REMOVED: isBulkReveal = false  <-- Yahan se hataya hai, isi se bug aata tha
                    playCollapseAnimation(h.itemView, row.session)
                }

                // Opening Animation (Sirf specific open ke waqt)
                // Agar bulk hai, toh animation skip karo, warna chalao
                if (!isBulkReveal) {
                    if (!revealedSessionIdsAlreadyAnimated.contains(row.session.id)) {
                        revealedSessionIdsAlreadyAnimated.add(row.session.id)
                        playExpandAnimation(h.itemView)
                    }
                }
            }
        }    
    }


    fun updateRows(newRows: List<ChatListRow>) {
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
    }


    fun getPositionForMessageId(messageId: String): Int {

        for (i in rows.indices) {

            when (val row = rows[i]) {

                is ChatListRow.MessageRow ->
                    if (row.message.id == messageId) return i

                is ChatListRow.RevealedSessionRow ->
                    if (row.messages.any { it.id == messageId }) return i

                else -> {}
            }
        }

        return -1
    }
}