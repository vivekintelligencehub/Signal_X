package com.signalX

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SimpleListActivity : AppCompatActivity(), MultiSelectHost {

    companion object {
        const val EXTRA_TITLE = "EXTRA_TITLE"
        const val EXTRA_SECURE = "EXTRA_SECURE"
        const val EXTRA_LOCATION = "EXTRA_LOCATION"
    }

    private var isSecurePage = false
    private var isLockedLocation = false

    override lateinit var multiSelectController: MultiSelectHeaderController


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        isSecurePage = intent.getBooleanExtra(EXTRA_SECURE, false)

        val location =
            ChatLocation.valueOf(intent.getStringExtra(EXTRA_LOCATION) ?: ChatLocation.ARCHIVED.name)

        isLockedLocation = (location == ChatLocation.LOCKED)

        if (isSecurePage) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        setContentView(R.layout.activity_simple_list)

        val pageTitle = intent.getStringExtra(EXTRA_TITLE) ?: "Chats"
        findViewById<TextView>(R.id.tvPageTitle).text = pageTitle

        findViewById<TextView>(R.id.btnBack).setOnClickListener {
            finish()
        }

        findViewById<TextView>(R.id.btnPageMenu).setOnClickListener {

            val comingSoonIntent = Intent(this, ComingSoonActivity::class.java)
            comingSoonIntent.putExtra(ComingSoonActivity.EXTRA_SECURE, isSecurePage)
            comingSoonIntent.putExtra(ComingSoonActivity.EXTRA_TITLE, pageTitle)

            startActivity(comingSoonIntent)
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


        val showSearchBar = (location == ChatLocation.LOCKED)

        if (supportFragmentManager.findFragmentById(R.id.chatListContainer) == null) {

            supportFragmentManager.beginTransaction()
                .replace(
                    R.id.chatListContainer,
                    ChatListFragment.newInstance(location, showUniversalTab = false, showSearchBar = showSearchBar)
                )
                .commit()
        }
    }


    override fun onResume() {
        super.onResume()

        if (isLockedLocation) {
            LockedZoneGuard.checkAccessOnResume(this)
        }
    }
}