package com.signalx

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

        val captionsList: List<String> = if (captionRaw.contains(AttachmentPanelView.CAP_SEPARATOR)) {
            val parts = captionRaw.split(AttachmentPanelView.CAP_SEPARATOR)
            List(uris.size) { i -> parts.getOrElse(i) { "" } }
        } else {
            List(uris.size) { captionRaw }
        }

        val vp           = findViewById<ViewPager2>(R.id.vpImages)
        val counter      = findViewById<TextView>(R.id.tvCounter)
        val btnClose     = findViewById<View>(R.id.btnClose)
        val tvCaption    = findViewById<TextView>(R.id.tvCaption)
        val btnZoomReset = findViewById<TextView>(R.id.btnZoomReset)

        // Semi-transparent circular background for the zoom-out button
        val circleBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xCC000000.toInt())
        }
        btnZoomReset.background = circleBg

        // Adapter: wire zoom-state callback so the button shows/hides
        val adapter = ImagePagerAdapter(uris) { isZoomed ->
            btnZoomReset.visibility = if (isZoomed) View.VISIBLE else View.GONE
        }
        vp.adapter = adapter

        fun showCaptionAt(position: Int) {
            val cap = captionsList.getOrElse(position) { "" }
            if (cap.isBlank()) tvCaption.visibility = View.GONE
            else { tvCaption.visibility = View.VISIBLE; tvCaption.text = cap }
        }

        vp.setCurrentItem(startIndex, false)
        counter.text = "${startIndex + 1} / ${uris.size}"
        showCaptionAt(startIndex)

        vp.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                counter.text = "${position + 1} / ${uris.size}"
                showCaptionAt(position)
                // Hide zoom-out button when page changes (new image is at 1x)
                btnZoomReset.visibility = View.GONE
            }
        })

        btnClose.setOnClickListener { finish() }

        // Zoom-out button: reset the current page's zoom to 1x
        btnZoomReset.setOnClickListener {
            val currentVH = findCurrentVH(vp)
            currentVH?.iv?.resetZoom()
            btnZoomReset.visibility = View.GONE
        }
    }

    /** Access the ViewHolder for the currently visible page. */
    private fun findCurrentVH(vp: ViewPager2): ImagePagerAdapter.VH? {
        val rv = vp.getChildAt(0) as? RecyclerView ?: return null
        return rv.findViewHolderForAdapterPosition(vp.currentItem) as? ImagePagerAdapter.VH
    }

    // -------------------------------------------------------------------------
    // Adapter
    // -------------------------------------------------------------------------
    class ImagePagerAdapter(
        private val uris: List<String>,
        private val onZoomStateChanged: (zoomed: Boolean) -> Unit
    ) : RecyclerView.Adapter<ImagePagerAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val iv: ZoomableImageView = v.findViewById(R.id.ivFullImage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context)
                .inflate(R.layout.item_image_viewer_page, parent, false))

        override fun getItemCount() = uris.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.iv.resetForNewImage()
            // Forward zoom state to the activity so it can show/hide the button
            holder.iv.onZoomStateChanged = onZoomStateChanged
            Glide.with(holder.iv.context)
                .load(Uri.parse(uris[position]))
                .into(holder.iv)
        }
    }
}
