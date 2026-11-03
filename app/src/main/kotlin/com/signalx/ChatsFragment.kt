package com.signalx

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

class ChatsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        return inflater.inflate(R.layout.fragment_chats, container, false)
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.rowMainSearch).setOnClickListener {

            startActivity(Intent(requireContext(), SearchActivity::class.java))
        }


        view.findViewById<View>(R.id.rowArchived).setOnClickListener {

            val intent = Intent(requireContext(), SimpleListActivity::class.java)
            intent.putExtra(SimpleListActivity.EXTRA_TITLE, "Archived")
            intent.putExtra(SimpleListActivity.EXTRA_SECURE, false)
            intent.putExtra(SimpleListActivity.EXTRA_LOCATION, ChatLocation.ARCHIVED.name)

            startActivity(intent)
        }


        view.findViewById<View>(R.id.rowLockedChats).setOnClickListener {

            val prefs = requireContext().getSharedPreferences("signalx_prefs", Context.MODE_PRIVATE)
            val hasPassword = prefs.contains("locked_chats_pass_hash")

            val intent =
                if (hasPassword) Intent(requireContext(), UnlockLockedChatsActivity::class.java)
                else Intent(requireContext(), CreatePasswordActivity::class.java)

            startActivity(intent)
        }


        if (childFragmentManager.findFragmentById(R.id.chatListContainer) == null) {

            childFragmentManager.beginTransaction()
                .replace(
                    R.id.chatListContainer,
                    ChatListFragment.newInstance(ChatLocation.NORMAL, showUniversalTab = true, showSearchBar = false)
                )
                .commit()
        }
    }
}