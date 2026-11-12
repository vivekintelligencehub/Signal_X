package com.signalx

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

class AttachmentPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    enum class State { COLLAPSED, MIDDLE, FULL }
    private enum class Zone { BAR, OPTIONS, HEADER, SHEET_BODY, OUTSIDE }

    var onImageSend: ((List<Uri>, String) -> Unit)? = null
    var onRequestMediaPermission: (() -> Unit)? = null
    var onOptionClicked: ((String) -> Unit)? = null
    var onPanelVisibilityChanged: ((Boolean) -> Unit)? = null
    var keyboardHeightPx: Int = 0

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

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val imagesAdapter: RecentImagesAdapter
    private val albumsAdapter: AlbumsAdapter
    private var allImages: List<MediaImage> = emptyList()
    private var buckets: List<MediaBucket> = emptyList()
    private var selectedBucketId: String? = null
    private val selection = mutableListOf<RecentImage>()
    private val perImageCaptions = mutableListOf<String>()

    private var imagesLoaded = false
    private var imagesLoading = false
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val maxSelection = 30

    private var panelH = 0
    private var fullH = 0
    private var trackedW = 0
    private var kbPx = 0
    private var trayPx = 0
    private var optH = 0
    private var albumHeightPx = 0
    private var watchedParent: View? = null

    private val headerH: Float
        get() = (headerSection.height.takeIf { it > 0 } ?: dp(68f)).toFloat()

    private val fullTop: Float get() = 0f
    private val middleTop: Float get() = panelH * 0.35f
    private val collapsedTop: Float get() = (panelH - trayPx + optH).toFloat() - headerH
    private val hiddenTop: Float get() = panelH.toFloat()

    private var curTop = 0f
    private var currentState = State.COLLAPSED
    private var panelVisible = false

    private var openingClosing = false
    private var stateAnimating = false
    private var dragging = false
    private var animGeneration = 0

    private var pendingOpen = false
    private var pendingOpenBaseH = 0
    private val pendingOpenTimeout = Runnable {
        if (pendingOpen) { pendingOpen = false; startOpen() }
    }

    private var kbCandidate = 0
    private var lastWindowH = 0
    private var lastWindowChangeAt = 0L
    private val kbCommitRunnable = Runnable {
        val c = kbCandidate
        if (c > 0 && (c > kbPx || c < kbPx * 0.8f) && fullH - lastWindowH == c) {
            kbPx = c; prefs.edit().putInt(KEY_KB_PX, c).apply(); applySizing()
        }
    }

    private val touchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop }
    private var downX = 0f; private var downY = 0f
    private var dragStartTop = 0f; private var dragStartState = State.COLLAPSED
    private var downZone = Zone.OUTSIDE
    private var velocityTracker: VelocityTracker? = null
    private var animator: ValueAnimator? = null

    private var pendingCollapseFrom: State? = null
    private var contentPushView: View? = null
    private var pushOriginalBottom = 0
    private var abDownY = 0f; private var abDragging = false
    private var editorOpen = false; private var edIndex = 0

    private val headerBg = GradientDrawable()
    private val bodyBg   = GradientDrawable()
    private val sheetColor = 0xFF11181C.toInt()
    private var gridScrollLocked = true

    private val parentLayoutListener =
        View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> trackWindowSize(v.width, v.height) }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_attachment_sheet, this, true)
        isClickable = false

        scrimView            = findViewById(R.id.scrimView)
        optionsContainer     = findViewById(R.id.optionsContainer)
        gallerySheet         = findViewById(R.id.gallerySheet)
        headerSection        = findViewById(R.id.headerSection)
        btnCloseGallery      = findViewById(R.id.btnCloseGallery)
        btnRecents           = findViewById(R.id.btnRecents)
        btnHd                = findViewById(R.id.btnHd)
        galleryRecycler      = findViewById(R.id.galleryRecycler)
        selectionBar         = findViewById(R.id.selectionBar)
        barThumb             = findViewById(R.id.barThumb)
        capEmoji             = findViewById(R.id.capEmoji)
        etCaption            = findViewById(R.id.etCaption)
        btnSendImages        = findViewById(R.id.btnSendImages)
        tvSendCount          = findViewById(R.id.tvSendCount)
        discardPopup         = findViewById(R.id.discardPopup)
        dpThumbs             = findViewById(R.id.dpThumbs)
        btnDpCancel          = findViewById(R.id.btnDpCancel)
        btnDpDiscard         = findViewById(R.id.btnDpDiscard)
        albumSheet           = findViewById(R.id.albumSheet)
        albumDragZone        = findViewById(R.id.albumDragZone)
        albumRecycler        = findViewById(R.id.albumRecycler)
        fileBox              = findViewById(R.id.fileBox)
        editorOverlay        = findViewById(R.id.editorOverlay)
        edClose              = findViewById(R.id.edClose)
        edStrip              = findViewById(R.id.edStrip)
        edHd                 = findViewById(R.id.edHd)
        edImage              = findViewById(R.id.edImage)
        edCaption            = findViewById(R.id.edCaption)
        edCount              = findViewById(R.id.edCount)

        kbPx = prefs.getInt(KEY_KB_PX, 0)

        scrimView.elevation       = dp(1f).toFloat()
        optionsContainer.elevation = dp(2f).toFloat()
        gallerySheet.elevation    = dp(4f).toFloat()
        selectionBar.elevation    = dp(6f).toFloat()
        albumSheet.elevation      = dp(8f).toFloat()
        discardPopup.elevation    = dp(10f).toFloat()
        editorOverlay.elevation   = dp(12f).toFloat()

        headerBg.setColor(sheetColor); headerBg.cornerRadii = topRadii(dp(16f).toFloat())
        headerSection.background = headerBg
        bodyBg.setColor(sheetColor); bodyBg.alpha = 255
        galleryRecycler.background = bodyBg

        scrimView.visibility = GONE
        scrimView.setOnClickListener { collapseThenAsk(currentState) }

        imagesAdapter = RecentImagesAdapter(mutableListOf()) { img -> onThumbTap(img) }
        gridScrollLocked = true
        galleryRecycler.layoutManager = object : GridLayoutManager(context, 4) {
            override fun canScrollVertically(): Boolean = !gridScrollLocked && super.canScrollVertically()
        }
        galleryRecycler.adapter = imagesAdapter
        galleryRecycler.overScrollMode = View.OVER_SCROLL_NEVER

        albumsAdapter = AlbumsAdapter(emptyList()) { bucket -> onAlbumPicked(bucket) }
        albumRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
        albumRecycler.adapter = albumsAdapter

        wireClicks(); wireAlbumGestures()

        optionsContainer.getChildAt(optionsContainer.childCount - 1)?.let { included ->
            (included.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.height = LinearLayout.LayoutParams.WRAP_CONTENT; lp.weight = 0f
            }
        }
        optionsContainer.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) post { applySizing() }
        }

        headerSection.alpha = 0f; headerSection.visibility = INVISIBLE
        optionsContainer.alpha = 0f; curTop = hiddenTop

        if (DEBUG_OVERLAY) {
            val tv = TextView(context); tv.setTextColor(0xFFFFFF00.toInt()); tv.setBackgroundColor(0xB3000000.toInt())
            tv.textSize = 9f; tv.typeface = Typeface.MONOSPACE; tv.setPadding(dp(4f), dp(2f), dp(4f), dp(2f))
            tv.isClickable = false; tv.elevation = dp(20f).toFloat()
            val dlp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            dlp.gravity = Gravity.TOP or Gravity.START; dlp.topMargin = dp(48f)
            addView(tv, dlp); dbgView = tv
        }
    }

    private fun wireClicks() {
        findViewById<View>(R.id.cellGallery).setOnClickListener { if (currentState != State.FULL) goState(State.MIDDLE) }
        findViewById<View>(R.id.cellLocation)?.setOnClickListener { onOptionClicked?.invoke("location") }
        findViewById<View>(R.id.cellContact)?.setOnClickListener  { onOptionClicked?.invoke("contact") }
        findViewById<View>(R.id.cellDocument)?.setOnClickListener { onOptionClicked?.invoke("document") }
        findViewById<View>(R.id.cellPoll)?.setOnClickListener     { onOptionClicked?.invoke("poll") }
        findViewById<View>(R.id.cellAudio)?.setOnClickListener    { onOptionClicked?.invoke("audio") }
        findViewById<View>(R.id.cellMembers)?.setOnClickListener  { onOptionClicked?.invoke("members") }
        findViewById<View>(R.id.cellSetting)?.setOnClickListener  { onOptionClicked?.invoke("setting") }

        btnCloseGallery.setOnClickListener { collapseThenAsk(currentState) }
        btnRecents.setOnClickListener { if (isAlbumOpen()) closeAlbums() else openAlbums() }
        btnHd.setOnClickListener {
            val on = !btnHd.isSelected; setHd(on)
            toast(if (on) "Items set to HD quality" else "Items set to standard quality")
        }
        edHd.setOnClickListener { btnHd.performClick() }
        fileBox.setOnClickListener { closeAlbums(); onOptionClicked?.invoke("document") }

        btnSendImages.setOnClickListener { sendSelection() }
        capEmoji.setOnClickListener { etCaption.requestFocus(); showKeyboard(etCaption) }
        barThumb.setOnClickListener { openEditor() }

        btnDpCancel.setOnClickListener {
            hideDiscardPopup()
            val back = pendingCollapseFrom ?: State.MIDDLE; pendingCollapseFrom = null; goState(back)
        }
        btnDpDiscard.setOnClickListener {
            hideDiscardPopup(); pendingCollapseFrom = null
            clearSelection(); resetGalleryToLatest(); hideKeyboardNow()
        }

        edClose.setOnClickListener { closeEditor() }
        findViewById<TextView>(R.id.toolUndo).setOnClickListener    { toolPhase2("Undo") }
        findViewById<TextView>(R.id.toolRotate).setOnClickListener  { toolPhase2("Rotate") }
        findViewById<TextView>(R.id.toolCrop).setOnClickListener    { toolPhase2("Crop") }
        findViewById<TextView>(R.id.toolSticker).setOnClickListener { toolPhase2("Stickers") }
        findViewById<TextView>(R.id.toolText).setOnClickListener    { toolPhase2("Text") }
        findViewById<TextView>(R.id.toolDraw).setOnClickListener    { toolPhase2("Draw") }
        findViewById<View>(R.id.edSend).setOnClickListener          { sendSelection() }
        findViewById<TextView>(R.id.edCapEmoji).setOnClickListener  { edCaption.requestFocus(); showKeyboard(edCaption) }
    }

    private fun toolPhase2(name: String) = toast("$name \u2014 option rakha hai, working phase-2")

    fun isOpen(): Boolean = panelVisible || pendingOpen
    fun setContentPushView(v: View) { contentPushView = v; pushOriginalBottom = v.paddingBottom }

    fun showPanel() {
        if (panelVisible) { if (currentState != State.COLLAPSED) goState(State.COLLAPSED); return }
        if (pendingOpen) return
        syncHeightFromParent()
        if (panelH == 0) { post { showPanel() }; return }
        if (fullH > 0 && fullH - panelH > dp(120f)) {
            pendingOpen = true; pendingOpenBaseH = panelH
            hideKeyboardNow(); postDelayed(pendingOpenTimeout, PENDING_OPEN_TIMEOUT_MS)
            updateDebug(); return
        }
        startOpen()
    }

    private fun startOpen() {
        syncHeightFromParent(); if (panelH == 0) return
        pristineReset(); applySizing()
        panelVisible = true; currentState = State.COLLAPSED
        visibility = VISIBLE; onPanelVisibilityChanged?.invoke(true)
        loadImagesIfNeeded(force = false)
        curTop = hiddenTop; updatePositions(curTop); animateOpenClose(opening = true)
    }

    fun hidePanel() {
        if (pendingOpen) {
            pendingOpen = false; removeCallbacks(pendingOpenTimeout)
            visibility = GONE; onPanelVisibilityChanged?.invoke(false); updateDebug(); return
        }
        if (!panelVisible) return
        panelVisible = false; stopTracking(); closeEditor(); closeAlbums()
        hideDiscardPopup(); clearSelection(); pendingCollapseFrom = null
        resetGalleryToLatest(); currentState = State.COLLAPSED
        onPanelVisibilityChanged?.invoke(false); animateOpenClose(opening = false)
    }

    fun refreshImages() { imagesLoaded = false; loadImagesIfNeeded(force = true) }

    fun handleBackPress(): Boolean {
        if (!panelVisible) return false
        if (editorOpen) { closeEditor(); return true }
        if (isAlbumOpen()) { closeAlbums(); return true }
        if (discardPopup.visibility == VISIBLE) { btnDpCancel.performClick(); return true }
        if (selection.isNotEmpty() && currentState != State.COLLAPSED) { collapseThenAsk(currentState); return true }
        return when (currentState) {
            State.FULL      -> { goState(State.MIDDLE); true }
            State.MIDDLE    -> { goState(State.COLLAPSED); true }
            State.COLLAPSED -> { hidePanel(); true }
        }
    }

    private fun pristineReset() {
        animGeneration++; animator?.cancel(); animator = null
        openingClosing = false; stateAnimating = false; stopTracking()
        pendingCollapseFrom = null; clearSelection(); resetGalleryToLatest()
        discardPopup.animate().cancel(); discardPopup.visibility = GONE
        albumSheet.animate().cancel();   albumSheet.visibility   = GONE
        editorOverlay.animate().cancel(); editorOverlay.visibility = GONE
        editorOverlay.alpha = 1f; editorOpen = false
        gridScrollLocked = true; downZone = Zone.OUTSIDE
    }

    private fun resetGalleryToLatest() {
        if (selectedBucketId != null) {
            selectedBucketId = null; btnRecents.text = "Recents \u25be"
            if (imagesLoaded) applyFilter()
        }
        (galleryRecycler.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
            ?.scrollToPositionWithOffset(0, 0)
    }
    private fun onReachedCollapsed() {
        if (selection.isEmpty() && pendingCollapseFrom == null) resetGalleryToLatest()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (parent as? View)?.let { p -> watchedParent = p; p.addOnLayoutChangeListener(parentLayoutListener); trackWindowSize(p.width, p.height) }
    }
    private fun syncHeightFromParent() {
        (parent as? View)?.let { p -> if (p.height > 0) { panelH = p.height; trackWindowSize(p.width, p.height) } }
    }
    private fun trackWindowSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (h != lastWindowH) { lastWindowH = h; lastWindowChangeAt = SystemClock.uptimeMillis() }
        if (w != trackedW) { trackedW = w; fullH = h; return }
        if (h >= fullH) { fullH = h; kbCandidate = 0; removeCallbacks(kbCommitRunnable) }
        else {
            val diff = fullH - h
            if (diff > dp(120f) && diff <= (fullH * 0.6f).toInt()) {
                kbCandidate = diff; removeCallbacks(kbCommitRunnable); postDelayed(kbCommitRunnable, 280)
            }
        }
        if (pendingOpen && h - pendingOpenBaseH > dp(100f)) {
            post { if (pendingOpen) { pendingOpen = false; removeCallbacks(pendingOpenTimeout); startOpen() } }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh); panelH = h; trackWindowSize(w, h); applySizing()
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val h = bottom - top; if (h > 0 && h != panelH) { panelH = h; post { applySizing() } }
    }
    private fun computeAlbumHeight(): Int {
        val at = topOf(currentState) + headerH
        return (panelH - at).toInt().coerceIn(dp(220f), max(panelH, dp(220f)))
    }
    private fun applySizing() {
        if (panelH <= 0) return
        val baseH = max(panelH, fullH)
        optH = optionsContainer.height.takeIf { it > 0 } ?: dp(190f)
        val kb = if (kbPx > 0) kbPx.coerceIn(dp(180f), (baseH * 0.6f).toInt())
                 else (baseH * 0.345f).toInt().coerceIn(dp(230f), dp(340f))
        trayPx = min(max(kb, optH + dp(72f)), (baseH * 0.72f).toInt())
        val olp = optionsContainer.layoutParams as FrameLayout.LayoutParams
        val optTop = (panelH - trayPx).coerceAtLeast(0)
        if (olp.gravity != Gravity.TOP || olp.height != ViewGroup.LayoutParams.WRAP_CONTENT ||
            olp.topMargin != optTop || olp.bottomMargin != 0) {
            olp.gravity = Gravity.TOP; olp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            olp.topMargin = optTop; olp.bottomMargin = 0; optionsContainer.layoutParams = olp
        }
        val slp = scrimView.layoutParams as FrameLayout.LayoutParams
        if (slp.bottomMargin != trayPx) { slp.bottomMargin = trayPx; scrimView.layoutParams = slp }
        albumHeightPx = computeAlbumHeight()
        val alp = albumSheet.layoutParams as FrameLayout.LayoutParams
        if (alp.height != albumHeightPx) { alp.height = albumHeightPx; albumSheet.layoutParams = alp }
        val busy = openingClosing || stateAnimating || dragging
        if (!busy) {
            curTop = if (panelVisible) topOf(currentState) else hiddenTop
            updatePositions(curTop); if (panelVisible) applyPush(trayPx)
        }
        if (!isAlbumOpen()) albumSheet.translationY = albumHeightPx.toFloat()
        updateDebug()
    }
    private fun applyPush(px: Int) {
        val v = contentPushView ?: return; val target = pushOriginalBottom + px
        if (v.paddingBottom != target) v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, target)
    }
    private fun topOf(s: State): Float = when (s) { State.FULL -> fullTop; State.MIDDLE -> middleTop; State.COLLAPSED -> collapsedTop }
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun animateOpenClose(opening: Boolean) {
        val myGen = ++animGeneration; animator?.cancel(); animator = null
        openingClosing = true; stateAnimating = false
        val startFrac  = if (panelH > 0) curTop / panelH else 1f
        val pushStart  = ((contentPushView?.paddingBottom ?: 0) - pushOriginalBottom).coerceAtLeast(0)
        val optFrom    = optionsContainer.alpha; val optTo = if (opening) 1f else 0f
        val anim = ValueAnimator.ofFloat(0f, 1f)
        animator = anim; anim.duration = 240; anim.interpolator = DecelerateInterpolator(1.4f)
        anim.addUpdateListener { a ->
            if (myGen != animGeneration) return@addUpdateListener
            val t = a.animatedValue as Float
            updatePositions(if (opening) lerp(hiddenTop, collapsedTop, t) else lerp(startFrac * panelH, hiddenTop, t))
            optionsContainer.alpha = lerp(optFrom, optTo, t)
            applyPush(lerp(pushStart.toFloat(), (if (opening) trayPx else 0).toFloat(), t).toInt())
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (myGen != animGeneration) return
                openingClosing = false; animator = null
                if (opening) { currentState = State.COLLAPSED; gridScrollLocked = true; applySizing(); postDelayed({ applySizing() }, 350) }
                else { visibility = GONE; currentState = State.COLLAPSED; updatePositions(hiddenTop); optionsContainer.alpha = 0f; applyPush(0) }
                updateDebug()
            }
        })
        anim.start()
    }

    private fun zoneAt(x: Float, y: Float): Zone {
        if (selectionBar.visibility == VISIBLE && y >= selectionBar.top && y <= selectionBar.bottom) return Zone.BAR
        val st = curTop; val inSheet = y >= st; val inHeader = inSheet && y <= st + headerH
        val o = optionsContainer; val inOptions = y >= o.top && y <= o.bottom && y < st
        return when { inOptions -> Zone.OPTIONS; inHeader -> Zone.HEADER; inSheet -> Zone.SHEET_BODY; else -> Zone.OUTSIDE }
    }
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!panelVisible || editorOpen) return false
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = ev.x; downY = ev.y; dragStartTop = curTop; dragStartState = currentState; downZone = zoneAt(ev.x, ev.y)
        }
        if (openingClosing) return false
        if (isAlbumOpen()) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                val r = IntArray(2).also { albumSheet.getLocationOnScreen(it) }
                if (ev.rawY < r[1] || ev.rawY > r[1] + albumSheet.height) { closeAlbums(); return true }
            }
            return false
        }
        if (discardPopup.visibility == VISIBLE) return false
        if (ev.actionMasked == MotionEvent.ACTION_MOVE) {
            if (downZone == Zone.OUTSIDE || downZone == Zone.BAR) return false
            val dy = ev.y - downY; val dx = ev.x - downX
            if (downZone == Zone.SHEET_BODY && currentState == State.FULL) {
                if (dy > touchSlop && abs(dy) > abs(dx) && !galleryRecycler.canScrollVertically(-1)) { startTracking(ev); return true }
                return false
            }
            if (abs(dy) > touchSlop && abs(dy) > abs(dx)) { startTracking(ev); return true }
        }
        return false
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!panelVisible || discardPopup.visibility == VISIBLE) { if (dragging) stopTracking(); return false }
        if (isAlbumOpen()) return true
        if (downZone == Zone.OUTSIDE) return false
        val action = event.actionMasked
        if (!dragging) {
            if (action == MotionEvent.ACTION_MOVE && !openingClosing && downZone != Zone.BAR) {
                val dy = event.y - downY; val dx = event.x - downX
                if (abs(dy) > touchSlop && abs(dy) > abs(dx)) startTracking(event) else return true
            } else return true
        }
        when (action) {
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                updatePositions((dragStartTop + (event.y - downY)).coerceIn(fullTop, hiddenTop))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                var vy = 0f
                velocityTracker?.let { it.addMovement(event); it.computeCurrentVelocity(1000); vy = it.yVelocity }
                stopTracking(); settle(curTop, if (action == MotionEvent.ACTION_UP) vy else 0f, dragStartState)
            }
        }
        return true
    }
    private fun startTracking(ev: MotionEvent) { stopTracking(); velocityTracker = VelocityTracker.obtain(); velocityTracker?.addMovement(ev); dragging = true }
    private fun stopTracking()                 { velocityTracker?.recycle(); velocityTracker = null; dragging = false }

    private fun settle(y: Float, vy: Float, startedFrom: State) {
        val canDismiss = startedFrom == State.COLLAPSED
        val dismissZone = collapsedTop + (hiddenTop - collapsedTop) * 0.4f
        val target: State? = when {
            vy < -STRONG_FLING -> State.FULL
            vy >  STRONG_FLING -> if (canDismiss && y > dismissZone) null else State.COLLAPSED
            vy < -MIN_FLING    -> if (y > middleTop + touchSlop) State.MIDDLE else State.FULL
            vy >  MIN_FLING    -> when { y < middleTop - touchSlop -> State.MIDDLE; canDismiss && y > dismissZone -> null; else -> State.COLLAPSED }
            else -> {
                if (canDismiss && y > dismissZone && y > collapsedTop + touchSlop) null
                else { val dF = abs(y-fullTop); val dM = abs(y-middleTop); val dC = abs(y-collapsedTop)
                    when { dF <= dM && dF <= dC -> State.FULL; dM <= dC -> State.MIDDLE; else -> State.COLLAPSED } }
            }
        }
        when { target == null -> hidePanel(); target == State.COLLAPSED -> collapseThenAsk(startedFrom); else -> goState(target) }
    }

    fun goState(state: State) = goStateInternal(state, null)
    private fun goStateInternal(state: State, onEnd: (() -> Unit)?) {
        val myGen = ++animGeneration; animator?.cancel(); animator = null
        openingClosing = false; stateAnimating = false; gridScrollLocked = state != State.FULL
        val startFrac = if (panelH > 0) curTop / panelH else 0f; val to = topOf(state)
        if (abs(to - curTop) < 1f) {
            currentState = state; updatePositions(to); if (state == State.COLLAPSED) onReachedCollapsed(); onEnd?.invoke(); return
        }
        stateAnimating = true
        val dist = abs(to - curTop)
        val anim = ValueAnimator.ofFloat(0f, 1f); animator = anim
        anim.duration = (170 + (dist / panelH.coerceAtLeast(1)) * 160).toLong().coerceIn(170, 340)
        anim.interpolator = DecelerateInterpolator(1.5f)
        anim.addUpdateListener { a ->
            if (myGen != animGeneration) return@addUpdateListener
            updatePositions(lerp(startFrac * panelH, topOf(state), a.animatedValue as Float))
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (myGen != animGeneration) return
                stateAnimating = false; animator = null; currentState = state; updatePositions(topOf(state))
                if (state == State.COLLAPSED) onReachedCollapsed(); onEnd?.invoke(); updateDebug()
            }
        })
        anim.start()
    }

    private fun updatePositions(y: Float) {
        curTop = y; gallerySheet.translationY = y
        val revealSpan = (collapsedTop - middleTop).coerceAtLeast(1f)
        val reveal = ((collapsedTop - y) / revealSpan * 1.15f).coerceIn(0f, 1f)
        headerSection.alpha = reveal; headerSection.visibility = if (reveal <= 0.02f) INVISIBLE else VISIBLE
        scrimView.alpha = reveal * 0.55f; scrimView.visibility = if (reveal > 0.05f && panelVisible) VISIBLE else GONE
        val ff = ((middleTop - y) / (middleTop - fullTop).coerceAtLeast(1f)).coerceIn(0f, 1f)
        headerBg.cornerRadii = topRadii(dp(16f) * (1f - ff)); updateDebug()
    }
    private fun updateDebug() {
        if (!DEBUG_OVERLAY) return
        dbgView?.text = "st=$currentState vis=$panelVisible pend=$pendingOpen\ntop=${curTop.toInt()} H=$panelH full=$fullH\nkb=$kbPx tray=$trayPx opt=$optH hdr=${headerH.toInt()}\noc=$openingClosing sa=$stateAnimating dr=$dragging sel=${selection.size} alb=${isAlbumOpen()}"
    }

    private fun collapseThenAsk(fromState: State) {
        if (selection.isEmpty() || fromState == State.COLLAPSED) { goStateInternal(State.COLLAPSED, null); return }
        pendingCollapseFrom = fromState; goStateInternal(State.COLLAPSED) { showDiscardPopup() }
    }
    private fun showDiscardPopup() {
        if (pendingCollapseFrom == null) return
        dpThumbs.removeAllViews()
        val size = dp(46f)
        selection.take(5).forEach { img ->
            val iv = ImageView(context); val lp = LinearLayout.LayoutParams(size, size).also { it.marginEnd = dp(6f) }
            iv.layoutParams = lp; iv.scaleType = ImageView.ScaleType.CENTER_CROP
            Glide.with(iv).load(img.uri).centerCrop().into(iv); dpThumbs.addView(iv)
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

    private fun onThumbTap(img: RecentImage) {
        if (openingClosing || pendingOpen) return
        if (SystemClock.uptimeMillis() - lastWindowChangeAt < 250) return
        val idx = selection.indexOfFirst { it.uri == img.uri }
        val added: Boolean
        if (idx >= 0) {
            selection.removeAt(idx)
            if (idx < perImageCaptions.size) perImageCaptions.removeAt(idx)
            added = false
        } else {
            if (selection.size >= maxSelection) { toast("Max $maxSelection photos"); return }
            selection.add(img); perImageCaptions.add(""); added = true
        }
        imagesAdapter.updateSelection(selection.map { it.uri }); updateSelectionBar()
        if (added && currentState == State.COLLAPSED) goStateInternal(State.MIDDLE, null)
    }

    private fun clearSelection() {
        selection.clear(); perImageCaptions.clear()
        imagesAdapter.updateSelection(emptyList<Uri>()); updateSelectionBar()
    }
    private fun updateSelectionBar() {
        val n = selection.size; selectionBar.visibility = if (n > 0) VISIBLE else GONE
        if (n > 0) { tvSendCount.text = n.toString(); Glide.with(barThumb).load(selection.first().uri).centerCrop().into(barThumb) }
    }

    private fun saveCurrentEditorCaption() { if (edIndex < perImageCaptions.size) perImageCaptions[edIndex] = edCaption.text.toString() }
    private fun syncCaptionSize() {
        while (perImageCaptions.size < selection.size) perImageCaptions.add("")
        while (perImageCaptions.size > selection.size) perImageCaptions.removeLastOrNull()
    }

    // -----------------------------------------------------------------------
    // Send — caption storage format:
    //   SINGLE image   : plain text (koi separator nahi)
    //   DEFAULT system : plain text (koi separator nahi) — sab images par same caption
    //   PER-IMAGE      : CAP_SEPARATOR se joined — alag-alag captions
    // Bubble aur Viewer is format se samajhte hain.
    // -----------------------------------------------------------------------
    private fun sendSelection() {
        if (selection.isEmpty()) return
        if (editorOpen) saveCurrentEditorCaption()
        syncCaptionSize()

        val quick = etCaption.text.toString().trim()
        val allBlank = perImageCaptions.all { it.isBlank() }

        val finalCaption: String = when {
            // Single image: perImageCaptions[0] ya etCaption
            selection.size == 1 ->
                perImageCaptions.getOrElse(0) { "" }.ifBlank { quick }

            // DEFAULT SYSTEM: direct caption → ek baar store karo (koi separator nahi)
            // Bubble mein ek dikhega, Viewer mein sab pages par same dikhega
            allBlank && quick.isNotBlank() -> quick

            // PER-IMAGE: editor se alag-alag captions set hain → separator ke saath store karo
            !allBlank -> perImageCaptions.take(selection.size).joinToString(CAP_SEPARATOR)

            // Koi caption nahi
            else -> ""
        }

        hideKeyboardNow()
        onImageSend?.invoke(selection.map { it.uri }, finalCaption)
        clearSelection(); closeEditor(); etCaption.setText(""); hidePanel()
    }

    private fun openEditor() {
        if (selection.isEmpty()) return
        syncCaptionSize()
        val quick = etCaption.text.toString().trim()
        if (perImageCaptions.all { it.isBlank() } && quick.isNotBlank()) {
            for (i in perImageCaptions.indices) perImageCaptions[i] = quick
        }
        editorOpen = true; edIndex = 0
        edCaption.setText(perImageCaptions.getOrElse(0) { "" })
        edCaption.hint = captionHint(0)
        editorOverlay.visibility = VISIBLE; editorOverlay.alpha = 0f
        editorOverlay.animate().alpha(1f).setDuration(160).start()
        renderEditor()
    }
    private fun renderEditor() {
        edStrip.removeAllViews(); val size = dp(40f)
        selection.forEachIndexed { k, img ->
            val iv = ImageView(context)
            val lp = LinearLayout.LayoutParams(size, size).also { it.marginEnd = dp(8f) }
            iv.layoutParams = lp; iv.scaleType = ImageView.ScaleType.CENTER_CROP
            val cur = k == edIndex
            iv.setPadding(if (cur) dp(2f) else 0, if (cur) dp(2f) else 0, if (cur) dp(2f) else 0, if (cur) dp(2f) else 0)
            iv.setBackgroundColor(if (cur) 0xFF00A884.toInt() else 0x00000000); iv.alpha = if (cur) 1f else 0.55f
            Glide.with(iv).load(img.uri).centerCrop().into(iv)
            iv.setOnClickListener {
                saveCurrentEditorCaption(); edIndex = k
                edCaption.setText(perImageCaptions.getOrElse(k) { "" })
                edCaption.hint = captionHint(k); renderEditor()
            }
            edStrip.addView(iv)
        }
        Glide.with(edImage).load(selection[edIndex].uri).fitCenter().into(edImage)
        edCount.text = selection.size.toString(); syncHdChip()
    }
    private fun captionHint(index: Int): String =
        if (selection.size > 1) "Photo ${index + 1} ke liye caption..." else "Caption likhein..."
    private fun closeEditor() {
        if (!editorOpen) return
        saveCurrentEditorCaption(); editorOpen = false
        if (selection.size == 1) etCaption.setText(perImageCaptions.getOrElse(0) { "" })
        editorOverlay.animate().alpha(0f).setDuration(140).withEndAction { editorOverlay.visibility = GONE; editorOverlay.alpha = 1f }.start()
    }

    private fun setHd(on: Boolean) {
        btnHd.isSelected = on; btnHd.text = if (on) "HD \u2713" else "HD"
        btnHd.setTextColor(if (on) 0xFF25D366.toInt() else 0xFFFFFFFF.toInt()); syncHdChip()
    }
    private fun syncHdChip() {
        val on = btnHd.isSelected; edHd.text = if (on) "HD \u2713" else "HD"
        edHd.setTextColor(if (on) 0xFF25D366.toInt() else 0xFFFFFFFF.toInt())
    }

    private fun hasMediaPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES
                   else android.Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }
    private fun loadImagesIfNeeded(force: Boolean) {
        if (imagesLoading) return; if (imagesLoaded && !force) return
        if (!hasMediaPermission()) { onRequestMediaPermission?.invoke(); return }
        imagesLoading = true
        ioExecutor.execute {
            val result = runCatching { MediaStoreHelper.getAllImages(context) }
            mainHandler.post {
                imagesLoading = false
                result.onFailure { t ->
                    if (t is SecurityException) onRequestMediaPermission?.invoke()
                    else toast("Photos load nahi hui: ${t.javaClass.simpleName}")
                    return@post
                }
                val loaded = result.getOrDefault(emptyList())
                allImages = loaded; imagesLoaded = true; buckets = MediaStoreHelper.getBuckets(loaded); applyFilter()
            }
        }
    }
    private fun applyFilter() {
        val list = if (selectedBucketId == null) allImages else allImages.filter { it.bucketId == selectedBucketId }
        imagesAdapter.update(list.map { RecentImage(it.uri) })
    }

    private fun isAlbumOpen(): Boolean = albumSheet.visibility == VISIBLE
    private fun openAlbums() {
        if (!imagesLoaded || stateAnimating || openingClosing || dragging) return
        albumHeightPx = computeAlbumHeight()
        val alp = albumSheet.layoutParams as FrameLayout.LayoutParams
        alp.height = albumHeightPx; albumSheet.layoutParams = alp
        val rows = mutableListOf(MediaBucket("", "Recents", allImages.firstOrNull()?.uri, allImages.size))
        rows.addAll(buckets); albumsAdapter.submit(rows, selectedBucketId)
        (albumRecycler.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
        albumSheet.animate().cancel()
        albumSheet.translationY = albumHeightPx.toFloat(); albumSheet.visibility = VISIBLE
        albumSheet.animate().translationY(0f).setDuration(200).start()
    }
    private fun closeAlbums() {
        if (!isAlbumOpen()) return; albumSheet.animate().cancel()
        albumSheet.animate().translationY(albumHeightPx.toFloat()).setDuration(180).withEndAction { albumSheet.visibility = GONE }.start()
    }
    private fun onAlbumPicked(bucket: MediaBucket) {
        selectedBucketId = if (bucket.id.isEmpty()) null else bucket.id
        btnRecents.text = "${bucket.name} \u25be"; applyFilter(); closeAlbums()
    }
    private fun wireAlbumGestures() {
        albumDragZone.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { abDownY = ev.rawY; abDragging = false; true }
                MotionEvent.ACTION_MOVE -> { val dy = ev.rawY - abDownY; if (dy > touchSlop) abDragging = true; if (abDragging) albumSheet.translationY = max(0f, dy); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (abDragging) { if (albumSheet.translationY > albumHeightPx * 0.25f) closeAlbums() else albumSheet.animate().translationY(0f).setDuration(160).start() }
                    abDragging = false; true
                }
                else -> false
            }
        }
        albumRecycler.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            private var downRawY = 0f; private var takingOver = false
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downRawY = e.rawY; takingOver = false }
                    MotionEvent.ACTION_MOVE -> { if (!takingOver && e.rawY - downRawY > touchSlop && !rv.canScrollVertically(-1)) takingOver = true }
                }; return takingOver
            }
            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_MOVE -> albumSheet.translationY = max(0f, e.rawY - downRawY)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (takingOver) { if (albumSheet.translationY > albumHeightPx * 0.25f) closeAlbums() else albumSheet.animate().translationY(0f).setDuration(160).start() }
                        takingOver = false
                    }
                }
            }
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        watchedParent?.removeOnLayoutChangeListener(parentLayoutListener); watchedParent = null
        removeCallbacks(pendingOpenTimeout); removeCallbacks(kbCommitRunnable)
        animator?.cancel(); stopTracking(); ioExecutor.shutdown()
    }
    private fun hideKeyboardNow() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etCaption.windowToken, 0); imm.hideSoftInputFromWindow(edCaption.windowToken, 0)
        etCaption.clearFocus(); edCaption.clearFocus()
    }
    private fun showKeyboard(target: EditText) {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
    }
    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    private fun topRadii(r: Float) = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    private fun dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    companion object {
        private const val MIN_FLING  = 1000f
        private const val STRONG_FLING = 2600f
        private const val PREFS_NAME = "attach_panel_prefs"
        private const val KEY_KB_PX  = "kb_px"
        private const val PENDING_OPEN_TIMEOUT_MS = 650L
        private const val DEBUG_OVERLAY = false
        const val CAP_SEPARATOR = "\u001E"
    }

    private class AlbumsAdapter(
        private var rows: List<MediaBucket>, val onPick: (MediaBucket) -> Unit
    ) : RecyclerView.Adapter<AlbumsAdapter.VH>() {
        private var selectedId: String? = null
        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val cover: ImageView = v.findViewById(R.id.albumCover)
            val name: TextView   = v.findViewById(R.id.albumName)
            val count: TextView  = v.findViewById(R.id.albumCount)
        }
        fun submit(newRows: List<MediaBucket>, sel: String?) { rows = newRows; selectedId = sel; notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_album_row, parent, false))
        override fun getItemCount() = rows.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val b = rows[position]; holder.name.text = b.name; holder.count.text = b.count.toString()
            if (b.coverUri != null) Glide.with(holder.cover).load(b.coverUri).centerCrop().into(holder.cover)
            else holder.cover.setImageDrawable(null)
            holder.itemView.setOnClickListener { onPick(b) }
        }
    }
}
