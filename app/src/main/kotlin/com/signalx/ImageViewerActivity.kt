package com.signalx

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide

class ImageViewActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URIS    = "extra_uris"
        private const val EXTRA_INDEX   = "extra_index"
        private const val EXTRA_CAPTION = "extra_caption"

        fun start(
            context: Context,
            uris: List<String>,
            startIndex: Int,
            caption: String? = null
        ) {
            val i = Intent(context, ImageViewActivity::class.java)
            i.putStringArrayListExtra(EXTRA_URIS, ArrayList(uris))
            i.putExtra(EXTRA_INDEX, startIndex)
            i.putExtra(EXTRA_CAPTION, caption ?: "")
            context.startActivity(i)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_viewer)

        val uris: List<String> = intent.getStringArrayListExtra(EXTRA_URIS) ?: emptyList()
        val startIndex: Int    = intent.getIntExtra(EXTRA_INDEX, 0)
        val captionRaw: String = intent.getStringExtra(EXTRA_CAPTION) ?: ""

        // Per-image captions: CAP_SEPARATOR se split karo.
        // Separator nahi hai → backward compat: same caption sab images par.
        val captionsList: List<String> = if (captionRaw.contains(AttachmentPanelView.CAP_SEPARATOR)) {
            val parts = captionRaw.split(AttachmentPanelView.CAP_SEPARATOR)
            List(uris.size) { i -> parts.getOrElse(i) { "" } }
        } else {
            List(uris.size) { captionRaw }
        }

        val vp        = findViewById<ViewPager2>(R.id.vpImages)
        val counter   = findViewById<TextView>(R.id.tvCounter)
        val btnClose  = findViewById<View>(R.id.btnClose)
        val tvCaption = findViewById<TextView>(R.id.tvCaption)

        fun showCaptionAt(position: Int) {
            val cap = captionsList.getOrElse(position) { "" }
            if (cap.isBlank()) tvCaption.visibility = View.GONE
            else { tvCaption.visibility = View.VISIBLE; tvCaption.text = cap }
        }

        vp.adapter = ImagePagerAdapter(uris)
        vp.setCurrentItem(startIndex, false)
        counter.text = "${startIndex + 1} / ${uris.size}"
        showCaptionAt(startIndex)

        vp.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                counter.text = "${position + 1} / ${uris.size}"
                showCaptionAt(position)
            }
        })

        btnClose.setOnClickListener { finish() }
    }

    private class ImagePagerAdapter(private val uris: List<String>) :
        RecyclerView.Adapter<ImagePagerAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val iv: ImageView = v.findViewById(R.id.ivFullImage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_image_viewer_page, parent, false))

        override fun getItemCount() = uris.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            // Page badlne par scale reset
            holder.iv.scaleX = 1f
            holder.iv.scaleY = 1f

            Glide.with(holder.iv.context)
                .load(Uri.parse(uris[position]))
                .into(holder.iv)

            // Pinch-to-zoom
            var scaleFactor = 1f
            val scaleDetector = ScaleGestureDetector(
                holder.iv.context,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        scaleFactor *= detector.scaleFactor
                        scaleFactor = scaleFactor.coerceIn(1f, 5f)
                        holder.iv.scaleX = scaleFactor
                        holder.iv.scaleY = scaleFactor
                        // Zoom mein ho to ViewPager2 ko swipe mat karne do
                        holder.iv.parent?.requestDisallowInterceptTouchEvent(scaleFactor > 1.05f)
                        return true
                    }
                }
            )

            holder.iv.setOnTouchListener { _, event ->
                scaleDetector.onTouchEvent(event)
                when {
                    // 2+ ungliyaan → zoom gesture
                    event.pointerCount >= 2 -> {
                        holder.iv.parent?.requestDisallowInterceptTouchEvent(true)
                        true
                    }
                    // Zoom hua hua hai → swipe mat karne do
                    scaleFactor > 1.05f -> {
                        holder.iv.parent?.requestDisallowInterceptTouchEvent(true)
                        true
                    }
                    // Normal single touch → ViewPager2 ko jaane do (swipe kaam kare)
                    else -> {
                        holder.iv.parent?.requestDisallowInterceptTouchEvent(false)
                        false
                    }
                }
            }
        }
    }
}
