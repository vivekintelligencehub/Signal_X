package com.signalX

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ChatListFragment : Fragment() {

    companion object {

        private const val ARG_LOCATION = "ARG_LOCATION"
        private const val ARG_SHOW_UNIVERSAL = "ARG_SHOW_UNIVERSAL"
        private const val ARG_SHOW_SEARCH = "ARG_SHOW_SEARCH"

        fun newInstance(
            location: ChatLocation,
            showUniversalTab: Boolean,
            showSearchBar: Boolean = true
        ): ChatListFragment {

            val fragment = ChatListFragment()

            fragment.arguments = Bundle().apply {
                putString(ARG_LOCATION, location.name)
                putBoolean(ARG_SHOW_UNIVERSAL, showUniversalTab)
                putBoolean(ARG_SHOW_SEARCH, showSearchBar)
            }

            return fragment
        }
    }


    private sealed class FilterMode {
        object All : FilterMode()
        object Active : FilterMode()
        object Inactive : FilterMode()
        data class Custom(val name: String) : FilterMode()
    }

    private var currentFilterMode: FilterMode = FilterMode.All
    private val customChipViews = mutableMapOf<String, View>()
    private lateinit var filterChipsContainer: LinearLayout
    private lateinit var filterChipsScrollView: HorizontalScrollView


    private lateinit var myLocation: ChatLocation
    private var showUniversalTab = true
    private var showSearchBar = true

    private lateinit var tabDirect: View
    private lateinit var tabGroup: View
    private lateinit var tabUniversal: View
    private var currentTab = "DIRECT"

    private lateinit var filterAll: View
    private lateinit var filterActive: View
    private lateinit var filterInactive: View
    private lateinit var btnAddChat: View

    private lateinit var rvChats: RecyclerView
    private lateinit var chatsAdapter: ChatsAdapter

    private var pendingMoveItems: List<ChatItem> = emptyList()


    private val verifyPasswordLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->

        if (result.resultCode == Activity.RESULT_OK) {
            moveChats(pendingMoveItems, ChatLocation.LOCKED)
        }

        pendingMoveItems = emptyList()
    }


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        return inflater.inflate(R.layout.fragment_chat_list, container, false)
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        myLocation = ChatLocation.valueOf(arguments?.getString(ARG_LOCATION) ?: ChatLocation.NORMAL.name)
        showUniversalTab = arguments?.getBoolean(ARG_SHOW_UNIVERSAL) ?: true
        showSearchBar = arguments?.getBoolean(ARG_SHOW_SEARCH) ?: true


        view.setOnClickListener {

            if (chatsAdapter.isMultiSelectMode) {
                exitMultiSelectMode()
            }
        }


        val searchBarContainer = view.findViewById<View>(R.id.searchBarContainer)

        if (showSearchBar) {

            searchBarContainer.visibility = View.VISIBLE

            searchBarContainer.setOnClickListener {
                startActivity(Intent(requireContext(), LockedSearchActivity::class.java))
            }

        } else {

            searchBarContainer.visibility = View.GONE
        }


        rvChats = view.findViewById(R.id.rvChats)
        rvChats.layoutManager = LinearLayoutManager(requireContext())

        chatsAdapter = ChatsAdapter(
            items = mutableListOf(),
            hostLocation = myLocation,
            onItemClick = { chatItem ->

                val intent = Intent(requireContext(), ChatActivity::class.java)
                intent.putExtra(ChatActivity.EXTRA_CHAT_ID, chatItem.id)

                startActivity(intent)
            },
            onMenuClick = { chatItem, _ ->
                showChatMenuSheet(chatItem)
            },
            onSelectionChanged = {
                refreshMultiSelectHeader()
            }
        )

        rvChats.adapter = chatsAdapter


        tabDirect = view.findViewById(R.id.tabDirect)
        tabGroup = view.findViewById(R.id.tabGroup)
        tabUniversal = view.findViewById(R.id.tabUniversal)

        tabUniversal.visibility = if (showUniversalTab) View.VISIBLE else View.GONE

        selectTab("DIRECT")

        tabDirect.setOnClickListener { selectTab("DIRECT") }
        tabGroup.setOnClickListener { selectTab("GROUP") }

        tabUniversal.setOnClickListener {

            tabUniversal.setBackgroundResource(R.drawable.bg_performance_selected)
            tabDirect.setBackgroundResource(R.drawable.bg_performance_normal)
            tabGroup.setBackgroundResource(R.drawable.bg_performance_normal)

            startActivity(Intent(requireContext(), UniversalActivity::class.java))
        }


        filterAll = view.findViewById(R.id.filterAll)
        filterActive = view.findViewById(R.id.filterActive)
        filterInactive = view.findViewById(R.id.filterInactive)
        filterChipsContainer = view.findViewById(R.id.filterChipsContainer)
        filterChipsScrollView = view.findViewById(R.id.filterChipsScrollView)
        btnAddChat = view.findViewById(R.id.btnAddChat)

        // Touch-lock ab TouchLockContainer (XML mein wrap kiya hua) khud sambhalta hai —
        // yahan koi setOnTouchListener lagane ki zaroorat nahi

        filterAll.setOnClickListener { selectFilterMode(FilterMode.All) }
        filterActive.setOnClickListener { selectFilterMode(FilterMode.Active) }
        filterInactive.setOnClickListener { selectFilterMode(FilterMode.Inactive) }

        renderCustomListChips()

        btnAddChat.setOnClickListener {
            showAddToListDialog(emptyList())
        }
    }


    override fun onResume() {
        super.onResume()

        restoreTabHighlight()
        renderCustomListChips()
        refreshVisibleList()

        if (chatsAdapter.isMultiSelectMode) {
            wireHeaderControls()
            refreshMultiSelectHeader()
        }
    }


    private fun buildSelfItem(): ChatItem? {

        if (myLocation != ChatRepository.selfLocation) return null

        val prefs = requireContext().getSharedPreferences("signalx_prefs", Context.MODE_PRIVATE)
        val syncedName = prefs.getString("synced_name", null)
        val selfName = if (syncedName.isNullOrBlank()) "You" else syncedName

        return ChatItem(
            id = "self",
            name = selfName,
            subtitle = "(Manage Yourself)",
            isSelf = true
        )
    }


    private fun renderCustomListChips() {

        for (chip in customChipViews.values) {
            filterChipsContainer.removeView(chip)
        }

        customChipViews.clear()

        for (listName in ChatRepository.customLists) {

            val chipView = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_filter_chip, filterChipsContainer, false)

            chipView.findViewById<TextView>(R.id.tvChipLabel).text = listName

            chipView.setOnClickListener {
                selectFilterMode(FilterMode.Custom(listName))
            }

            chipView.setOnLongClickListener {
                confirmDeleteList(listName)
                true
            }

            val insertIndex = filterChipsContainer.indexOfChild(btnAddChat)
            filterChipsContainer.addView(chipView, insertIndex)

            customChipViews[listName] = chipView
        }

        applyFilterHighlight()
    }


    private fun selectFilterMode(mode: FilterMode) {

        currentFilterMode = mode
        applyFilterHighlight()
        centerSelectedChip()
        refreshVisibleList()
    }


    private fun centerSelectedChip() {

        val targetView: View? = when (val mode = currentFilterMode) {
            FilterMode.All -> filterAll
            FilterMode.Active -> filterActive
            FilterMode.Inactive -> filterInactive
            is FilterMode.Custom -> customChipViews[mode.name]
        }

        targetView?.post {

            val scrollViewWidth = filterChipsScrollView.width
            val targetCenter = targetView.left + targetView.width / 2
            val scrollTo = (targetCenter - scrollViewWidth / 2).coerceAtLeast(0)

            filterChipsScrollView.smoothScrollTo(scrollTo, 0)
        }
    }


    private fun applyFilterHighlight() {

        filterAll.setBackgroundResource(R.drawable.bg_performance_normal)
        filterActive.setBackgroundResource(R.drawable.bg_performance_normal)
        filterInactive.setBackgroundResource(R.drawable.bg_performance_normal)

        for (chip in customChipViews.values) {
            chip.setBackgroundResource(R.drawable.bg_performance_normal)
        }

        when (val mode = currentFilterMode) {
            FilterMode.All -> filterAll.setBackgroundResource(R.drawable.bg_performance_selected)
            FilterMode.Active -> filterActive.setBackgroundResource(R.drawable.bg_performance_selected)
            FilterMode.Inactive -> filterInactive.setBackgroundResource(R.drawable.bg_performance_selected)
            is FilterMode.Custom -> customChipViews[mode.name]?.setBackgroundResource(R.drawable.bg_performance_selected)
        }
    }


    private fun confirmDeleteList(name: String) {

        AlertDialog.Builder(requireContext())
            .setTitle("Delete List")
            .setMessage("Delete the list \"$name\"? Chats inside it won't be deleted.")
            .setPositiveButton("Delete") { _, _ ->

                ChatRepository.deleteList(name)

                val current = currentFilterMode
                if (current is FilterMode.Custom && current.name == name) {
                    currentFilterMode = FilterMode.All
                }

                renderCustomListChips()
                refreshVisibleList()

                Toast.makeText(requireContext(), "List deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


    private fun applyFilterMode(items: List<ChatItem>): List<ChatItem> {

        return when (val mode = currentFilterMode) {
            FilterMode.All -> items
            FilterMode.Active -> items.filter { it.isActive == true }
            FilterMode.Inactive -> items.filter { it.isActive == false }
            is FilterMode.Custom -> items.filter { ChatRepository.isChatInList(it.id, mode.name) }
        }
    }


    private fun refreshVisibleList() {

        val tabType = if (currentTab == "GROUP") ChatTabType.GROUP else ChatTabType.DIRECT

        val baseItems = ChatRepository.getChats(myLocation, tabType)
        val filteredItems = applyFilterMode(baseItems)

        val combined = mutableListOf<ChatItem>()
        val selfItem = buildSelfItem()

        val shouldShowSelf = when (val mode = currentFilterMode) {
            is FilterMode.Custom -> ChatRepository.isChatInList("self", mode.name)
            FilterMode.Inactive -> false
            else -> true
        }

        if (selfItem != null && shouldShowSelf) {
            combined.add(selfItem)
        }

        combined.addAll(filteredItems.sortedByDescending { it.isPinned })

        chatsAdapter.updateList(combined)
    }


    private fun selectTab(tab: String) {

        tabDirect.setBackgroundResource(R.drawable.bg_performance_normal)
        tabGroup.setBackgroundResource(R.drawable.bg_performance_normal)
        tabUniversal.setBackgroundResource(R.drawable.bg_performance_normal)

        currentTab = tab

        when (tab) {
            "DIRECT" -> tabDirect.setBackgroundResource(R.drawable.bg_performance_selected)
            "GROUP" -> tabGroup.setBackgroundResource(R.drawable.bg_performance_selected)
        }

        refreshVisibleList()
    }
    
    private fun restoreTabHighlight() {

        tabDirect.setBackgroundResource(R.drawable.bg_performance_normal)
        tabGroup.setBackgroundResource(R.drawable.bg_performance_normal)
        tabUniversal.setBackgroundResource(R.drawable.bg_performance_normal)

        when (currentTab) {
            "DIRECT" -> tabDirect.setBackgroundResource(R.drawable.bg_performance_selected)
            "GROUP" -> tabGroup.setBackgroundResource(R.drawable.bg_performance_selected)
        }
    }

    private fun showChatMenuSheet(chatItem: ChatItem) {

        val sheet = ChatMenuBottomSheet(chatItem) { action, item ->

            if (action == ChatMenuAction.SELECT) {

                enterMultiSelect(preselectAll = false)
                chatsAdapter.selectedIds.add(item.id)
                chatsAdapter.notifyDataSetChanged()
                refreshMultiSelectHeader()

            } else {

                executeAction(action, listOf(item))
            }
        }

        sheet.show(parentFragmentManager, "chat_menu_sheet")
    }


    private val hostController: MultiSelectHeaderController
        get() = (requireActivity() as MultiSelectHost).multiSelectController


    private fun enterMultiSelect(preselectAll: Boolean) {

        chatsAdapter.isMultiSelectMode = true

        if (preselectAll) {

            val allNonSelfIds = chatsAdapter.currentItems().filterNot { it.isSelf }.map { it.id }

            chatsAdapter.selectedIds.clear()
            chatsAdapter.selectedIds.addAll(allNonSelfIds)

        } else {

            chatsAdapter.selectedIds.clear()
        }

        chatsAdapter.notifyDataSetChanged()
        wireHeaderControls()
        refreshMultiSelectHeader()
    }


    private fun exitMultiSelectMode() {

        chatsAdapter.isMultiSelectMode = false
        chatsAdapter.selectedIds.clear()
        chatsAdapter.notifyDataSetChanged()
        hostController.hide()
    }


    private fun wireHeaderControls() {

        hostController.onExitClick = {
            exitMultiSelectMode()
        }

        hostController.onSelectAllClick = {

            val allNonSelfIds = chatsAdapter.currentItems().filterNot { it.isSelf }.map { it.id }

            chatsAdapter.selectedIds.clear()
            chatsAdapter.selectedIds.addAll(allNonSelfIds)
            chatsAdapter.notifyDataSetChanged()
            refreshMultiSelectHeader()
        }
    }


    private fun refreshMultiSelectHeader() {

        val selectedItems = chatsAdapter.currentItems().filter { chatsAdapter.selectedIds.contains(it.id) }

        hostController.show()

        val actions = computeHeaderActions(selectedItems)

        hostController.update(selectedItems.size, actions) { action ->
            executeAction(action, selectedItems)
        }
    }


    private fun computeHeaderActions(selectedItems: List<ChatItem>): List<Pair<ChatMenuAction, Boolean>> {

        if (selectedItems.isEmpty()) return emptyList()

        val hasSelf = selectedItems.any { it.isSelf }
        val normalItems = selectedItems.filterNot { it.isSelf }

        if (hasSelf && normalItems.isEmpty()) {

            return listOf(
                ChatMenuAction.REPLACE to false,
                ChatMenuAction.ADD_TO_LIST to false,
                ChatMenuAction.ADD_TO_HOME to false,
                ChatMenuAction.CLEAR_CHAT to false
            )
        }

        val actions = mutableListOf<Pair<ChatMenuAction, Boolean>>()

        actions.add(ChatMenuAction.DELETE to hasSelf)
        actions.add(ChatMenuAction.REPLACE to false)

        val pinAction = if (normalItems.all { it.isPinned }) ChatMenuAction.UNPIN else ChatMenuAction.PIN
        actions.add(pinAction to hasSelf)

        val blockAction = if (normalItems.all { it.isBlocked }) ChatMenuAction.UNBLOCK else ChatMenuAction.BLOCK
        actions.add(blockAction to hasSelf)

        actions.add(ChatMenuAction.ADD_TO_LIST to false)

        if (!hasSelf && normalItems.size == 1) {

            actions.add(ChatMenuAction.VIEW_CONTACT to false)
            actions.add(ChatMenuAction.ADD_TO_HOME to false)

        } else if (hasSelf) {

            actions.add(ChatMenuAction.VIEW_CONTACT to true)
            actions.add(ChatMenuAction.ADD_TO_HOME to false)
        }

        actions.add(ChatMenuAction.CLEAR_CHAT to false)

        if (normalItems.size >= 2) {
            actions.add(ChatMenuAction.CREATE_GROUP to false)
        }

        return actions
    }


    private fun finishBulkAction() {

        if (chatsAdapter.isMultiSelectMode) {
            exitMultiSelectMode()
        }

        refreshVisibleList()
    }


    private fun executeAction(action: ChatMenuAction, items: List<ChatItem>) {

        when (action) {

            ChatMenuAction.DELETE -> showDeleteDialog(items)
            ChatMenuAction.REPLACE -> showReplaceDialog(items)
            ChatMenuAction.PIN, ChatMenuAction.UNPIN -> togglePin(items)
            ChatMenuAction.BLOCK, ChatMenuAction.UNBLOCK -> toggleBlock(items)
            ChatMenuAction.ADD_TO_LIST -> showAddToListDialog(items)
            ChatMenuAction.VIEW_CONTACT -> handleViewContact(items.first())
            ChatMenuAction.ADD_TO_HOME -> addToHomeScreen(items.first())
            ChatMenuAction.CLEAR_CHAT -> showClearChatDialog(items)
            ChatMenuAction.CREATE_GROUP -> handleCreateGroup(items)
            ChatMenuAction.SELECT -> { /* sirf single-chat sheet se hi aata hai */ }
        }
    }


    private fun showDeleteDialog(items: List<ChatItem>) {

        val targets = items.filterNot { it.isSelf }

        if (targets.isEmpty()) {
            Toast.makeText(requireContext(), "This chat can't be deleted", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_delete_chat, null)
        val cbSelectMedia = dialogView.findViewById<CheckBox>(R.id.cbSelectMedia)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tvDeleteMessage)

        tvMessage.text =
            if (targets.size == 1) "Delete chat with ${targets.first().name}?"
            else "Delete ${targets.size} chats?"

        AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setPositiveButton("Delete") { _, _ ->

                val alsoDeleteMedia = cbSelectMedia.isChecked

                for (item in targets) ChatRepository.removeChat(item.id)

                if (alsoDeleteMedia) {
                    // TODO: media aane par yahan gallery se bhi delete karna
                }

                finishBulkAction()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


    private fun locationDisplayName(location: ChatLocation): String =
        when (location) {
            ChatLocation.NORMAL -> "Inbox"
            ChatLocation.ARCHIVED -> "Archived"
            ChatLocation.LOCKED -> "Locked"
        }


    private fun showReplaceDialog(items: List<ChatItem>) {

        val destinations = ChatLocation.values().filter { it != myLocation }
        val labels = destinations.map { locationDisplayName(it) }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("Replace")
            .setItems(labels) { _, which ->

                val target = destinations[which]

                if (target == ChatLocation.LOCKED) {

                    val prefs = requireContext().getSharedPreferences("signalx_prefs", Context.MODE_PRIVATE)

                    if (prefs.contains("locked_chats_pass_hash")) {

                        pendingMoveItems = items

                        val intent = Intent(requireContext(), UnlockLockedChatsActivity::class.java)
                        intent.putExtra(UnlockLockedChatsActivity.EXTRA_MODE, UnlockLockedChatsActivity.MODE_VERIFY_ONLY)

                        verifyPasswordLauncher.launch(intent)

                    } else {

                        Toast.makeText(requireContext(), "Set a Locked Chats password first", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(requireContext(), CreatePasswordActivity::class.java))
                    }

                } else {

                    moveChats(items, target)
                }
            }
            .show()
    }


    private fun moveChats(items: List<ChatItem>, location: ChatLocation) {

        if (items.isEmpty()) return

        for (item in items) {

            if (item.isSelf) ChatRepository.selfLocation = location
            else ChatRepository.updateChat(item.id) { it.copy(location = location) }
        }

        Toast.makeText(
            requireContext(),
            "Moved to ${locationDisplayName(location)}",
            Toast.LENGTH_SHORT
        ).show()

        finishBulkAction()
    }


    private fun togglePin(items: List<ChatItem>) {

        val targets = items.filterNot { it.isSelf }
        if (targets.isEmpty()) { finishBulkAction(); return }

        val makePinned = !targets.all { it.isPinned }

        for (item in targets) ChatRepository.updateChat(item.id) { it.copy(isPinned = makePinned) }

        finishBulkAction()
    }


    private fun toggleBlock(items: List<ChatItem>) {

        val targets = items.filterNot { it.isSelf }
        if (targets.isEmpty()) { finishBulkAction(); return }

        val makeBlocked = !targets.all { it.isBlocked }

        for (item in targets) ChatRepository.updateChat(item.id) { it.copy(isBlocked = makeBlocked) }

        Toast.makeText(
            requireContext(),
            if (makeBlocked) "Blocked" else "Unblocked",
            Toast.LENGTH_SHORT
        ).show()

        finishBulkAction()
    }


    private fun showAddToListDialog(items: List<ChatItem>) {

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_to_list, null)
        val tvEmpty = dialogView.findViewById<TextView>(R.id.tvListsEmpty)
        val existingListsContainer = dialogView.findViewById<LinearLayout>(R.id.existingListsContainer)
        val etNewListName = dialogView.findViewById<EditText>(R.id.etNewListName)
        val btnCreateList = dialogView.findViewById<TextView>(R.id.btnCreateList)

        val chatIds = items.map { it.id }

        fun renderDialogLists() {

            existingListsContainer.removeAllViews()

            if (ChatRepository.customLists.isEmpty()) {

                tvEmpty.visibility = View.VISIBLE

            } else {

                tvEmpty.visibility = View.GONE

                for (listName in ChatRepository.customLists) {

                    if (chatIds.isEmpty()) {

                        val row = TextView(requireContext()).apply {
                            text = listName
                            textSize = 13f
                            setTextColor(0xFF222222.toInt())
                            setPadding(4, 24, 4, 24)
                        }

                        existingListsContainer.addView(row)

                    } else {

                        val row = CheckBox(requireContext()).apply {
                            text = listName
                            textSize = 13f
                            isChecked = chatIds.all { ChatRepository.isChatInList(it, listName) }

                            setOnCheckedChangeListener { _, isChecked ->

                                if (isChecked) {
                                    ChatRepository.addChatsToList(chatIds, listName)
                                } else {
                                    ChatRepository.removeChatsFromList(chatIds, listName)
                                }
                            }
                        }

                        existingListsContainer.addView(row)
                    }
                }
            }
        }

        renderDialogLists()

        btnCreateList.setOnClickListener {

            val name = etNewListName.text.toString().trim()

            if (name.isBlank()) {
                Toast.makeText(requireContext(), "Enter a list name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val created = ChatRepository.createList(name)

            if (created) {

                if (chatIds.isNotEmpty()) {
                    ChatRepository.addChatsToList(chatIds, name)
                }

                etNewListName.text.clear()
                renderDialogLists()
                renderCustomListChips()

                Toast.makeText(requireContext(), "List \"$name\" created", Toast.LENGTH_SHORT).show()

            } else {

                Toast.makeText(requireContext(), "List already exists", Toast.LENGTH_SHORT).show()
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Add to List")
            .setView(dialogView)
            .setPositiveButton("Done") { _, _ -> finishBulkAction() }
            .show()
    }


    private fun handleViewContact(item: ChatItem) {

        if (item.isSelf) {
            Toast.makeText(requireContext(), "Not available for this chat", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "Coming soon", Toast.LENGTH_SHORT).show()
        }

        finishBulkAction()
    }

    private fun addToHomeScreen(chatItem: ChatItem) {

        val context = requireContext()

        try {

            val shortcutIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                addCategory(Intent.CATEGORY_DEFAULT)
                putExtra("open_chat_id", chatItem.id)
            }

            val shortcutInfo = ShortcutInfoCompat.Builder(context, "chat_${chatItem.id}")
                .setShortLabel(chatItem.name)
                .setLongLabel(chatItem.name)
                .setIcon(IconCompat.createWithResource(context, R.drawable.bg_avatar_circle))
                .setIntent(shortcutIntent)
                .build()

            if (ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {

                val pinnedReceiverIntent = Intent(context, ShortcutPinReceiver::class.java)

                val successCallback = android.app.PendingIntent.getBroadcast(
                    context,
                    chatItem.id.hashCode(),
                    pinnedReceiverIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )

                ShortcutManagerCompat.requestPinShortcut(
                    context,
                    shortcutInfo,
                    successCallback.intentSender
                )

                // Kuch der wait karke check karo ki system popup genuinely aaya ya nahi.
                // Agar 800ms baad bhi confirm nahi hua, MIUI ne silently block kiya hai —
                // seedha permission settings kholne ka option do.

                view?.postDelayed({

                    AlertDialog.Builder(context)
                        .setTitle("Didn't see a popup?")
                        .setMessage("Your phone's launcher may be blocking shortcut creation. Enable \"Create desktop shortcuts\" permission for SignalX to fix this.")
                        .setPositiveButton("Open Settings") { _, _ ->
                            MiuiPermissionHelper.openShortcutPermissionSettings(context)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()

                }, 800L)

            } else {

                val legacyWorked = tryLegacyShortcut(context, chatItem, shortcutIntent)

                if (!legacyWorked) {

                    AlertDialog.Builder(context)
                        .setTitle("Can't create shortcut")
                        .setMessage("Enable \"Create desktop shortcuts\" permission for SignalX to fix this.")
                        .setPositiveButton("Open Settings") { _, _ ->
                            MiuiPermissionHelper.openShortcutPermissionSettings(context)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }

        } catch (e: Exception) {

            Toast.makeText(context, "Couldn't add to home screen", Toast.LENGTH_SHORT).show()
        }

        finishBulkAction()
    }


    @Suppress("DEPRECATION")
    private fun tryLegacyShortcut(context: Context, chatItem: ChatItem, shortcutIntent: Intent): Boolean {

        return try {

            val addIntent = Intent()

            addIntent.putExtra("android.intent.extra.shortcut.INTENT", shortcutIntent)
            addIntent.putExtra("android.intent.extra.shortcut.NAME", chatItem.name)
            addIntent.putExtra(
                "android.intent.extra.shortcut.ICONRESOURCE",
                Intent.ShortcutIconResource.fromContext(context, R.drawable.bg_avatar_circle)
            )
            addIntent.action = "com.android.launcher.action.INSTALL_SHORTCUT"

            context.sendBroadcast(addIntent)

            true

        } catch (e: Exception) {

            false
        }
    }

    private fun showClearChatDialog(items: List<ChatItem>) {

        val name = if (items.size == 1) items.first().name else "${items.size} chats"

        AlertDialog.Builder(requireContext())
            .setTitle("Clear Chat")
            .setMessage("Clear all messages with $name? The chat itself will stay.")
            .setPositiveButton("Clear") { _, _ ->

                Toast.makeText(requireContext(), "Chat cleared", Toast.LENGTH_SHORT).show()
                finishBulkAction()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


    private fun handleCreateGroup(items: List<ChatItem>) {

        val targets = items.filterNot { it.isSelf }

        if (targets.size < 2) {
            Toast.makeText(requireContext(), "Select at least 2 chats to create a group", Toast.LENGTH_SHORT).show()
            return
        }

        startActivity(Intent(requireContext(), CreateGroupActivity::class.java))
        finishBulkAction()
    }
}