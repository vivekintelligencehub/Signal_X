package com.signalX

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment

class ConnectionSettingsFragment : Fragment() {

    // ======================================================
    // PERFORMANCE
    // ======================================================

    private lateinit var performanceLow: View
    private lateinit var performanceBalanced: View
    private lateinit var performanceHigh: View
    private lateinit var performanceInfo: TextView


    // ======================================================
    // STAR / MESH
    // ======================================================

    private lateinit var starDirectConnection: View
    private lateinit var meshConnection: View


    // ======================================================
    // CONNECTION TYPES
    // ======================================================

    private lateinit var connectionLan: View
    private lateinit var connectionHotspot: View
    private lateinit var connectionWifiP2p: View
    private lateinit var connectionBluetooth: View
    private lateinit var connectionNan: View


    // ======================================================
    // LAN DETAILS
    // ======================================================

    private lateinit var lanDetailsContainer: View
    private lateinit var lanOwner: View
    private lateinit var lanClient: View


    // ======================================================
    // HOTSPOT + WIFI DETAILS
    // ======================================================

    private lateinit var hotspotDetailsContainer: View
    private lateinit var hotspotOwner: View
    private lateinit var hotspotClient: View


    // ======================================================
    // SCAN BOX
    // ======================================================

    private lateinit var scanBoxContainer: View
    private lateinit var tvScanTitle: TextView
    private lateinit var btnRefresh: ImageView
    private lateinit var btnExpand: ImageView


    // ======================================================
    // SYNC NAME
    // ======================================================

    private lateinit var btnSyncName: View
    private lateinit var syncTraceView: SyncTraceView
    private lateinit var etConnectionName: EditText

    private var lastSyncedName: String? = null
    private var isSyncing = false

    // Auto-sync timer
    private val syncHandler = Handler(Looper.getMainLooper())
    private var autoSyncRunnable: Runnable? = null


    // ======================================================
    // VIEW CREATE
    // ======================================================

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        return inflater.inflate(
            R.layout.fragment_connection_settings,
            container,
            false
        )
    }


    // ======================================================
    // VIEW CREATED
    // ======================================================

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)


        // ==================================================
        // PERFORMANCE
        // ==================================================

        performanceLow =
            view.findViewById(R.id.performanceLow)

        performanceBalanced =
            view.findViewById(R.id.performanceBalanced)

        performanceHigh =
            view.findViewById(R.id.performanceHigh)

        performanceInfo =
            view.findViewById(R.id.tvPerformanceInfo)


        selectPerformance("HIGH")


        performanceLow.setOnClickListener {
            selectPerformance("LOW")
        }

        performanceBalanced.setOnClickListener {
            selectPerformance("BALANCED")
        }

        performanceHigh.setOnClickListener {
            selectPerformance("HIGH")
        }


        // ==================================================
        // PERFORMANCE INFO
        // ==================================================

        view.findViewById<TextView>(
            R.id.infoLow
        ).setOnClickListener {

            showInfo(
                R.layout.performance_low_info
            )
        }


        view.findViewById<TextView>(
            R.id.infoBalanced
        ).setOnClickListener {

            showInfo(
                R.layout.performance_balanced_info
            )
        }


        view.findViewById<TextView>(
            R.id.infoHigh
        ).setOnClickListener {

            showInfo(
                R.layout.performance_high_info
            )
        }


        // ==================================================
        // STAR / MESH
        // ==================================================

        starDirectConnection =
            view.findViewById(R.id.starDirectConnection)

        meshConnection =
            view.findViewById(R.id.meshConnection)


        // ==================================================
        // CONNECTION TYPES
        // ==================================================

        connectionLan =
            view.findViewById(R.id.connectionLan)

        connectionHotspot =
            view.findViewById(R.id.connectionHotspot)

        connectionWifiP2p =
            view.findViewById(R.id.connectionWifiP2p)

        connectionBluetooth =
            view.findViewById(R.id.connectionBluetooth)

        connectionNan =
            view.findViewById(R.id.connectionNan)


        // ==================================================
        // LAN DETAILS
        // ==================================================

        lanDetailsContainer =
            view.findViewById(R.id.lanDetailsContainer)

        lanOwner =
            view.findViewById(R.id.lanOwner)

        lanClient =
            view.findViewById(R.id.lanClient)


        // ==================================================
        // HOTSPOT + WIFI DETAILS
        // ==================================================

        hotspotDetailsContainer =
            view.findViewById(R.id.hotspotDetailsContainer)

        hotspotOwner =
            view.findViewById(R.id.hotspotOwner)

        hotspotClient =
            view.findViewById(R.id.hotspotClient)


        // ==================================================
        // SCAN BOX
        // ==================================================

        scanBoxContainer =
            view.findViewById(R.id.scanBoxContainer)

        tvScanTitle =
            view.findViewById(R.id.tvScanTitle)

        btnRefresh =
            view.findViewById(R.id.btnRefresh)

        btnExpand =
            view.findViewById(R.id.btnExpand)


        // --------------------------------------------------
        // REFRESH
        // --------------------------------------------------

        btnRefresh.setOnClickListener {

            it.animate()
                .rotationBy(1440f)
                .setDuration(2400)
                .setInterpolator(LinearInterpolator())
                .start()

            // TODO: yahan actual scan/refresh logic call karna
        }


        // --------------------------------------------------
        // EXPAND
        // --------------------------------------------------

        btnExpand.setOnClickListener {

            showScanBoxFullScreen()
        }


        // ==================================================
        // SYNC NAME
        // ==================================================

        btnSyncName =
            view.findViewById(R.id.btnSyncName)

        syncTraceView =
            view.findViewById(R.id.syncTraceView)

        etConnectionName =
            view.findViewById(R.id.etConnectionName)
        
        etConnectionName =
            view.findViewById(R.id.etConnectionName)

        // Pehle se sync ho chuka naam ho to Name field mein wapas dikha do

        val prefs = requireContext().getSharedPreferences("signalx_prefs", Context.MODE_PRIVATE)
        val existingSyncedName = prefs.getString("synced_name", null)

        if (!existingSyncedName.isNullOrBlank()) {
            etConnectionName.setText(existingSyncedName)
            lastSyncedName = existingSyncedName
        }


        // --------------------------------------------------
        // LOAD PREVIOUSLY SYNCED NAME
        // --------------------------------------------------

        lastSyncedName =
            requireContext()
                .getSharedPreferences(
                    "signalx_prefs",
                    Context.MODE_PRIVATE
                )
                .getString(
                    "synced_name",
                    null
                )


        // --------------------------------------------------
        // INITIAL BUTTON STATE
        // --------------------------------------------------

        refreshSyncButtonState()


        // --------------------------------------------------
        // NAME CHANGE LISTENER
        // --------------------------------------------------

        etConnectionName.addTextChangedListener(
            object : TextWatcher {

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }


                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                }


                override fun afterTextChanged(
                    s: Editable?
                ) {

                    refreshSyncButtonState()
                }
            }
        )


        // --------------------------------------------------
        // MANUAL SYNC BUTTON
        // --------------------------------------------------

        btnSyncName.setOnClickListener {

            startNameSync()

            // TODO: yahan actual "sync name" wala logic call karna
        }


        // ==================================================
        // DEFAULT MODE = STAR
        // ==================================================

        selectConnectionMode("STAR")


        starDirectConnection.setOnClickListener {

            selectConnectionMode("STAR")
        }


        meshConnection.setOnClickListener {

            selectConnectionMode("MESH")
        }


        // ==================================================
        // CONNECTION TYPES
        // ==================================================

        connectionLan.setOnClickListener {

            selectConnectionType("LAN")
        }


        connectionHotspot.setOnClickListener {

            selectConnectionType("HOTSPOT")
        }


        connectionWifiP2p.setOnClickListener {

            selectConnectionType("WIFI_P2P")
        }


        connectionBluetooth.setOnClickListener {

            selectConnectionType("BLUETOOTH")
        }


        connectionNan.setOnClickListener {

            selectConnectionType("NAN")
        }


        // ==================================================
        // LAN OWNER / CLIENT
        // ==================================================

        lanOwner.setOnClickListener {

            selectLanRole("OWNER")
        }


        lanClient.setOnClickListener {

            selectLanRole("CLIENT")
        }


        // ==================================================
        // HOTSPOT OWNER / CLIENT
        // ==================================================

        hotspotOwner.setOnClickListener {

            selectHotspotRole("OWNER")
        }


        hotspotClient.setOnClickListener {

            selectHotspotRole("CLIENT")
        }


        // ==================================================
        // CONNECTION INFO BUTTONS
        // ==================================================

        view.findViewById<TextView>(
            R.id.infoConnectionLan
        ).setOnClickListener {

            showInfo(
                R.layout.connection_lan_info
            )
        }


        view.findViewById<TextView>(
            R.id.infoConnectionHotspot
        ).setOnClickListener {

            showInfo(
                R.layout.connection_hotspot_info
            )
        }


        view.findViewById<TextView>(
            R.id.infoConnectionWifiP2p
        ).setOnClickListener {

            showInfo(
                R.layout.connection_wifi_p2p_info
            )
        }


        view.findViewById<TextView>(
            R.id.infoConnectionBluetooth
        ).setOnClickListener {

            showInfo(
                R.layout.connection_bluetooth_info
            )
        }


        view.findViewById<TextView>(
            R.id.infoConnectionNan
        ).setOnClickListener {

            showInfo(
                R.layout.connection_nan_info
            )
        }
    }


    // ======================================================
    // PERFORMANCE SELECTION
    // ======================================================

    private fun selectPerformance(mode: String) {

        performanceLow.setBackgroundResource(
            R.drawable.bg_performance_normal
        )

        performanceBalanced.setBackgroundResource(
            R.drawable.bg_performance_normal
        )

        performanceHigh.setBackgroundResource(
            R.drawable.bg_performance_normal
        )


        when (mode) {

            "LOW" -> {

                performanceLow.setBackgroundResource(
                    R.drawable.bg_performance_selected
                )

                performanceInfo.text =
                    "battery saver"
            }


            "BALANCED" -> {

                performanceBalanced.setBackgroundResource(
                    R.drawable.bg_performance_selected
                )

                performanceInfo.text =
                    "superfast"
            }


            "HIGH" -> {

                performanceHigh.setBackgroundResource(
                    R.drawable.bg_performance_selected
                )

                performanceInfo.text =
                    "superfast + logical switching + backup connection"
            }
        }
    }


    // ======================================================
    // STAR / MESH SELECTION
    // ======================================================

    private fun selectConnectionMode(mode: String) {

        starDirectConnection.setBackgroundResource(
            R.drawable.bg_performance_normal
        )

        meshConnection.setBackgroundResource(
            R.drawable.bg_performance_normal
        )


        if (mode == "STAR") {

            starDirectConnection.setBackgroundResource(
                R.drawable.bg_performance_selected
            )


            connectionLan.visibility =
                View.VISIBLE

            connectionHotspot.visibility =
                View.VISIBLE

            connectionWifiP2p.visibility =
                View.VISIBLE

            connectionBluetooth.visibility =
                View.VISIBLE

            connectionNan.visibility =
                View.GONE


            selectConnectionType("LAN")

        } else {

            meshConnection.setBackgroundResource(
                R.drawable.bg_performance_selected
            )


            connectionLan.visibility =
                View.GONE

            connectionHotspot.visibility =
                View.GONE

            connectionWifiP2p.visibility =
                View.GONE

            connectionBluetooth.visibility =
                View.GONE

            connectionNan.visibility =
                View.VISIBLE


            selectConnectionType("NAN")
        }
    }


    // ======================================================
    // CONNECTION TYPE SELECTION
    // ======================================================

    private fun selectConnectionType(type: String) {

        connectionLan.setBackgroundResource(
            R.drawable.bg_performance_normal
        )

        connectionHotspot.setBackgroundResource(
            R.drawable.bg_performance_normal
        )

        connectionWifiP2p.setBackgroundResource(
            R.drawable.bg_performance_normal
        )

        connectionBluetooth.setBackgroundResource(
            R.drawable.bg_performance_normal
        )

        connectionNan.setBackgroundResource(
            R.drawable.bg_performance_normal
        )


        lanDetailsContainer.visibility =
            View.GONE

        hotspotDetailsContainer.visibility =
            View.GONE

        scanBoxContainer.visibility =
            View.GONE


        when (type) {

            "LAN" -> {

                connectionLan.setBackgroundResource(
                    R.drawable.bg_performance_selected
                )


                lanDetailsContainer.visibility =
                    View.VISIBLE


                selectLanRole("OWNER")
            }


            "HOTSPOT" -> {

                connectionHotspot.setBackgroundResource(
                    R.drawable.bg_performance_selected
                )


                hotspotDetailsContainer.visibility =
                    View.VISIBLE


                selectHotspotRole("OWNER")
            }


            "WIFI_P2P" -> {

                connectionWifiP2p.setBackgroundResource(
                    R.drawable.bg_performance_selected
                )


                scanBoxContainer.visibility =
                    View.VISIBLE

                tvScanTitle.text =
                    "NEARBY DEVICE"
            }


            "BLUETOOTH" -> {

                connectionBluetooth.setBackgroundResource(
                    R.drawable.bg_performance_selected
                )


                scanBoxContainer.visibility =
                    View.VISIBLE

                tvScanTitle.text =
                    "NEARBY DEVICE"
            }


            "NAN" -> {

                connectionNan.setBackgroundResource(
                    R.drawable.bg_performance_selected
                )


                scanBoxContainer.visibility =
                    View.VISIBLE

                tvScanTitle.text =
                    "NEARBY DEVICE"
            }
        }
    }


    // ======================================================
    // LAN OWNER / CLIENT
    // ======================================================

    private fun selectLanRole(role: String) {

        lanOwner.setBackgroundResource(
            R.drawable.bg_performance_normal
        )

        lanClient.setBackgroundResource(
            R.drawable.bg_performance_normal
        )


        scanBoxContainer.visibility =
            View.VISIBLE


        when (role) {

            "OWNER" -> {

                lanOwner.setBackgroundResource(
                    R.drawable.bg_performance_selected
                )

                tvScanTitle.text =
                    "CONNECTED DEVICE"
            }


            "CLIENT" -> {

                lanClient.setBackgroundResource(
                    R.drawable.bg_performance_selected
                )

                tvScanTitle.text =
                    "NEARBY DEVICE"
            }
        }
    }


    // ======================================================
    // HOTSPOT OWNER / CLIENT
    // ======================================================

    private fun selectHotspotRole(role: String) {

        hotspotOwner.setBackgroundResource(
            R.drawable.bg_performance_normal
        )

        hotspotClient.setBackgroundResource(
            R.drawable.bg_performance_normal
        )


        scanBoxContainer.visibility =
            View.VISIBLE


        when (role) {

            "OWNER" -> {

                hotspotOwner.setBackgroundResource(
                    R.drawable.bg_performance_selected
                )

                tvScanTitle.text =
                    "CONNECTED DEVICE"
            }


            "CLIENT" -> {

                hotspotClient.setBackgroundResource(
                    R.drawable.bg_performance_selected
                )

                tvScanTitle.text =
                    "NEARBY DEVICE"
            }
        }
    }


    // ======================================================
    // SYNC BUTTON STATE + AUTO SYNC TIMER
    // ======================================================

    private fun refreshSyncButtonState() {

        val currentName =
            etConnectionName.text.toString()

        val lastName =
            lastSyncedName ?: ""


        // Name changed hai ya nahi
        val hasChanged =
            currentName != lastName


        updateSyncButtonState(hasChanged)


        // --------------------------------------------------
        // IMPORTANT:
        //
        // Har text change par purana timer cancel hoga.
        //
        // Iska matlab:
        // User blank field mein koi bhi text activity kare
        // to 2-second countdown turant reset/cancel ho jayega.
        // --------------------------------------------------

        autoSyncRunnable?.let {

            syncHandler.removeCallbacks(it)
        }

        autoSyncRunnable = null


        // --------------------------------------------------
        // AUTO SYNC TIMER
        //
        // Sirf tab lagega:
        //
        // Current name = blank
        // Last synced name = non-blank
        // Sync currently nahi chal raha
        // --------------------------------------------------

        if (
            currentName.isBlank() &&
            lastName.isNotBlank() &&
            !isSyncing
        ) {

            autoSyncRunnable = Runnable {

                val latestName =
                    etConnectionName.text.toString()

                val latestLastName =
                    lastSyncedName ?: ""


                // --------------------------------------------------
                // 2 seconds ke baad final safety check
                // --------------------------------------------------

                if (
                    latestName.isBlank() &&
                    latestLastName.isNotBlank() &&
                    !isSyncing
                ) {

                    startNameSync()
                }
            }


            syncHandler.postDelayed(
                autoSyncRunnable!!,
                2000L
            )
        }
    }


    // ======================================================
    // UPDATE SYNC BUTTON UI
    // ======================================================

    private fun updateSyncButtonState(
        enabled: Boolean
    ) {

        btnSyncName.isEnabled =
            enabled

        btnSyncName.alpha =
            if (enabled) 1f else 0.4f
    }


    // ======================================================
    // START NAME SYNC
    // ======================================================

    private fun startNameSync() {

        if (isSyncing) return


        // Agar manual sync ke through call hua
        // ya auto-sync ke through,
        // dono mein pending timer cancel kar do.

        autoSyncRunnable?.let {

            syncHandler.removeCallbacks(it)
        }

        autoSyncRunnable = null


        isSyncing = true


        // --------------------------------------------------
        // SAME EXISTING TRACE ANIMATION
        // --------------------------------------------------

        syncTraceView.startTraceAnimation {

            // Animation complete hone ke baad
            // current name ko synced name bana do.

            lastSyncedName =
                etConnectionName.text.toString()


            // --------------------------------------------------
            // NAME SAVE
            // Chats page bhi isi naam ko use kar sakega.
            // --------------------------------------------------

            requireContext()
                .getSharedPreferences(
                    "signalx_prefs",
                    Context.MODE_PRIVATE
                )
                .edit()
                .putString(
                    "synced_name",
                    lastSyncedName
                )
                .apply()


            // --------------------------------------------------
            // Animation ke baad thoda delay
            // --------------------------------------------------

            btnSyncName.postDelayed({

                isSyncing = false

                refreshSyncButtonState()

            }, 100L)
        }
    }


    // ======================================================
    // SCAN BOX — FULL SCREEN
    // ======================================================

    private fun showScanBoxFullScreen() {

        val dialogView =
            LayoutInflater.from(requireContext())
                .inflate(
                    R.layout.connection_scan_box,
                    null
                )


        val dialog =
            Dialog(
                requireContext(),
                android.R.style.Theme_Black_NoTitleBar_Fullscreen
            )


        dialog.setContentView(dialogView)


        dialogView.findViewById<TextView>(
            R.id.tvScanTitle
        ).text =
            tvScanTitle.text


        dialogView.findViewById<ImageView>(
            R.id.btnRefresh
        ).setOnClickListener {

            it.animate()
                .rotationBy(1440f)
                .setDuration(2400)
                .setInterpolator(LinearInterpolator())
                .start()
        }


        val btnCloseFullscreen =
            dialogView.findViewById<ImageView>(
                R.id.btnExpand
            )


        btnCloseFullscreen.setImageResource(
            R.drawable.ic_close
        )


        btnCloseFullscreen.setOnClickListener {

            dialog.dismiss()
        }


        dialog.show()
    }


    // ======================================================
    // INFORMATION DIALOG
    // ======================================================

    private fun showInfo(layoutId: Int) {

        val dialogView =
            LayoutInflater.from(requireContext())
                .inflate(
                    layoutId,
                    null
                )


        val dialog =
            AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .create()


        dialog.show()
    }


    // ======================================================
    // CLEANUP
    // ======================================================

    override fun onDestroyView() {

        autoSyncRunnable?.let {

            syncHandler.removeCallbacks(it)
        }

        autoSyncRunnable = null

        super.onDestroyView()
    }
}