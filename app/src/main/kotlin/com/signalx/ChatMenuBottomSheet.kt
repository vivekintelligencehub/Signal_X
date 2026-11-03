package com.signalX

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class ChatMenuBottomSheet(
    private val chatItem: ChatItem,
    private val onActionSelected: (ChatMenuAction, ChatItem) -> Unit
) : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        return inflater.inflate(R.layout.bottom_sheet_chat_menu, container, false)
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.tvSheetChatName).text = chatItem.name

        val container = view.findViewById<LinearLayout>(R.id.sheetOptionsContainer)

        val actions =
            if (chatItem.isSelf) {

                listOf(
                    ChatMenuAction.SELECT,
                    ChatMenuAction.REPLACE,
                    ChatMenuAction.ADD_TO_LIST,
                    ChatMenuAction.ADD_TO_HOME,
                    ChatMenuAction.CLEAR_CHAT
                )

            } else {

                listOf(
                    ChatMenuAction.SELECT,
                    ChatMenuAction.DELETE,
                    ChatMenuAction.REPLACE,
                    if (chatItem.isPinned) ChatMenuAction.UNPIN else ChatMenuAction.PIN,
                    if (chatItem.isBlocked) ChatMenuAction.UNBLOCK else ChatMenuAction.BLOCK,
                    ChatMenuAction.ADD_TO_LIST,
                    ChatMenuAction.VIEW_CONTACT,
                    ChatMenuAction.ADD_TO_HOME,
                    ChatMenuAction.CLEAR_CHAT
                )
            }

        for (action in actions) {

            val row = LayoutInflater.from(requireContext()).inflate(R.layout.item_sheet_option, container, false)

            row.findViewById<ImageView>(R.id.ivOptionIcon).setImageResource(action.iconRes)
            row.findViewById<TextView>(R.id.tvOptionLabel).text = action.label

            row.setOnClickListener {
                dismiss()
                onActionSelected(action, chatItem)
            }

            container.addView(row)
        }
    }
}