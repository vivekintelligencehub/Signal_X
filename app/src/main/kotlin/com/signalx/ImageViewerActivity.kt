package com.signalX

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
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
        private const val EXTRA_URIS = "extra_uris"
        private const val EXTRA_INDEX = "extra_index"

        fun start(context: Context, uris: List<String>, startIndex: Int) {
            val i = Intent(context, ImageViewActivity::class.java)
            i.putStringArrayListExtra(EXTRA_URIS, ArrayList(uris))
            i.putExtra(EXTRA_INDEX, startIndex)
            context.startActivity(i)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_viewer)

        val uris = intent.getStringArrayListExtra(EXTRA_URIS) ?: emptyList()
        val startIndex = intent.getIntExtra(EXTRA_INDEX, 0)

        val vp = findViewById<ViewPager2>(R.id.vpImages)
        val counter = findViewById<TextView>(R.id.tvCounter)
        val btnClose = findViewById<View>(R.id.btnClose)

        vp.adapter = ImagePagerAdapter(uris)
        vp.setCurrentItem(startIndex, false)
        counter.text = "${startIndex + 1} / ${uris.size}"

        vp.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                counter.text = "${position + 1} / ${uris.size}"
            }
        })

        btnClose.setOnClickListener { finish() }
    }

    private class ImagePagerAdapter(private val uris: List<String>) :
        RecyclerView.Adapter<ImagePagerAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val iv: ImageView = v.findViewById(R.id.ivFullImage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_image_viewer_page, parent, false)
            return VH(v)
        }

        override fun getItemCount() = uris.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            Glide.with(holder.iv.context)
                .load(Uri.parse(uris[position]))
                .into(holder.iv)
        }
    }
}