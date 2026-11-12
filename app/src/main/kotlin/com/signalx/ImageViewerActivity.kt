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
            // Naye page par scale bilkul reset karo
            holder.iv.scaleX = 1f
            holder.iv.scaleY = 1f

            Glide.with(holder.iv.context)
                .load(Uri.parse(uris[position]))
                .into(holder.iv)

            // -------------------------------------------------------
            // Pinch-to-zoom
            // -------------------------------------------------------
            var scaleFactor = 1f

            val scaleDetector = ScaleGestureDetector(
                holder.iv.context,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        scaleFactor *= detector.scaleFactor
                        scaleFactor = scaleFactor.coerceIn(1f, 5f)
                        holder.iv.scaleX = scaleFactor
                        holder.iv.scaleY = scaleFactor
                        return true
                    }
                }
            )

            // isClickable = true zaroori hai — iske bina ImageView touch consume
            // nahi karta aur ScaleGestureDetector ko events milte hi nahi.
            holder.iv.isClickable = true

            holder.iv.setOnTouchListener { v, event ->
                // Pehle ScaleGestureDetector ko dena zaroori hai
                scaleDetector.onTouchEvent(event)

                when (event.actionMasked) {
                    // Doosri ungli neeche aayi → TURANT ViewPager2 ko rok do
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    // Sab ungliyaan uthin aur zoom nahi hai → swipe wapas chalu
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        if (scaleFactor <= 1.05f) {
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                }

                // Zoom hua hua ho to single finger bhi ViewPager2 ko nahi milni chahiye
                if (scaleFactor > 1.05f) {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }

                // 2+ ungliyaan ya zoom → consume karo (true)
                // 1 ungli + no zoom → ViewPager2 ko swipe karne do (false)
                event.pointerCount >= 2 || scaleFactor > 1.05f
            }
        }
    }
}
