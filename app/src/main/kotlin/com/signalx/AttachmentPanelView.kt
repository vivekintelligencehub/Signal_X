package com.signalX

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * AttachmentPanelView — WhatsApp-style 3-stage attachment panel.
 *
 * GEOMETRY:
 *   TRAY = keyboard ki EXACT height (panel khud window-resize se naapta hai aur save kar leta hai).
 *   Tray ke UPAR wala hissa = white options box (natural height),
 *   uske BILKUL neeche = photos ki jhalak (grid ka top options ke bottom se milta hai).
 *
 * STATES:
 *   COLLAPSED → options + neeche photos ki jhalak (hamesha Recents + latest photos)
 *   MIDDLE    → gallery sheet options ke UPAR glide karti hai
 *   FULL      → poori screen; grid scroll
 *
 * RULES:
 *   RULE 1: COLLAPSED mein photo tap  → select + sheet MIDDLE
 *   RULE 2: MIDDLE/FULL mein photo tap → sirf select/deselect
 *   RULE 3: Selection ke saath COLLAPSED ki taraf → pehle collapse, phir discard popup
 *
 * DEBUG_OVERLAY (companion mein): true ho to panel ke upar-left mein ek chhota peela box
 * state/height ke numbers dikhata hai. Abhi false hai.
 */
class AttachmentPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    enum class State { COLLAPSED, MIDDLE, FULL }
    private enum class Zone { BAR, OPTIONS, HEADER, SHEET_BODY, OUTSIDE }

    // ---------------------------------------------------------------
    // Public callbacks — ChatActivity inhi names se wire karti hai
    // ---------------------------------------------------------------
    var onImageSend: ((List<Uri>, String) -> Unit)? = null
    var onRequestMediaPermission: (() -> Unit)? = null

    /** Baaki option cells: "location","contact","document","poll","audio","members","setting" */
    var onOptionClicked: ((String) -> Unit)? = null

    /** Panel khulte/band hote hi (drag-dismiss ya back se bhi) ChatActivity ko batata hai. */
    var onPanelVisibilityChanged: ((Boolean) -> Unit)? = null

    /** ChatActivity compat — ab zaroorat nahi: panel keyboard ki height khud naapta hai. */
    var keyboardHeightPx: Int = 0

    // ---------------------------------------------------------------
    // Views
    // ---------------------------------------------------------------
    private val scrimView: View
    private val optionsContainer: LinearLayout
    private val gallerySheet: LinearLayout
    private val headerSection: LinearLayout
    private val btnCloseGallery: TextView
    private val btnRecents: TextView
    private val btnHd: TextView
    private val galleryRecycler: RecyclerView

    private val selectionBar: LinearLayout
    private val barThumb: ImageView
    private val capEmoji: TextView
    private val etCaption: EditText
    private val btnSendImages: FrameLayout
    private val tvSendCount: TextView

    private val discardPopup: LinearLayout
    private val dpThumbs: LinearLayout
    private val btnDpCancel: TextView
    private val btnDpDiscard: TextView

    private val albumSheet: LinearLayout
    private val albumDragZone: View
    private val albumRecycler: RecyclerView
    private val fileBox: View

    private val editorOverlay: LinearLayout
    private val edClose: TextView
    private val edStrip: LinearLayout
    private val edHd: TextView
    private val edImage: ImageView
    private val edCaption: EditText
    private val edCount: TextView

    private var dbgView: TextView? = null

    // ---------------------------------------------------------------
    // Data
    // ---------------------------------------------------------------
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val imagesAdapter: RecentImagesAdapter
    private val albumsAdapter: AlbumsAdapter
    private var allImages: List<MediaImage> = emptyList()
    private var buckets: List<MediaBucket> = emptyList()
    private var selectedBucketId: String? = null   // null = Recents
    private val selection = mutableListOf<RecentImage>() // order = selection order
    private var imagesLoaded = false
    private var imagesLoading = false
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val maxSelection = 30

    // ---------------------------------------------------------------
    // Geometry / drag
    // ---------------------------------------------------------------
    private var panelH = 0          // is view ki abhi ki height (keyboard khuli ho to chhoti)
    private var fullH = 0           // ab tak dekhi sabse badi height (= keyboard band)
    private var trackedW = 0
    private var kbPx = 0            // keyboard ki naapi hui height (save hoti hai)
    private var trayPx = 0          // tray ki kul height = options + neeche photos ki jhalak
    private var optH = 0            // options box ki natural height
    private var albumHeightPx = 0
    private var watchedParent: View? = null

    private val headerH: Float
        get() = (headerSection.height.takeIf { it > 0 } ?: dp(68f)).toFloat()

    private val fullTop: Float get() = 0f
    private val middleTop: Float get() = panelH * 0.35f

    // Grid ka top (= collapsedTop + headerH) BILKUL options box ke bottom par baithta hai
    private val collapsedTop: Float get() = (panelH - trayPx + optH).toFloat() - headerH
    private val hiddenTop: Float get() = panelH.toFloat()

    private var curTop = 0f
    private var currentState = State.COLLAPSED
    private var panelVisible = false

    // Animation / drag flags (inme se koi bhi true ho to sizing positions ko nahi chhedti)
    private var openingClosing = false   // open/close animation chal rahi hai
    private var stateAnimating = false   // collapsed/middle/full animation chal rahi hai
    private var dragging = false         // finger se sheet drag ho rahi hai

    // Har naye animation par badhta hai — purane (cancel hue) animation ka callback ignore ho.
    // DHYAN: counter hamesha cancel() se PEHLE badhana hai (cancel turant onAnimationEnd bulata hai).
    private var animGeneration = 0

    // Keyboard screen par ho to panel tab tak nahi khulta jab tak window poori height par na aa jaye
    private var pendingOpen = false
    private var pendingOpenBaseH = 0
    private val pendingOpenTimeout = Runnable {
        if (pendingOpen) {
            pendingOpen = false
            startOpen()
        }
    }

    // Keyboard height tabhi save hoti hai jab window kuch der ek hi height par tiki rahe.
    // Aur sirf tab badalti hai jab naya naap pehle se bada ho ya bahut (20%+) chhota —
    // isse tray ka size 510 ↔ 606 jaise chhote farak se baar-baar nahi badalta.
    private var kbCandidate = 0
    private var lastWindowH = 0
    private var lastWindowChangeAt = 0L
    private val kbCommitRunnable = Runnable {
        val c = kbCandidate
        if (c > 0 && (c > kbPx || c < kbPx * 0.8f) && fullH - lastWindowH == c) {
            kbPx = c
            prefs.edit().putInt(KEY_KB_PX, c).apply()
            applySizing()
        }
    }

    private val touchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop }
    private var downX = 0f
    private var downY = 0f
    private var dragStartTop = 0f
    private var dragStartState = State.COLLAPSED
    private var downZone = Zone.OUTSIDE
    private var velocityTracker: VelocityTracker? = null
    private var animator: ValueAnimator? = null

    // RULE 3 pending
    private var pendingCollapseFrom: State? = null

    // push (input bar ko tray ke upar rakhta hai)
    private var contentPushView: View? = null
    private var pushOriginalBottom = 0

    // album sheet own drag
    private var abDownY = 0f
    private var abDragging = false

    // editor
    private var editorOpen = false
    private var edIndex = 0

    private val headerBg = GradientDrawable()
    private val bodyBg = GradientDrawable()
    private val sheetColor = 0xFF11181C.toInt()
    private var gridScrollLocked = true

    // Window (parent) ki height badalte hi keyboard ki height naapne ke liye
    private val parentLayoutListener =
        View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            trackWindowSize(v.width, v.height)
        }

    // ---------------------------------------------------------------
    // Init
    // ---------------------------------------------------------------
    init {
        LayoutInflater.from(context).inflate(R.layout.view_attachment_sheet, this, true)
        isClickable = false // root blindly touch consume NAHI karega (pass-through)

        scrimView = findViewById(R.id.scrimView)
        optionsContainer = findViewById(R.id.optionsContainer)
        gallerySheet = findViewById(R.id.gallerySheet)
        headerSection = findViewById(R.id.headerSection)
        btnCloseGallery = findViewById(R.id.btnCloseGallery)
        btnRecents = findViewById(R.id.btnRecents)
        btnHd = findViewById(R.id.btnHd)
        galleryRecycler = findViewById(R.id.galleryRecycler)

        selectionBar = findViewById(R.id.selectionBar)
        barThumb = findViewById(R.id.barThumb)
        capEmoji = findViewById(R.id.capEmoji)
        etCaption = findViewById(R.id.etCaption)
        btnSendImages = findViewById(R.id.btnSendImages)
        tvSendCount = findViewById(R.id.tvSendCount)

        discardPopup = findViewById(R.id.discardPopup)
        dpThumbs = findViewById(R.id.dpThumbs)
        btnDpCancel = findViewById(R.id.btnDpCancel)
        btnDpDiscard = findViewById(R.id.btnDpDiscard)

        albumSheet = findViewById(R.id.albumSheet)
        albumDragZone = findViewById(R.id.albumDragZone)
        albumRecycler = findViewById(R.id.albumRecycler)
        fileBox = findViewById(R.id.fileBox)

        editorOverlay = findViewById(R.id.editorOverlay)
        edClose = findViewById(R.id.edClose)
        edStrip = findViewById(R.id.edStrip)
        edHd = findViewById(R.id.edHd)
        edImage = findViewById(R.id.edImage)
        edCaption = findViewById(R.id.edCaption)
        edCount = findViewById(R.id.edCount)

        // pichli baar naapi hui keyboard height (pehli baar ke liye bhi sahi size)
        kbPx = prefs.getInt(KEY_KB_PX, 0)

        // z-order (drawing + touch priority)
        scrimView.elevation = dp(1f).toFloat()
        optionsContainer.elevation = dp(2f).toFloat()
        gallerySheet.elevation = dp(4f).toFloat()
        selectionBar.elevation = dp(6f).toFloat()
        albumSheet.elevation = dp(8f).toFloat()
        discardPopup.elevation = dp(10f).toFloat()
        editorOverlay.elevation = dp(12f).toFloat()

        // sheet styling: rounded corners -> FULL pe flat
        headerBg.setColor(sheetColor)
        headerBg.cornerRadii = topRadii(dp(16f).toFloat())
        headerSection.background = headerBg
        bodyBg.setColor(sheetColor)
        bodyBg.alpha = 255
        galleryRecycler.background = bodyBg

        // scrim: dim chat, tap = collapse attempt (RULE 3 compatible)
        scrimView.visibility = GONE
        scrimView.setOnClickListener { collapseThenAsk(currentState) }

        imagesAdapter = RecentImagesAdapter(mutableListOf()) { img -> onThumbTap(img) }
        gridScrollLocked = true
        galleryRecycler.layoutManager = object : GridLayoutManager(context, 4) {
            override fun canScrollVertically(): Boolean =
                !gridScrollLocked && super.canScrollVertically()
        }
        galleryRecycler.adapter = imagesAdapter
        galleryRecycler.overScrollMode = View.OVER_SCROLL_NEVER

        albumsAdapter = AlbumsAdapter(emptyList()) { bucket -> onAlbumPicked(bucket) }
        albumRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
        albumRecycler.adapter = albumsAdapter

        wireClicks()
        wireAlbumGestures()

        // view_attach_panel.xml ki apni hard-coded height ko override karo —
        // options box apni natural (content jitni) height le.
        optionsContainer.getChildAt(optionsContainer.childCount - 1)?.let { included ->
            (included.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                lp.weight = 0f
            }
        }

        // Options box ki asli height pata chalte hi sizing dobara (grid ka top = options ka bottom)
        optionsContainer.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) post { applySizing() }
        }

        headerSection.alpha = 0f
        headerSection.visibility = INVISIBLE
        optionsContainer.alpha = 0f
        curTop = hiddenTop

        // TEMPORARY debug box (bug pakadne ke liye) — abhi band hai
        if (DEBUG_OVERLAY) {
            val tv = TextView(context)
            tv.setTextColor(0xFFFFFF00.toInt())
            tv.setBackgroundColor(0xB3000000.toInt())
            tv.textSize = 9f
            tv.typeface = Typeface.MONOSPACE
            tv.setPadding(dp(4f), dp(2f), dp(4f), dp(2f))
            tv.isClickable = false
            tv.elevation = dp(20f).toFloat()
            val dlp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            dlp.gravity = Gravity.TOP or Gravity.START
            dlp.topMargin = dp(48f)
            addView(tv, dlp)
            dbgView = tv
        }
    }

    private fun wireClicks() {
        // Option cells (view_attach_panel.xml ke EXISTING ids)
        findViewById<View>(R.id.cellGallery).setOnClickListener {
            if (currentState != State.FULL) goState(State.MIDDLE)
        }
        findViewById<View>(R.id.cellLocation)?.setOnClickListener { onOptionClicked?.invoke("location") }
        findViewById<View>(R.id.cellContact)?.setOnClickListener { onOptionClicked?.invoke("contact") }
        findViewById<View>(R.id.cellDocument)?.setOnClickListener { onOptionClicked?.invoke("document") }
        findViewById<View>(R.id.cellPoll)?.setOnClickListener { onOptionClicked?.invoke("poll") }
        findViewById<View>(R.id.cellAudio)?.setOnClickListener { onOptionClicked?.invoke("audio") }
        findViewById<View>(R.id.cellMembers)?.setOnClickListener { onOptionClicked?.invoke("members") }
        findViewById<View>(R.id.cellSetting)?.setOnClickListener { onOptionClicked?.invoke("setting") }

        btnCloseGallery.setOnClickListener { collapseThenAsk(currentState) }

        btnRecents.setOnClickListener {
            if (isAlbumOpen()) closeAlbums() else openAlbums()   // TOGGLE
        }

        btnHd.setOnClickListener {
            val on = !btnHd.isSelected
            setHd(on)
            toast(if (on) "Items set to HD quality" else "Items set to standard quality")
        }
        edHd.setOnClickListener { btnHd.performClick() }

        fileBox.setOnClickListener {
            closeAlbums()
            onOptionClicked?.invoke("document")   // Files → existing document flow
        }

        // selection bar
        btnSendImages.setOnClickListener { sendSelection() }
        capEmoji.setOnClickListener {
            etCaption.requestFocus()
            showKeyboard(etCaption)
        }
        barThumb.setOnClickListener { openEditor() }

        // discard popup
        btnDpCancel.setOnClickListener {
            hideDiscardPopup()
            val back = pendingCollapseFrom ?: State.MIDDLE
            pendingCollapseFrom = null
            goState(back)   // Cancel → jahan the wahin recover
        }
        btnDpDiscard.setOnClickListener {
            hideDiscardPopup()
            pendingCollapseFrom = null
            clearSelection()   // Discard → collapsed hi rahe, sirf selection clear
            resetGalleryToLatest()
            hideKeyboardNow()
        }

        // editor
        edClose.setOnClickListener { closeEditor() }
        findViewById<TextView>(R.id.toolUndo).setOnClickListener { toolPhase2("Undo") }
        findViewById<TextView>(R.id.toolRotate).setOnClickListener { toolPhase2("Rotate") }
        findViewById<TextView>(R.id.toolCrop).setOnClickListener { toolPhase2("Crop") }
        findViewById<TextView>(R.id.toolSticker).setOnClickListener { toolPhase2("Stickers") }
        findViewById<TextView>(R.id.toolText).setOnClickListener { toolPhase2("Text") }
        findViewById<TextView>(R.id.toolDraw).setOnClickListener { toolPhase2("Draw") }
        findViewById<View>(R.id.edSend).setOnClickListener {
            etCaption.setText(edCaption.text.toString())
            sendSelection()
        }
        findViewById<TextView>(R.id.edCapEmoji).setOnClickListener {
            edCaption.requestFocus()
            showKeyboard(edCaption)
        }
    }

    private fun toolPhase2(name: String) = toast("$name — option rakha hai, working phase-2")

    // ---------------------------------------------------------------
    // Public API (ChatActivity-compatible)
    // ---------------------------------------------------------------
    fun isOpen(): Boolean = panelVisible || pendingOpen

    /** ChatActivity: chatMainContent pass karo — input bar tray ke upar push ho jaayega. */
    fun setContentPushView(v: View) {
        contentPushView = v
        pushOriginalBottom = v.paddingBottom
    }

    fun showPanel() {
        if (panelVisible) {
            if (currentState != State.COLLAPSED) goState(State.COLLAPSED)
            return
        }
        if (pendingOpen) return

        syncHeightFromParent()

        if (panelH == 0) {
            post { showPanel() }
            return
        }

        // Keyboard abhi screen par hai (ChatActivity use hide kar rahi hai) → window abhi chhoti
        // hai aur kuch der mein badi hogi. Us beech panel kholne se geometry adhuri window mein
        // banti thi (aur tap galat jagah lagte the) — isliye window ke poori height par aane ka
        // intezaar karo (timeout ke saath).
        if (fullH > 0 && fullH - panelH > dp(120f)) {
            pendingOpen = true
            pendingOpenBaseH = panelH
            hideKeyboardNow()
            postDelayed(pendingOpenTimeout, PENDING_OPEN_TIMEOUT_MS)
            updateDebug()
            return
        }

        startOpen()
    }

    private fun startOpen() {
        syncHeightFromParent()
        if (panelH == 0) return

        pristineReset()   // pichle session ka koi bhi bacha hua state saaf
        applySizing()     // panelVisible abhi false hai → push/position nahi chhedta

        panelVisible = true
        // Fresh open hamesha COLLAPSED se — kabhi stale MIDDLE/FULL nahi
        currentState = State.COLLAPSED
        visibility = VISIBLE
        onPanelVisibilityChanged?.invoke(true)
        loadImagesIfNeeded(force = false)

        curTop = hiddenTop
        updatePositions(curTop)
        animateOpenClose(opening = true)
    }

    fun hidePanel() {
        // Khulne ka intezaar chal raha ho to use hi cancel kar do
        if (pendingOpen) {
            pendingOpen = false
            removeCallbacks(pendingOpenTimeout)
            visibility = GONE
            onPanelVisibilityChanged?.invoke(false)
            updateDebug()
            return
        }

        if (!panelVisible) return
        panelVisible = false
        stopTracking()
        closeEditor()
        closeAlbums()
        hideDiscardPopup()
        clearSelection()
        pendingCollapseFrom = null
        resetGalleryToLatest()   // band hote hi Recents + latest photos par wapas
        currentState = State.COLLAPSED
        // ChatActivity ko TURANT batao (drag-dismiss / back se band hua ho tab bhi),
        // warna paperclip highlighted rehta tha aur dobara dabane par keyboard khulta tha.
        onPanelVisibilityChanged?.invoke(false)
        animateOpenClose(opening = false)
    }

    /** Permission grant ke baad ChatActivity isi ko call karti hai (existing wiring). */
    fun refreshImages() {
        imagesLoaded = false
        loadImagesIfNeeded(force = true)
    }

    /** ChatActivity.onBackPressed se sabse pehle call karo. true = consume ho gaya. */
    fun handleBackPress(): Boolean {
        if (!panelVisible) return false
        if (editorOpen) { closeEditor(); return true }
        if (isAlbumOpen()) { closeAlbums(); return true }
        if (discardPopup.visibility == VISIBLE) {
            btnDpCancel.performClick()   // back = Cancel (state recover)
            return true
        }
        if (selection.isNotEmpty() && currentState != State.COLLAPSED) {
            collapseThenAsk(currentState)
            return true
        }
        return when (currentState) {
            State.FULL -> { goState(State.MIDDLE); true }
            State.MIDDLE -> { goState(State.COLLAPSED); true }
            State.COLLAPSED -> { hidePanel(); true }
        }
    }

    /** Har naye khulne par pichhla sab kuch (popup/album/editor/selection/drag) saaf. */
    private fun pristineReset() {
        animGeneration++
        animator?.cancel()
        animator = null
        openingClosing = false
        stateAnimating = false
        stopTracking()
        pendingCollapseFrom = null
        clearSelection()
        resetGalleryToLatest()

        discardPopup.animate().cancel()
        discardPopup.visibility = GONE
        albumSheet.animate().cancel()
        albumSheet.visibility = GONE
        editorOverlay.animate().cancel()
        editorOverlay.visibility = GONE
        editorOverlay.alpha = 1f
        editorOpen = false

        gridScrollLocked = true
        downZone = Zone.OUTSIDE
    }

    // ---------------------------------------------------------------
    // Gallery default: Recents + latest photos
    // ---------------------------------------------------------------
    /** Album filter hatao ("Recents") aur grid ko sabse upar (latest photo) par le aao. */
    private fun resetGalleryToLatest() {
        if (selectedBucketId != null) {
            selectedBucketId = null
            btnRecents.text = "Recents ▾"
            if (imagesLoaded) applyFilter()
        }
        (galleryRecycler.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
            ?.scrollToPositionWithOffset(0, 0)
    }

    /** COLLAPSED par pahunchte hi (selection ka sawal na ho to) default gallery wapas. */
    private fun onReachedCollapsed() {
        if (selection.isEmpty() && pendingCollapseFrom == null) resetGalleryToLatest()
    }

    // ---------------------------------------------------------------
    // Window / keyboard height tracking
    // ---------------------------------------------------------------
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (parent as? View)?.let { p ->
            watchedParent = p
            p.addOnLayoutChangeListener(parentLayoutListener)
            trackWindowSize(p.width, p.height)
        }
    }

    private fun syncHeightFromParent() {
        (parent as? View)?.let { p ->
            if (p.height > 0) {
                panelH = p.height
                trackWindowSize(p.width, p.height)
            }
        }
    }

    /**
     * Window (parent) ki height ka sabse bada maan = keyboard band. Jab height itni ghate
     * (> 120dp) aur kuch der wahin tiki rahe to farak = keyboard ki exact height — yahi tray
     * ki height banti hai, to input box keyboard ↔ panel switch par bilkul nahi hilta.
     */
    private fun trackWindowSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return

        if (h != lastWindowH) {
            lastWindowH = h
            lastWindowChangeAt = SystemClock.uptimeMillis()
        }

        if (w != trackedW) {      // pehli baar ya rotation — naye sire se
            trackedW = w
            fullH = h
            return
        }

        if (h >= fullH) {
            fullH = h
            kbCandidate = 0
            removeCallbacks(kbCommitRunnable)
        } else {
            val diff = fullH - h
            if (diff > dp(120f) && diff <= (fullH * 0.6f).toInt()) {
                kbCandidate = diff
                removeCallbacks(kbCommitRunnable)
                postDelayed(kbCommitRunnable, 280)
            }
        }

        // Keyboard hatne ka intezaar kar rahe the → window badi ho gayi, ab panel kholo
        if (pendingOpen && h - pendingOpenBaseH > dp(100f)) {
            post {
                if (pendingOpen) {
                    pendingOpen = false
                    removeCallbacks(pendingOpenTimeout)
                    startOpen()
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Sizing
    // ---------------------------------------------------------------
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        panelH = h
        trackWindowSize(w, h)
        applySizing()
    }

    // Size same rehne par onSizeChanged nahi bulata — isliye har layout par asli height se sync
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val h = bottom - top
        if (h > 0 && h != panelH) {
            panelH = h
            post { applySizing() }
        }
    }

    /** Dropdown (album list) sheet ke header ke THEEK neeche se shuru ho — chipak kar. */
    private fun computeAlbumHeight(): Int {
        val albumTop = topOf(currentState) + headerH
        return (panelH - albumTop).toInt().coerceIn(dp(220f), max(panelH, dp(220f)))
    }

    private fun applySizing() {
        if (panelH <= 0) return

        val baseH = max(panelH, fullH)

        optH = optionsContainer.height.takeIf { it > 0 } ?: dp(190f)

        // Tray = keyboard ki exact height. Keyboard kabhi khuli hi na ho to andaaza
        // (pehli baar keyboard khulte hi sahi naap save ho jaata hai).
        val kb = if (kbPx > 0) {
            kbPx.coerceIn(dp(180f), (baseH * 0.6f).toInt())
        } else {
            (baseH * 0.345f).toInt().coerceIn(dp(230f), dp(340f))
        }

        // Options ke neeche kam se kam 72dp photos ki jhalak bachni chahiye
        trayPx = min(max(kb, optH + dp(72f)), (baseH * 0.72f).toInt())

        // White options box: tray ke TOP par (neeche nahi) — isse grid options ko kabhi nahi dhakta
        val olp = optionsContainer.layoutParams as FrameLayout.LayoutParams
        val optTop = (panelH - trayPx).coerceAtLeast(0)
        if (
            olp.gravity != Gravity.TOP ||
            olp.height != ViewGroup.LayoutParams.WRAP_CONTENT ||
            olp.topMargin != optTop ||
            olp.bottomMargin != 0
        ) {
            olp.gravity = Gravity.TOP
            olp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            olp.topMargin = optTop
            olp.bottomMargin = 0
            optionsContainer.layoutParams = olp
        }

        // scrim: tray ke UPAR ka poora area
        val slp = scrimView.layoutParams as FrameLayout.LayoutParams
        if (slp.bottomMargin != trayPx) {
            slp.bottomMargin = trayPx
            scrimView.layoutParams = slp
        }

        // Album dropdown ki height: sheet ke asli top se (header ke neeche) bottom tak
        albumHeightPx = computeAlbumHeight()
        val alp = albumSheet.layoutParams as FrameLayout.LayoutParams
        if (alp.height != albumHeightPx) {
            alp.height = albumHeightPx
            albumSheet.layoutParams = alp
        }

        // Animation / drag chal raha ho to positions usi ko sambhalne do (woh LIVE geometry use karte hain)
        val busy = openingClosing || stateAnimating || dragging
        if (!busy) {
            curTop = if (panelVisible) topOf(currentState) else hiddenTop
            updatePositions(curTop)
            if (panelVisible) applyPush(trayPx)
        }

        // Dropdown band ho tab hi use neeche chhupa rakho (khuli ho to uski translation ko mat chhedo)
        if (!isAlbumOpen()) albumSheet.translationY = albumHeightPx.toFloat()

        updateDebug()
    }

    private fun applyPush(px: Int) {
        val v = contentPushView ?: return
        val target = pushOriginalBottom + px
        if (v.paddingBottom != target) {
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, target)
        }
    }

    private fun topOf(s: State): Float = when (s) {
        State.FULL -> fullTop
        State.MIDDLE -> middleTop
        State.COLLAPSED -> collapsedTop
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    // ---------------------------------------------------------------
    // Open / close animations (LIVE geometry)
    // ---------------------------------------------------------------
    private fun animateOpenClose(opening: Boolean) {
        val myGen = ++animGeneration   // PEHLE badhao, phir purana cancel karo
        animator?.cancel()
        animator = null
        openingClosing = true
        stateAnimating = false

        val startTopFrac = if (panelH > 0) curTop / panelH else 1f
        val pushStart = ((contentPushView?.paddingBottom ?: 0) - pushOriginalBottom).coerceAtLeast(0)
        val optFrom = optionsContainer.alpha
        val optTo = if (opening) 1f else 0f

        val anim = ValueAnimator.ofFloat(0f, 1f)
        animator = anim
        anim.duration = 240
        anim.interpolator = DecelerateInterpolator(1.4f)

        anim.addUpdateListener { a ->
            if (myGen != animGeneration) return@addUpdateListener
            val t = a.animatedValue as Float

            val top = if (opening) {
                lerp(hiddenTop, collapsedTop, t)
            } else {
                lerp(startTopFrac * panelH, hiddenTop, t)
            }
            updatePositions(top)

            optionsContainer.alpha = lerp(optFrom, optTo, t)

            val pushEnd = if (opening) trayPx else 0
            applyPush(lerp(pushStart.toFloat(), pushEnd.toFloat(), t).toInt())
        }

        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // Naya animation shuru ho chuka ho to yeh callback STALE hai — kuch mat karo
                if (myGen != animGeneration) return
                openingClosing = false
                animator = null
                if (opening) {
                    currentState = State.COLLAPSED
                    gridScrollLocked = true
                    applySizing()
                    // Window baad mein badi/chhoti ho sakti hai — phir se sahi baithao
                    postDelayed({ applySizing() }, 350)
                } else {
                    visibility = GONE
                    currentState = State.COLLAPSED
                    updatePositions(hiddenTop)
                    optionsContainer.alpha = 0f
                    applyPush(0)
                }
                updateDebug()
            }
        })

        anim.start()
    }

    // ---------------------------------------------------------------
    // Touch engine
    // ---------------------------------------------------------------
    private fun zoneAt(x: Float, y: Float): Zone {
        // selection bar sabse pehle (woh sheet ke bottom band pe overlay karta hai)
        if (selectionBar.visibility == VISIBLE && y >= selectionBar.top && y <= selectionBar.bottom) return Zone.BAR

        val sheetTop = curTop
        val inSheet = y >= sheetTop
        val inHeader = inSheet && y <= sheetTop + headerH

        val o = optionsContainer
        val inOptions = y >= o.top && y <= o.bottom && y < sheetTop

        return when {
            inOptions -> Zone.OPTIONS
            inHeader -> Zone.HEADER
            inSheet -> Zone.SHEET_BODY
            else -> Zone.OUTSIDE
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!panelVisible || editorOpen) return false

        // Har DOWN par zone/start-values TAAZA record karo — animation chal rahi ho tab bhi,
        // warna purane (stale) values se tap ko drag samajh liya jaata tha.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = ev.x
            downY = ev.y
            dragStartTop = curTop
            dragStartState = currentState
            downZone = zoneAt(ev.x, ev.y)
        }

        // Open/close animation ke dauran drag shuru nahi hota
        if (openingClosing) return false

        // album khuli ho to BAHAR ka pehla touch = sirf album band (consume)
        if (isAlbumOpen()) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                val r = IntArray(2).also { albumSheet.getLocationOnScreen(it) }
                val inside = ev.rawY >= r[1] && ev.rawY <= r[1] + albumSheet.height
                if (!inside) {
                    closeAlbums()
                    return true
                }
            }
            return false
        }

        // Popup ke apne buttons (Cancel/Discard) sab kuch khud sambhalte hain
        if (discardPopup.visibility == VISIBLE) return false

        if (ev.actionMasked == MotionEvent.ACTION_MOVE) {
            if (downZone == Zone.OUTSIDE || downZone == Zone.BAR) return false
            val dy = ev.y - downY
            val dx = ev.x - downX
            if (downZone == Zone.SHEET_BODY && currentState == State.FULL) {
                // FULL mein grid apna scroll karti hai — LEKIN grid EXACT top pe ho aur
                // neeche kheencho → sheet drag
                if (dy > touchSlop && abs(dy) > abs(dx) && !galleryRecycler.canScrollVertically(-1)) {
                    startTracking(ev)
                    return true
                }
                return false
            }
            if (abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                startTracking(ev)
                return true
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!panelVisible || discardPopup.visibility == VISIBLE) {
            if (dragging) stopTracking()
            return false
        }

        // Dropdown khula ho to uske blank hisse par touch sirf consume ho (sheet drag nahi)
        if (isAlbumOpen()) return true

        // Touch asli input-bar / paperclip / switch-icon ke area mein hai — neeche waali
        // asli view tak jaane do, khud claim mat karo.
        if (downZone == Zone.OUTSIDE) return false

        val action = event.actionMasked

        if (!dragging) {
            // Sirf tap / blank-area touch — drag shuru hi nahi hua. Animation ke dauran ya
            // sirf tap par KUCH mat karo. Drag tabhi shuru karo jab finger sach mein vertical hile.
            if (action == MotionEvent.ACTION_MOVE && !openingClosing && downZone != Zone.BAR) {
                val dy = event.y - downY
                val dx = event.x - downX
                if (abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                    startTracking(event)
                } else {
                    return true
                }
            } else {
                return true
            }
        }

        when (action) {
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val newTop = dragStartTop + (event.y - downY)
                updatePositions(newTop.coerceIn(fullTop, hiddenTop))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                var vy = 0f
                velocityTracker?.let {
                    it.addMovement(event)
                    it.computeCurrentVelocity(1000)
                    vy = it.yVelocity
                }
                stopTracking()
                settle(curTop, if (action == MotionEvent.ACTION_UP) vy else 0f, dragStartState)
            }
        }
        return true
    }

    private fun startTracking(ev: MotionEvent) {
        stopTracking()
        velocityTracker = VelocityTracker.obtain()
        velocityTracker?.addMovement(ev)
        dragging = true
    }

    private fun stopTracking() {
        velocityTracker?.recycle()
        velocityTracker = null
        dragging = false
    }

    private fun settle(y: Float, vy: Float, startedFrom: State) {
        val canDismiss = startedFrom == State.COLLAPSED
        val dismissZone = collapsedTop + (hiddenTop - collapsedTop) * 0.4f

        val target: State? = when {
            vy < -STRONG_FLING -> State.FULL
            vy > STRONG_FLING -> if (canDismiss && y > dismissZone) null else State.COLLAPSED
            vy < -MIN_FLING -> if (y > middleTop + touchSlop) State.MIDDLE else State.FULL
            vy > MIN_FLING -> when {
                y < middleTop - touchSlop -> State.MIDDLE
                canDismiss && y > dismissZone -> null
                else -> State.COLLAPSED
            }
            else -> {
                if (canDismiss && y > dismissZone && y > collapsedTop + touchSlop) null
                else {
                    val dFull = abs(y - fullTop)
                    val dMid = abs(y - middleTop)
                    val dCol = abs(y - collapsedTop)
                    when {
                        dFull <= dMid && dFull <= dCol -> State.FULL
                        dMid <= dCol -> State.MIDDLE
                        else -> State.COLLAPSED
                    }
                }
            }
        }

        when {
            target == null -> hidePanel()                          // tray dismiss (WhatsApp jaisa)
            target == State.COLLAPSED -> collapseThenAsk(startedFrom) // RULE 3 hook
            else -> goState(target)
        }
    }

    fun goState(state: State) = goStateInternal(state, null)

    private fun goStateInternal(state: State, onEnd: (() -> Unit)?) {
        val myGen = ++animGeneration   // PEHLE badhao, phir purana cancel karo
        animator?.cancel()
        animator = null
        openingClosing = false
        stateAnimating = false
        gridScrollLocked = state != State.FULL

        val startFrac = if (panelH > 0) curTop / panelH else 0f
        val from = curTop
        val to = topOf(state)

        if (abs(to - from) < 1f) {
            currentState = state
            updatePositions(to)
            if (state == State.COLLAPSED) onReachedCollapsed()
            onEnd?.invoke()
            return
        }

        stateAnimating = true

        val dist = abs(to - from)
        val anim = ValueAnimator.ofFloat(0f, 1f)
        animator = anim
        anim.duration = (170 + (dist / panelH.coerceAtLeast(1)) * 160).toLong().coerceIn(170, 340)
        anim.interpolator = DecelerateInterpolator(1.5f)

        anim.addUpdateListener { a ->
            if (myGen != animGeneration) return@addUpdateListener
            updatePositions(lerp(startFrac * panelH, topOf(state), a.animatedValue as Float))
        }

        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (myGen != animGeneration) return
                stateAnimating = false
                animator = null
                currentState = state
                updatePositions(topOf(state))
                if (state == State.COLLAPSED) onReachedCollapsed()
                onEnd?.invoke()
                updateDebug()
            }
        })

        anim.start()
    }

    /** Sheet position + header gradual reveal + scrim + corners — sab yahin sync. */
    private fun updatePositions(y: Float) {
        curTop = y
        gallerySheet.translationY = y

        val revealSpan = (collapsedTop - middleTop).coerceAtLeast(1f)
        val reveal = ((collapsedTop - y) / revealSpan * 1.15f).coerceIn(0f, 1f)
        headerSection.alpha = reveal
        headerSection.visibility = if (reveal <= 0.02f) INVISIBLE else VISIBLE

        scrimView.alpha = reveal * 0.55f
        val scrimOn = reveal > 0.05f && panelVisible
        scrimView.visibility = if (scrimOn) VISIBLE else GONE

        val fullSpan = (middleTop - fullTop).coerceAtLeast(1f)
        val ff = ((middleTop - y) / fullSpan).coerceIn(0f, 1f)
        headerBg.cornerRadii = topRadii(dp(16f) * (1f - ff))

        updateDebug()
    }

    private fun updateDebug() {
        if (!DEBUG_OVERLAY) return
        dbgView?.text =
            "st=$currentState vis=$panelVisible pend=$pendingOpen\n" +
                "top=${curTop.toInt()} H=$panelH full=$fullH\n" +
                "kb=$kbPx tray=$trayPx opt=$optH hdr=${headerH.toInt()}\n" +
                "oc=$openingClosing sa=$stateAnimating dr=$dragging sel=${selection.size} alb=${isAlbumOpen()}"
    }

    // ---------------------------------------------------------------
    // RULE 3 — pehle COLLAPSE, phir discard POPUP
    // ---------------------------------------------------------------
    private fun collapseThenAsk(fromState: State) {
        if (selection.isEmpty() || fromState == State.COLLAPSED) {
            goStateInternal(State.COLLAPSED, null)
            return
        }
        pendingCollapseFrom = fromState
        goStateInternal(State.COLLAPSED) { showDiscardPopup() }   // collapse complete hone pe popup
    }

    private fun showDiscardPopup() {
        if (pendingCollapseFrom == null) return
        dpThumbs.removeAllViews()
        val size = dp(46f)
        selection.take(5).forEach { img ->
            val iv = ImageView(context)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginEnd = dp(6f)
            iv.layoutParams = lp
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            Glide.with(iv).load(img.uri).centerCrop().into(iv)
            dpThumbs.addView(iv)
        }
        discardPopup.visibility = VISIBLE
        discardPopup.translationY = discardPopup.height + dp(140f).toFloat()
        discardPopup.animate().translationY(0f).setDuration(220).start()
    }

    private fun hideDiscardPopup() {
        if (discardPopup.visibility != VISIBLE) return
        discardPopup.animate().translationY(discardPopup.height + dp(140f).toFloat())
            .setDuration(180).withEndAction { discardPopup.visibility = GONE }.start()
    }

    // ---------------------------------------------------------------
    // RULE 1 & 2 — photo tap (multi-select, in-place)
    // ---------------------------------------------------------------
    private fun onThumbTap(img: RecentImage) {
        // Panel khul/band ho raha ho ya window abhi-abhi badli ho (keyboard aa/ja raha ho) —
        // tab anjaane mein hui tap ko ignore karo
        if (openingClosing || pendingOpen) return
        if (SystemClock.uptimeMillis() - lastWindowChangeAt < 250) return

        val idx = selection.indexOfFirst { it.uri == img.uri }
        val added: Boolean
        if (idx >= 0) {
            selection.removeAt(idx)
            added = false
        } else {
            if (selection.size >= maxSelection) {
                toast("Max $maxSelection photos")
                return
            }
            selection.add(img)
            added = true
        }
        imagesAdapter.updateSelection(selection.map { it.uri })
        updateSelectionBar()
        // RULE 1: collapsed tha → MIDDLE; middle/full tha → state wahin (RULE 2)
        if (added && currentState == State.COLLAPSED) goStateInternal(State.MIDDLE, null)
    }

    private fun clearSelection() {
        selection.clear()
        imagesAdapter.updateSelection(emptyList<Uri>())
        updateSelectionBar()
    }

    private fun updateSelectionBar() {
        val n = selection.size
        selectionBar.visibility = if (n > 0) VISIBLE else GONE
        if (n > 0) {
            tvSendCount.text = n.toString()
            Glide.with(barThumb).load(selection.first().uri).centerCrop().into(barThumb)
        }
    }

    private fun sendSelection() {
        if (selection.isEmpty()) return
        val caption = etCaption.text.toString().trim()
        hideKeyboardNow()
        // Sab selected images EK message mein jaate hain (uris list + ek caption)
        onImageSend?.invoke(selection.map { it.uri }, caption)
        clearSelection()
        closeEditor()
        etCaption.setText("")
        hidePanel()
    }

    // ---------------------------------------------------------------
    // EDITOR (fullscreen preview; tools abhi placeholders)
    // ---------------------------------------------------------------
    private fun openEditor() {
        if (selection.isEmpty()) return
        editorOpen = true
        edIndex = 0
        edCaption.setText(etCaption.text.toString())
        editorOverlay.visibility = VISIBLE
        editorOverlay.alpha = 0f
        editorOverlay.animate().alpha(1f).setDuration(160).start()
        renderEditor()
    }

    private fun renderEditor() {
        edStrip.removeAllViews()
        val size = dp(40f)
        selection.forEachIndexed { k, img ->
            val iv = ImageView(context)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginEnd = dp(8f)
            iv.layoutParams = lp
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            val cur = k == edIndex
            iv.setPadding(if (cur) dp(2f) else 0, if (cur) dp(2f) else 0, if (cur) dp(2f) else 0, if (cur) dp(2f) else 0)
            iv.setBackgroundColor(if (cur) 0xFF00A884.toInt() else 0x00000000)
            iv.alpha = if (cur) 1f else 0.55f
            Glide.with(iv).load(img.uri).centerCrop().into(iv)
            iv.setOnClickListener { edIndex = k; renderEditor() }
            edStrip.addView(iv)
        }
        Glide.with(edImage).load(selection[edIndex].uri).fitCenter().into(edImage)
        edCount.text = selection.size.toString()
        syncHdChip()
    }

    private fun closeEditor() {
        if (!editorOpen) return
        editorOpen = false
        etCaption.setText(edCaption.text.toString())
        editorOverlay.animate().alpha(0f).setDuration(140).withEndAction {
            editorOverlay.visibility = GONE
            editorOverlay.alpha = 1f
        }.start()
    }

    // ---------------------------------------------------------------
    // HD
    // ---------------------------------------------------------------
    private fun setHd(on: Boolean) {
        btnHd.isSelected = on
        btnHd.text = if (on) "HD ✓" else "HD"
        btnHd.setTextColor(if (on) 0xFF25D366.toInt() else 0xFFFFFFFF.toInt())
        syncHdChip()
    }

    private fun syncHdChip() {
        val on = btnHd.isSelected
        edHd.text = if (on) "HD ✓" else "HD"
        edHd.setTextColor(if (on) 0xFF25D366.toInt() else 0xFFFFFFFF.toInt())
    }

    // ---------------------------------------------------------------
    // Media loading (background thread — main-thread jank nahi)
    // ---------------------------------------------------------------
    private fun hasMediaPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33)
            android.Manifest.permission.READ_MEDIA_IMAGES
        else
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun loadImagesIfNeeded(force: Boolean) {
        if (imagesLoading) return
        if (imagesLoaded && !force) return
        if (!hasMediaPermission()) {
            onRequestMediaPermission?.invoke()
            return
        }
        imagesLoading = true
        ioExecutor.execute {
            val result = runCatching { MediaStoreHelper.getAllImages(context) }
            mainHandler.post {
                imagesLoading = false
                result.onFailure { t ->
                    if (t is SecurityException) {
                        onRequestMediaPermission?.invoke()
                    } else {
                        toast("Photos load nahi hui: ${t.javaClass.simpleName}")
                    }
                    return@post
                }
                val loaded = result.getOrDefault(emptyList())
                allImages = loaded
                imagesLoaded = true
                buckets = MediaStoreHelper.getBuckets(loaded)
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val list = if (selectedBucketId == null) allImages
        else allImages.filter { it.bucketId == selectedBucketId }
        imagesAdapter.update(list.map { RecentImage(it.uri) })
    }

    // ---------------------------------------------------------------
    // Recents ▾ album picker (+ Files box) — toggle / outside / drag close
    // ---------------------------------------------------------------
    private fun isAlbumOpen(): Boolean = albumSheet.visibility == VISIBLE

    private fun openAlbums() {
        if (!imagesLoaded) return
        // Sheet hil rahi ho to dropdown mat kholo (galat height / position ban jaati thi)
        if (stateAnimating || openingClosing || dragging) return

        // Sheet ke header ke THEEK neeche se bottom tak (chipak kar)
        albumHeightPx = computeAlbumHeight()
        val alp = albumSheet.layoutParams as FrameLayout.LayoutParams
        alp.height = albumHeightPx
        albumSheet.layoutParams = alp

        val rows = mutableListOf<MediaBucket>()
        rows.add(MediaBucket("", "Recents", allImages.firstOrNull()?.uri, allImages.size))
        rows.addAll(buckets)
        albumsAdapter.submit(rows, selectedBucketId)

        // Har baar khulte waqt list TOP (Recents) par hi dikhe
        (albumRecycler.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
            ?.scrollToPositionWithOffset(0, 0)

        // Pichli band hone ki animation (aur uska "GONE") turant rok do, phir khol do
        albumSheet.animate().cancel()
        albumSheet.translationY = albumHeightPx.toFloat()
        albumSheet.visibility = VISIBLE
        albumSheet.animate().translationY(0f).setDuration(200).start()
    }

    private fun closeAlbums() {
        if (!isAlbumOpen()) return
        albumSheet.animate().cancel()
        albumSheet.animate().translationY(albumHeightPx.toFloat()).setDuration(180).withEndAction {
            albumSheet.visibility = GONE
        }.start()
    }

    private fun onAlbumPicked(bucket: MediaBucket) {
        selectedBucketId = if (bucket.id.isEmpty()) null else bucket.id
        btnRecents.text = "${bucket.name} ▾"
        applyFilter()
        closeAlbums()
    }

    /** Pill se drag-close + list top-overscroll drag-close, list natively scroll karti hai. */
    private fun wireAlbumGestures() {
        albumDragZone.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { abDownY = ev.rawY; abDragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - abDownY
                    if (dy > touchSlop) abDragging = true
                    if (abDragging) albumSheet.translationY = max(0f, dy)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (abDragging) {
                        if (albumSheet.translationY > albumHeightPx * 0.25f) closeAlbums()
                        else albumSheet.animate().translationY(0f).setDuration(160).start()
                    }
                    abDragging = false
                    true
                }
                else -> false
            }
        }

        // List top par ho aur neeche kheencho → poori dropdown sheet finger ke saath neeche jaaye.
        // OnItemTouchListener ko HAR touch ka DOWN pehle milta hai (child ne pakda ho tab bhi).
        albumRecycler.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            private var downRawY = 0f
            private var takingOver = false

            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawY = e.rawY
                        takingOver = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!takingOver && e.rawY - downRawY > touchSlop && !rv.canScrollVertically(-1)) {
                            takingOver = true
                        }
                    }
                }
                return takingOver
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        albumSheet.translationY = max(0f, e.rawY - downRawY)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (takingOver) {
                            if (albumSheet.translationY > albumHeightPx * 0.25f) closeAlbums()
                            else albumSheet.animate().translationY(0f).setDuration(160).start()
                        }
                        takingOver = false
                    }
                }
            }

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
    }

    // ---------------------------------------------------------------
    // Cleanup / helpers
    // ---------------------------------------------------------------
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        watchedParent?.removeOnLayoutChangeListener(parentLayoutListener)
        watchedParent = null
        removeCallbacks(pendingOpenTimeout)
        removeCallbacks(kbCommitRunnable)
        animator?.cancel()
        stopTracking()
        ioExecutor.shutdown()
    }

    private fun hideKeyboardNow() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etCaption.windowToken, 0)
        imm.hideSoftInputFromWindow(edCaption.windowToken, 0)
        etCaption.clearFocus()
        edCaption.clearFocus()
    }

    private fun showKeyboard(target: EditText) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    private fun topRadii(r: Float) = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)

    private fun dp(v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    ).toInt()

    companion object {
        private const val MIN_FLING = 1000f
        private const val STRONG_FLING = 2600f
        private const val PREFS_NAME = "attach_panel_prefs"
        private const val KEY_KB_PX = "kb_px"
        private const val PENDING_OPEN_TIMEOUT_MS = 650L

        // true = peela debug box dikhega (bug pakadne ke liye). Abhi band hai.
        private const val DEBUG_OVERLAY = false
    }

    // ---------------------------------------------------------------
    // Album list adapter (inner — MediaStoreHelper.getBuckets ka data)
    // ---------------------------------------------------------------
    private class AlbumsAdapter(
        private var rows: List<MediaBucket>,
        val onPick: (MediaBucket) -> Unit
    ) : RecyclerView.Adapter<AlbumsAdapter.VH>() {
        private var selectedId: String? = null

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val cover: ImageView = v.findViewById(R.id.albumCover)
            val name: TextView = v.findViewById(R.id.albumName)
            val count: TextView = v.findViewById(R.id.albumCount)
        }

        fun submit(newRows: List<MediaBucket>, sel: String?) {
            rows = newRows
            selectedId = sel
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_album_row, parent, false))

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val b = rows[position]
            holder.name.text = b.name
            holder.count.text = b.count.toString()
            if (b.coverUri != null) {
                Glide.with(holder.cover).load(b.coverUri).centerCrop().into(holder.cover)
            } else {
                holder.cover.setImageDrawable(null)
            }
            holder.itemView.setOnClickListener { onPick(b) }
        }
    }
}
