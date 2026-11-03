package com.signalX

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class LockedSearchActivity : AppCompatActivity() {

    private lateinit var etSearch: EditText
    private lateinit var rvResults: RecyclerView
    private lateinit var tvNoResults: TextView
    private lateinit var cbAlsoShowNormal: CheckBox
    private lateinit var cbContainer: View
    private lateinit var adapter: SearchGroupAdapter
    private var lastOutcome: SearchHelper.SearchOutcome? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_locked_search)

        etSearch = findViewById(R.id.etGlobalSearch)
        rvResults = findViewById(R.id.rvResults)
        tvNoResults = findViewById(R.id.tvSearchNoResults)
        cbAlsoShowNormal = findViewById(R.id.cbAlsoShowNormal)
        cbContainer = findViewById(R.id.cbContainer)

        findViewById<TextView>(R.id.btnBackSearch).setOnClickListener { finish() }

        rvResults.layoutManager = LinearLayoutManager(this)

        adapter = SearchGroupAdapter(
            groups = mutableListOf(),
            onRowJumpClick = { group -> jumpToChat(group, group.messageHits.firstOrNull(), enableHighlight = false) },
            onHighlightClick = { group -> jumpToChat(group, group.messageHits.firstOrNull(), enableHighlight = true) },
            onMatchClick = { group, hit -> jumpToChat(group, hit, enableHighlight = false) },
            showExpandIcon = true
        )
        rvResults.adapter = adapter

        etSearch.requestFocus()

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                runSearch(s.toString())
            }
        })

        cbAlsoShowNormal.setOnCheckedChangeListener { _, _ ->
            runSearch(etSearch.text.toString())
        }

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(etSearch.windowToken, 0)
                true
            } else {
                false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LockedZoneGuard.checkAccessOnResume(this)
    }

    private fun runSearch(rawInput: String) {
        if (rawInput.isBlank()) {
            lastOutcome = null
            adapter.updateList(emptyList())
            tvNoResults.visibility = View.GONE
            cbContainer.visibility = View.GONE
            return
        }

        val outcome = SearchHelper.search(
            context = this,
            rawInput = rawInput,
            locations = setOf(ChatLocation.LOCKED),
            includeNormal = cbAlsoShowNormal.isChecked,
            fallbackToNormalSearch = true
        )
        lastOutcome = outcome

        val hasValidPassword = outcome.lockedGroups.isNotEmpty() && rawInput.contains('/')
        if (hasValidPassword) {
            cbContainer.visibility = View.VISIBLE
        } else {
            cbContainer.visibility = View.GONE
            cbAlsoShowNormal.isChecked = false
        }

        val finalList = if (hasValidPassword) {
            SearchHelper.mergeGroupsByChat(outcome.lockedGroups, outcome.normalGroups)
        } else {
            outcome.normalGroups
        }

        adapter.updateList(finalList)
        tvNoResults.visibility = if (finalList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun jumpToChat(group: ChatSearchGroup, hit: MessageSearchHit?, enableHighlight: Boolean) {
        val intent = Intent(this, ChatActivity::class.java)
        intent.putExtra(ChatActivity.EXTRA_CHAT_ID, group.chatItem.id)

        val sessionIdsToReveal: Set<String> = if (enableHighlight) {
            group.revealedSessionIds
        } else {
            hit?.sessionId?.let { setOf(it) } ?: emptySet()
        }

        if (sessionIdsToReveal.isNotEmpty()) {
            intent.putStringArrayListExtra(ChatActivity.EXTRA_JUMP_SESSION_IDS, ArrayList<String>(sessionIdsToReveal))
        }

        if (hit != null) {
            intent.putExtra(ChatActivity.EXTRA_JUMP_MESSAGE_ID, hit.message.id)
            // FIX: Highlight query SIRF Find All (enableHighlight=true) par bhejo
            if (enableHighlight) {
                intent.putExtra(ChatActivity.EXTRA_HIGHLIGHT_QUERY, lastOutcome?.effectiveQuery ?: "")
            }
        }

        startActivity(intent)
        finish()
    }
}