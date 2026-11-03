package com.signalX

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2

class MainActivity : AppCompatActivity(), MultiSelectHost {

    private lateinit var tvLogsContent: TextView
    private lateinit var btnDeleteLogs: ImageView
    private lateinit var btnExpandLogs: ImageView

    override lateinit var multiSelectController: MultiSelectHeaderController


    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Permission mile ya na mile, service start karne ki koshish karte hain —
        // agar permission nahi mili, notification bas dikhegi nahi, app crash nahi hoga

        startBackgroundService()
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)


        val tvName = findViewById<TextView>(R.id.tvAppName)

        val text = "SignalX"
        val spannable = SpannableString(text)

        spannable.setSpan(
            ForegroundColorSpan(Color.parseColor("#00A859")),
            0, 6, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        spannable.setSpan(
            ForegroundColorSpan(Color.parseColor("#FF0000")),
            6, 7, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        tvName.text = spannable as CharSequence


        findViewById<TextView>(R.id.btnNotification).setOnClickListener {
            startActivity(Intent(this, NotificationActivity::class.java))
        }

        findViewById<TextView>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }


        multiSelectController = MultiSelectHeaderController(
            context = this,
            normalHeader = findViewById(R.id.normalHeader),
            multiSelectHeader = findViewById(R.id.multiSelectHeader),
            tvSelectedCount = findViewById(R.id.tvSelectedCount),
            btnSelectAll = findViewById(R.id.btnSelectAll),
            actionsContainer = findViewById(R.id.multiSelectActionsContainer),
            btnExit = findViewById(R.id.btnExitMultiSelect)
        )


        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        viewPager.adapter = ViewPagerAdapter(this)


        tvLogsContent = findViewById(R.id.tv_logs_content)
        btnDeleteLogs = findViewById(R.id.btnDeleteLogs)
        btnExpandLogs = findViewById(R.id.btnExpandLogs)

        btnDeleteLogs.setOnClickListener { tvLogsContent.text = "" }
        btnExpandLogs.setOnClickListener { showLogsFullScreen() }


        requestNotificationPermissionAndStartService()
    }


    // ======================================================
    // BACKGROUND ACTIVE SERVICE
    // ======================================================

    private fun requestNotificationPermissionAndStartService() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            val hasPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                startBackgroundService()
            } else {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

        } else {

            startBackgroundService()
        }
    }


    private fun startBackgroundService() {

        val serviceIntent = Intent(this, BackgroundActiveService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }


    // ======================================================
    // LOGS BOX — FULL SCREEN
    // ======================================================

    private fun showLogsFullScreen() {

        val view = LayoutInflater.from(this).inflate(R.layout.connection_logs, null)
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        dialog.setContentView(view)

        val fullScreenLogsContent = view.findViewById<TextView>(R.id.tv_logs_content)
        fullScreenLogsContent.text = tvLogsContent.text

        view.findViewById<ImageView>(R.id.btnDeleteLogs).setOnClickListener {
            fullScreenLogsContent.text = ""
            tvLogsContent.text = ""
        }

        val btnCloseFullscreen = view.findViewById<ImageView>(R.id.btnExpandLogs)
        btnCloseFullscreen.setImageResource(R.drawable.ic_close)
        btnCloseFullscreen.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }
}
