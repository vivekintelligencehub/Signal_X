package com.signalX

enum class ChatMenuAction(val label: String, val iconRes: Int) {

    SELECT("Select", R.drawable.ic_multiselect),
    DELETE("Delete Chat", R.drawable.ic_delete),
    REPLACE("Replace", R.drawable.ic_replace),
    PIN("Pin", R.drawable.ic_pin),
    UNPIN("Unpin", R.drawable.ic_unpin),
    BLOCK("Block", R.drawable.ic_block),
    UNBLOCK("Unblock", R.drawable.ic_unblock),
    ADD_TO_LIST("Add to List", R.drawable.ic_add_to_list),
    VIEW_CONTACT("View Contact", R.drawable.ic_contact),
    ADD_TO_HOME("Add to Home Screen", R.drawable.ic_add_home),
    CLEAR_CHAT("Clear Chat", R.drawable.ic_clear_chat),
    CREATE_GROUP("Create Group", R.drawable.ic_create_group)
}