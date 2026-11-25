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
import android.widget.SeekBar
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
    private val edOverlayContainer: FrameLayout
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

    // -------------------------------------------------------------------------
    // Caption — do BILKUL ALAG sources, koi overlap nahi:
    //
    //   SOURCE 1: etCaption (bottom bar)
    //       Editor kabhi nahi khula to yahi use hoga.
    //       Plain text store hota hai (koi separator nahi).
    //       Sab images par ek hi caption dikhta hai.
    //
    //   SOURCE 2: perImageCaptions (editor ke andar strip se)
    //       Jab bhi editor khulta hai, yahi source active ho jaata hai.
    //       etCaption bilkul ignore ho jaata hai.
    //       CAP_SEPARATOR se join hokar store hota hai.
    //       Viewer mein har image apna caption dekhta hai.
    // -------------------------------------------------------------------------
    private val perImageCaptions = mutableListOf<String>()

    /**
     * TRUE = is selection ke liye editor khula tha → SOURCE 2 use karo.
     * FALSE = editor kabhi nahi khula → SOURCE 1 (etCaption) use karo.
     */
    private var editorWasOpened = false

    /**
     * Har selected image ka apna rotation (clockwise degrees: 0, 90, 180, 270).
     * perImageCaptions jaisa hi parallel list — selection[i] ka rotation = perImageRotations[i].
     * Sirf editor ke andar "Rotate" button se badalta hai.
     */
    private val perImageRotations = mutableListOf<Int>()

    /**
     * Har selected image ke text overlays (jo "Text" tool se daale gaye).
     * perImageCaptions/perImageRotations jaisa hi parallel list.
     * Har entry ek mutable list hai kyunki ek image par multiple text boxes ho sakte hain.
     */
    private val perImageTextEntries = mutableListOf<MutableList<EditorTextEntry>>()

    private var imagesLoaded  = false
    private var imagesLoading = false
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val maxSelection = 30

    private var panelH = 0; private var fullH = 0; private var trackedW = 0
    private var kbPx = 0; private var trayPx = 0; private var optH = 0
    private var albumHeightPx = 0; private var watchedParent: View? = null

    private val headerH: Float get() = (headerSection.height.takeIf { it > 0 } ?: dp(68f)).toFloat()
    private val fullTop:      Float get() = 0f
    private val middleTop:    Float get() = panelH * 0.35f
    private val collapsedTop: Float get() = (panelH - trayPx + optH).toFloat() - headerH
    private val hiddenTop:    Float get() = panelH.toFloat()

    private var curTop = 0f; private var currentState = State.COLLAPSED; private var panelVisible = false
    private var openingClosing = false; private var stateAnimating = false; private var dragging = false
    private var animGeneration = 0

    private var pendingOpen = false; private var pendingOpenBaseH = 0
    private val pendingOpenTimeout = Runnable { if (pendingOpen) { pendingOpen = false; startOpen() } }

    private var kbCandidate = 0; private var lastWindowH = 0; private var lastWindowChangeAt = 0L
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
    private var velocityTracker: VelocityTracker? = null; private var animator: ValueAnimator? = null

    private var pendingCollapseFrom: State? = null
    private var contentPushView: View? = null; private var pushOriginalBottom = 0
    private var abDownY = 0f; private var abDragging = false
    private var editorOpen = false; private var edIndex = 0

    private val headerBg = GradientDrawable(); private val bodyBg = GradientDrawable()
    private val sheetColor = 0xFF11181C.toInt(); private var gridScrollLocked = true

    private val parentLayoutListener =
        View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> trackWindowSize(v.width, v.height) }

    // -------------------------------------------------------------------------
    // Text tool — floating toolbar + drag-to-delete state
    // -------------------------------------------------------------------------
    /** Abhi jo text box select/edit ho raha hai uska EditText (null = koi nahi). */
    private var activeTextView: EditText? = null
    private var activeTextEntry: EditorTextEntry? = null

    /** Floating toolbar jo active text ke upar/neeche aata hai: B / I / U / size / color-dots. */
    private var textToolbar: LinearLayout? = null
    private var textToolbarBold: TextView? = null
    private var textToolbarItalic: TextView? = null
    private var textToolbarUnderline: TextView? = null
    private var textToolbarSizeBar: SeekBar? = null
    private var textToolbarColorDots: View? = null

    /** Niche se khulne wala rangeen palette (color-dots button dabane par). */
    private var colorPalettePopup: LinearLayout? = null

    /** Long-press-and-slide dustbin — sirf drag ke dauraan dikhta hai. */
    private var dustbinView: TextView? = null
    private var isDraggingToDelete = false

    init {
        LayoutInflater.from(context).inflate(R.layout.view_attachment_sheet, this, true)
        isClickable = false

        scrimView         = findViewById(R.id.scrimView)
        optionsContainer  = findViewById(R.id.optionsContainer)
        gallerySheet      = findViewById(R.id.gallerySheet)
        headerSection     = findViewById(R.id.headerSection)
        btnCloseGallery   = findViewById(R.id.btnCloseGallery)
        btnRecents        = findViewById(R.id.btnRecents)
        btnHd             = findViewById(R.id.btnHd)
        galleryRecycler   = findViewById(R.id.galleryRecycler)
        selectionBar      = findViewById(R.id.selectionBar)
        barThumb          = findViewById(R.id.barThumb)
        capEmoji          = findViewById(R.id.capEmoji)
        etCaption         = findViewById(R.id.etCaption)
        btnSendImages     = findViewById(R.id.btnSendImages)
        tvSendCount       = findViewById(R.id.tvSendCount)
        discardPopup      = findViewById(R.id.discardPopup)
        dpThumbs          = findViewById(R.id.dpThumbs)
        btnDpCancel       = findViewById(R.id.btnDpCancel)
        btnDpDiscard      = findViewById(R.id.btnDpDiscard)
        albumSheet        = findViewById(R.id.albumSheet)
        albumDragZone     = findViewById(R.id.albumDragZone)
        albumRecycler     = findViewById(R.id.albumRecycler)
        fileBox           = findViewById(R.id.fileBox)
        editorOverlay     = findViewById(R.id.editorOverlay)
        edClose           = findViewById(R.id.edClose)
        edStrip           = findViewById(R.id.edStrip)
        edHd              = findViewById(R.id.edHd)
        edImage           = findViewById(R.id.edImage)
        edOverlayContainer = findViewById(R.id.edOverlayContainer)
        edCaption         = findViewById(R.id.edCaption)
        edCount           = findViewById(R.id.edCount)

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
        galleryRecycler.adapter = imagesAdapter; galleryRecycler.overScrollMode = View.OVER_SCROLL_NEVER

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
            val tv = TextView(context)
            tv.setTextColor(0xFFFFFF00.toInt()); tv.setBackgroundColor(0xB3000000.toInt())
            tv.textSize = 9f; tv.typeface = Typeface.MONOSPACE
            tv.setPadding(dp(4f), dp(2f), dp(4f), dp(2f))
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
        findViewById<TextView>(R.id.toolRotate).setOnClickListener  { rotateCurrentEditorImage() }
        findViewById<TextView>(R.id.toolCrop).setOnClickListener    { toolPhase2("Crop") }
        findViewById<TextView>(R.id.toolSticker).setOnClickListener { toolPhase2("Stickers") }
        findViewById<TextView>(R.id.toolText).setOnClickListener    { addTextOverlay() }
        findViewById<TextView>(R.id.toolDraw).setOnClickListener    { toolPhase2("Draw") }
        findViewById<View>(R.id.edSend).setOnClickListener          { sendSelection() }
        findViewById<TextView>(R.id.edCapEmoji).setOnClickListener  { edCaption.requestFocus(); showKeyboard(edCaption) }
    }

    private fun toolPhase2(name: String) = toast("$name \u2014 option rakha hai, working phase-2")

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------
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
        if (editorOpen)                      { closeEditor(); return true }
        if (isAlbumOpen())                   { closeAlbums(); return true }
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

    // -------------------------------------------------------------------------
    // Gallery
    // -------------------------------------------------------------------------
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

    // -------------------------------------------------------------------------
    // Window / keyboard
    // -------------------------------------------------------------------------
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (parent as? View)?.let { p ->
            watchedParent = p; p.addOnLayoutChangeListener(parentLayoutListener); trackWindowSize(p.width, p.height)
        }
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

    // -------------------------------------------------------------------------
    // Sizing
    // -------------------------------------------------------------------------
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

    // -------------------------------------------------------------------------
    // Open / close animation
    // -------------------------------------------------------------------------
    private fun animateOpenClose(opening: Boolean) {
        val myGen = ++animGeneration; animator?.cancel(); animator = null
        openingClosing = true; stateAnimating = false
        val startFrac = if (panelH > 0) curTop / panelH else 1f
        val pushStart = ((contentPushView?.paddingBottom ?: 0) - pushOriginalBottom).coerceAtLeast(0)
        val optFrom   = optionsContainer.alpha; val optTo = if (opening) 1f else 0f
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

    // -------------------------------------------------------------------------
    // Touch
    // -------------------------------------------------------------------------
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
        dbgView?.text = "st=$currentState vis=$panelVisible pend=$pendingOpen\ntop=${curTop.toInt()} H=$panelH full=$fullH\nkb=$kbPx tray=$trayPx opt=$optH hdr=${headerH.toInt()}\noc=$openingClosing sa=$stateAnimating dr=$dragging sel=${selection.size} editorOpened=$editorWasOpened"
    }

    // -------------------------------------------------------------------------
    // RULE 3
    // -------------------------------------------------------------------------
    private fun collapseThenAsk(fromState: State) {
        if (selection.isEmpty() || fromState == State.COLLAPSED) { goStateInternal(State.COLLAPSED, null); return }
        pendingCollapseFrom = fromState; goStateInternal(State.COLLAPSED) { showDiscardPopup() }
    }
    private fun showDiscardPopup() {
        if (pendingCollapseFrom == null) return
        dpThumbs.removeAllViews(); val size = dp(46f)
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

    // -------------------------------------------------------------------------
    // Photo tap
    // -------------------------------------------------------------------------
    private fun onThumbTap(img: RecentImage) {
        if (openingClosing || pendingOpen) return
        if (SystemClock.uptimeMillis() - lastWindowChangeAt < 250) return
        val idx = selection.indexOfFirst { it.uri == img.uri }
        val added: Boolean
        if (idx >= 0) {
            selection.removeAt(idx)
            if (idx < perImageCaptions.size) perImageCaptions.removeAt(idx)
            if (idx < perImageRotations.size) perImageRotations.removeAt(idx)
            if (idx < perImageTextEntries.size) perImageTextEntries.removeAt(idx)
            added = false
        } else {
            if (selection.size >= maxSelection) { toast("Max $maxSelection photos"); return }
            selection.add(img); perImageCaptions.add(""); perImageRotations.add(0)
            perImageTextEntries.add(mutableListOf()); added = true
        }
        imagesAdapter.updateSelection(selection.map { it.uri }); updateSelectionBar()
        if (added && currentState == State.COLLAPSED) goStateInternal(State.MIDDLE, null)
    }
    private fun clearSelection() {
        selection.clear()
        perImageCaptions.clear()
        perImageRotations.clear()
        perImageTextEntries.clear()
        editorWasOpened = false          // fresh selection = no editor yet
        imagesAdapter.updateSelection(emptyList<Uri>()); updateSelectionBar()
    }
    private fun updateSelectionBar() {
        val n = selection.size; selectionBar.visibility = if (n > 0) VISIBLE else GONE
        if (n > 0) { tvSendCount.text = n.toString(); Glide.with(barThumb).load(selection.first().uri).centerCrop().into(barThumb) }
    }

    // -------------------------------------------------------------------------
    // Caption helpers
    // -------------------------------------------------------------------------
    private fun saveCurrentEditorCaption() {
        if (edIndex < perImageCaptions.size) perImageCaptions[edIndex] = edCaption.text.toString()
    }
    private fun syncCaptionSize() {
        while (perImageCaptions.size < selection.size) perImageCaptions.add("")
        while (perImageCaptions.size > selection.size) perImageCaptions.removeLastOrNull()
        while (perImageRotations.size < selection.size) perImageRotations.add(0)
        while (perImageRotations.size > selection.size) perImageRotations.removeLastOrNull()
        while (perImageTextEntries.size < selection.size) perImageTextEntries.add(mutableListOf())
        while (perImageTextEntries.size > selection.size) perImageTextEntries.removeLastOrNull()
    }

    // -------------------------------------------------------------------------
    // Rotate (editor ke andar "Rotate" tool)
    // -------------------------------------------------------------------------

    /** Rotate button: current editor image ko 90° clockwise ghumao, animate karke, aur yaad rakho. */
    private fun rotateCurrentEditorImage() {
        if (!editorOpen) return
        if (edIndex >= perImageRotations.size) return

        val newRotation = (perImageRotations[edIndex] + 90) % 360
        perImageRotations[edIndex] = newRotation

        edImage.animate().rotationBy(90f).setDuration(220).start()
        renderEditor()
    }

    // =========================================================================
    // Text tool (editor ke andar "T" tool)
    //
    // Har text box edOverlayContainer ke andar ek EditText hai:
    //   - tap karke seedha type kar sakte ho (focus + keyboard khulta hai)
    //   - focus hote hi ek FLOATING TOOLBAR text ke paas aata hai: B / I / U /
    //     size-slider / color-dots-button
    //   - color-dots button dabane par neeche se ek palette khulta hai
    //   - LONG-PRESS karke drag karo to neeche-right ek dustbin aata hai;
    //     ungli dustbin ke andar chhodo to text delete, bahar chhodo to bach jaata hai
    //
    // Position hamesha edOverlayContainer ke fraction (0f..1f) mein save hoti hai.
    // =========================================================================

    private fun addTextOverlay() {
        if (!editorOpen) return
        if (edIndex >= perImageTextEntries.size) return

        val entry = EditorTextEntry(x = 0.5f, y = 0.5f, text = "")
        perImageTextEntries[edIndex].add(entry)
        renderTextOverlaysForCurrentImage()

        val lastChild = edOverlayContainer.getChildAt(edOverlayContainer.childCount - 1)
        (lastChild as? EditText)?.let { et ->
            et.requestFocus()
            showKeyboard(et)
        }
    }

    private fun renderTextOverlaysForCurrentImage() {
        edOverlayContainer.removeAllViews()
        activeTextView = null; activeTextEntry = null
        hideTextToolbar()
        if (edIndex >= perImageTextEntries.size) return
        val entries = perImageTextEntries[edIndex]
        entries.forEach { entry -> addDraggableTextView(entry) }
    }

    /** Ek EditorTextEntry ke current style ke hisaab se uski EditText par visually apply karo. */
    private fun applyEntryStyleToView(entry: EditorTextEntry, et: EditText) {
        et.setTextColor(entry.resolvedColor())
        et.textSize = entry.sizeSp
        val style = when {
            entry.isBold && entry.isItalic -> Typeface.BOLD_ITALIC
            entry.isBold -> Typeface.BOLD
            entry.isItalic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        et.setTypeface(Typeface.DEFAULT, style)
        et.paintFlags = if (entry.isUnderline)
            et.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        else
            et.paintFlags and android.graphics.Paint.UNDERLINE_TEXT_FLAG.inv()
    }

    private fun addDraggableTextView(entry: EditorTextEntry) {
        val et = EditText(context).apply {
            setText(entry.text)
            background = null
            isSingleLine = false
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
            gravity = Gravity.CENTER
            setHorizontallyScrolling(false)
            hint = "Type..."
            setHintTextColor(0x80FFFFFF.toInt())
        }
        applyEntryStyleToView(entry, et)

        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        et.layoutParams = lp
        edOverlayContainer.addView(et)

        edOverlayContainer.post {
            val cw = edOverlayContainer.width; val ch = edOverlayContainer.height
            if (cw > 0 && ch > 0) {
                et.x = (entry.x * cw) - (et.width / 2f).coerceAtLeast(0f)
                et.y = (entry.y * ch) - (et.height / 2f).coerceAtLeast(0f)
            }
        }

        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { entry.text = s?.toString() ?: "" }
        })

        // Focus milte hi iska apna floating toolbar dikhao; focus jaate hi hata do
        et.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) showTextToolbarFor(et, entry) else hideTextToolbarIfFor(et)
        }

        // Drag-to-move (normal) + Long-press-and-slide-to-delete (dustbin)
        var dLastX = 0f; var dLastY = 0f; var dMoved = false
        var longPressArmed = false
        val longPressRunnable = Runnable {
            longPressArmed = true
            showDustbin()
            et.alpha = 0.55f   // visual hint: ab drag-to-delete mode hai
        }

        et.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dLastX = ev.rawX; dLastY = ev.rawY; dMoved = false; longPressArmed = false
                    v.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    false   // DOWN ko EditText ko bhi jaane do (focus/cursor ke liye)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - dLastX; val dy = ev.rawY - dLastY
                    if (!dMoved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dMoved = true
                        v.removeCallbacks(longPressRunnable)
                    }
                    if (dMoved) {
                        v.x += dx; v.y += dy
                        dLastX = ev.rawX; dLastY = ev.rawY

                        if (longPressArmed) {
                            // Drag-to-delete mode: dustbin ke upar ho to highlight karo
                            isDraggingToDelete = true
                            val overDustbin = isViewOverDustbin(v)
                            dustbinView?.isSelected = overDustbin
                            dustbinView?.scaleX = if (overDustbin) 1.25f else 1f
                            dustbinView?.scaleY = if (overDustbin) 1.25f else 1f
                        } else {
                            // Normal move: position fraction mein save karo
                            val cw = edOverlayContainer.width; val ch = edOverlayContainer.height
                            if (cw > 0 && ch > 0) {
                                entry.x = ((v.x + v.width / 2f) / cw).coerceIn(0f, 1f)
                                entry.y = ((v.y + v.height / 2f) / ch).coerceIn(0f, 1f)
                            }
                        }
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPressRunnable)
                    val wasLongPressArmed = longPressArmed
                    val wasMoved = dMoved
                    longPressArmed = false; dMoved = false

                    if (isDraggingToDelete) {
                        isDraggingToDelete = false
                        val overDustbin = isViewOverDustbin(v)
                        hideDustbin()
                        v.alpha = 1f
                        if (overDustbin) {
                            deleteTextEntry(entry, et)
                            true
                        } else {
                            // Position jahan chhoda wahin save karo, bach gaya
                            val cw = edOverlayContainer.width; val ch = edOverlayContainer.height
                            if (cw > 0 && ch > 0) {
                                entry.x = ((v.x + v.width / 2f) / cw).coerceIn(0f, 1f)
                                entry.y = ((v.y + v.height / 2f) / ch).coerceIn(0f, 1f)
                            }
                            true
                        }
                    } else if (wasMoved) {
                        true   // normal drag khatam — EditText ka apna touch yahan nahi chahiye
                    } else {
                        false  // plain tap tha — EditText khud handle kare (focus/cursor)
                    }
                }
                else -> false
            }
        }
    }

    private fun deleteTextEntry(entry: EditorTextEntry, et: EditText) {
        if (edIndex < perImageTextEntries.size) perImageTextEntries[edIndex].remove(entry)
        edOverlayContainer.removeView(et)
        if (activeTextView === et) { activeTextView = null; activeTextEntry = null; hideTextToolbar() }
    }

    private fun isViewOverDustbin(textView: View): Boolean {
        val bin = dustbinView ?: return false
        val binLoc = IntArray(2); bin.getLocationOnScreen(binLoc)
        val txtLoc = IntArray(2); textView.getLocationOnScreen(txtLoc)
        val txtCenterX = txtLoc[0] + textView.width / 2
        val txtCenterY = txtLoc[1] + textView.height / 2
        val binCenterX = binLoc[0] + bin.width / 2
        val binCenterY = binLoc[1] + bin.height / 2
        val hitRadius = dp(40f)
        return abs(txtCenterX - binCenterX) < hitRadius && abs(txtCenterY - binCenterY) < hitRadius
    }

    // ---------------------------------------------------------------------
    // Dustbin (sirf long-press-drag ke dauraan dikhta hai, bottom-right)
    // ---------------------------------------------------------------------
    private fun showDustbin() {
        if (dustbinView != null) { dustbinView?.visibility = VISIBLE; return }
        val bin = TextView(context).apply {
            text = "\uD83D\uDDD1"   // 🗑
            textSize = 24f
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC000000.toInt())
        }
        val size = dp(56f)
        val lp = FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            marginEnd = dp(20f); bottomMargin = dp(20f)
        }
        bin.layoutParams = lp
        // GradientDrawable round background (dp() ke baad set karte hain taaki radius sahi ho)
        bin.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xCC000000.toInt())
        }
        editorOverlay.addView(bin)
        dustbinView = bin
    }
    private fun hideDustbin() {
        dustbinView?.let { editorOverlay.removeView(it) }
        dustbinView = null
    }

    // ---------------------------------------------------------------------
    // Floating toolbar: B / I / U / size-slider / color-dots-button
    // Active text ke upar ya neeche (jo bhi screen mein fit ho) aata hai
    // ---------------------------------------------------------------------
    private fun showTextToolbarFor(et: EditText, entry: EditorTextEntry) {
        activeTextView = et; activeTextEntry = entry
        ensureTextToolbarCreated()
        syncToolbarButtonsFromEntry(entry)
        positionToolbarNear(et)
        textToolbar?.visibility = VISIBLE
    }
    private fun hideTextToolbarIfFor(et: EditText) {
        if (activeTextView === et) {
            // Thoda delay — agar user ne toolbar ke kisi button ko tap kiya hai to
            // focus-loss turant aata hai, usse pehle button ka onClick chalna chahiye.
            postDelayed({ if (activeTextView === et) hideTextToolbar() }, 150)
        }
    }
    private fun hideTextToolbar() {
        textToolbar?.visibility = GONE
        colorPalettePopup?.let { editorOverlay.removeView(it) }
        colorPalettePopup = null
        activeTextView = null; activeTextEntry = null
    }

    private fun ensureTextToolbarCreated() {
        if (textToolbar != null) return

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            background = GradientDrawable().apply {
                cornerRadius = dp(22f).toFloat()
                setColor(0xFF222B32.toInt())
            }
        }
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        bar.layoutParams = lp

        fun styleBtn(label: String): TextView = TextView(context).apply {
            text = label; textSize = 15f; setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            val s = dp(34f)
            layoutParams = LinearLayout.LayoutParams(s, s)
            isClickable = true; isFocusable = true
        }

        val bBold = styleBtn("B").apply { setTypeface(typeface, Typeface.BOLD) }
        val bItalic = styleBtn("I").apply { setTypeface(typeface, Typeface.ITALIC) }
        val bUnderline = styleBtn("U").apply { paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG }

        val sizeBar = SeekBar(context).apply {
            max = (EditorTextEntry.MAX_SIZE_SP - EditorTextEntry.MIN_SIZE_SP).toInt()
            layoutParams = LinearLayout.LayoutParams(dp(90f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6f); marginEnd = dp(6f)
            }
        }

        val colorDots = View(context).apply {
            val s = dp(28f)
            layoutParams = LinearLayout.LayoutParams(s, s).apply { marginStart = dp(4f) }
            background = buildColorDotsDrawable()
            isClickable = true; isFocusable = true
        }

        bBold.setOnClickListener {
            val e = activeTextEntry ?: return@setOnClickListener
            e.isBold = !e.isBold
            activeTextView?.let { applyEntryStyleToView(e, it) }
            syncToolbarButtonsFromEntry(e)
        }
        bItalic.setOnClickListener {
            val e = activeTextEntry ?: return@setOnClickListener
            e.isItalic = !e.isItalic
            activeTextView?.let { applyEntryStyleToView(e, it) }
            syncToolbarButtonsFromEntry(e)
        }
        bUnderline.setOnClickListener {
            val e = activeTextEntry ?: return@setOnClickListener
            e.isUnderline = !e.isUnderline
            activeTextView?.let { applyEntryStyleToView(e, it) }
            syncToolbarButtonsFromEntry(e)
        }
        sizeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val e = activeTextEntry ?: return
                e.sizeSp = EditorTextEntry.MIN_SIZE_SP + progress
                activeTextView?.let { applyEntryStyleToView(e, it) }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                activeTextView?.let { positionToolbarNear(it) }  // size badla to toolbar reposition
            }
        })
        colorDots.setOnClickListener { toggleColorPalette() }

        bar.addView(bBold); bar.addView(bItalic); bar.addView(bUnderline)
        bar.addView(sizeBar); bar.addView(colorDots)

        editorOverlay.addView(bar)
        textToolbar = bar
        textToolbarBold = bBold; textToolbarItalic = bItalic; textToolbarUnderline = bUnderline
        textToolbarSizeBar = sizeBar; textToolbarColorDots = colorDots
    }

    private fun syncToolbarButtonsFromEntry(entry: EditorTextEntry) {
        textToolbarBold?.setBackgroundColor(if (entry.isBold) 0x55FFFFFF.toInt() else 0x00000000)
        textToolbarItalic?.setBackgroundColor(if (entry.isItalic) 0x55FFFFFF.toInt() else 0x00000000)
        textToolbarUnderline?.setBackgroundColor(if (entry.isUnderline) 0x55FFFFFF.toInt() else 0x00000000)
        textToolbarSizeBar?.progress = (entry.sizeSp - EditorTextEntry.MIN_SIZE_SP).toInt().coerceIn(0, textToolbarSizeBar?.max ?: 0)
    }

    /** Toolbar ko active EditText ke upar rakhta hai; jagah na ho to neeche. */
    private fun positionToolbarNear(et: EditText) {
        val bar = textToolbar ?: return
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val barW = bar.measuredWidth; val barH = bar.measuredHeight

        val etLoc = IntArray(2); et.getLocationInWindow(etLoc)
        val overlayLoc = IntArray(2); editorOverlay.getLocationInWindow(overlayLoc)
        val etTopInOverlay = etLoc[1] - overlayLoc[1]
        val etLeftInOverlay = etLoc[0] - overlayLoc[0]

        var top = etTopInOverlay - barH - dp(8f)
        if (top < 0) top = etTopInOverlay + et.height + dp(8f)   // upar jagah nahi — neeche rakho

        var left = etLeftInOverlay + (et.width / 2) - (barW / 2)
        left = left.coerceIn(dp(4f), (editorOverlay.width - barW - dp(4f)).coerceAtLeast(dp(4f)))

        (bar.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.leftMargin = left; lp.topMargin = top.coerceAtLeast(0)
            bar.layoutParams = lp
        }
    }

    // ---------------------------------------------------------------------
    // Color palette (color-dots button dabane par neeche se khulta hai)
    // ---------------------------------------------------------------------
    private fun buildColorDotsDrawable(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFFFFFFF.toInt())
            // Simple multi-color look: ek white circle par thoda border —
            // asli "rangeen dots" visual palette khulne par hi dikhta hai
            setStroke(dp(2f), 0xFF888888.toInt())
        }

    private fun toggleColorPalette() {
        if (colorPalettePopup != null) { closeColorPalette(); return }
        val entry = activeTextEntry ?: return

        val palette = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
            background = GradientDrawable().apply {
                topLeftRadius = dp(16f).toFloat(); topRightRadius = dp(16f).toFloat()
                setColor(0xFF1A2229.toInt())
            }
        }
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        }
        palette.layoutParams = lp

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        EditorTextEntry.TEXT_COLORS.forEachIndexed { idx, color ->
            val dot = View(context).apply {
                val s = dp(36f)
                layoutParams = LinearLayout.LayoutParams(s, s).apply {
                    marginStart = dp(6f); marginEnd = dp(6f)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(dp(2f), 0xFFFFFFFF.toInt())
                }
                setOnClickListener {
                    entry.colorIndex = idx; entry.customColor = null
                    activeTextView?.let { applyEntryStyleToView(entry, it) }
                }
            }
            row.addView(dot)
        }
        palette.addView(row)

        editorOverlay.addView(palette)
        colorPalettePopup = palette
    }
    private fun closeColorPalette() {
        colorPalettePopup?.let { editorOverlay.removeView(it) }
        colorPalettePopup = null
    }

    // -------------------------------------------------------------------------
    // Send
    //
    //   SOURCE 1 — etCaption (editor kabhi nahi khula):
    //       finalCaption = etCaption.text  (plain text, no separator)
    //       Viewer: same caption sab pages par
    //       Bubble: ek baar dikhega
    //
    //   SOURCE 2 — perImageCaptions (editor khula tha):
    //       finalCaption = cap1 + CAP_SEP + cap2 + CAP_SEP + cap3  (separator se join)
    //       Viewer: har page apna caption
    //       Bubble: sab captions comma se
    //
    //   ROTATION + TEXT: pehle rotation apply hota hai, phir usi (rotated) image
    //   par text burn hota hai — taaki text hamesha sahi orientation mein,
    //   user ne jahan rakha wahin dikhe.
    // -------------------------------------------------------------------------
    private fun sendSelection() {
        if (selection.isEmpty()) return
        if (editorOpen) saveCurrentEditorCaption()
        syncCaptionSize()

        val finalCaption: String = if (!editorWasOpened) {
            etCaption.text.toString().trim()
        } else {
            perImageCaptions.take(selection.size).joinToString(CAP_SEPARATOR)
        }

        val hasAnyRotation = perImageRotations.any { it != 0 }
        val hasAnyText = perImageTextEntries.any { list -> list.any { it.text.isNotBlank() } }
        val selectionSnapshot = selection.toList()

        if (!hasAnyRotation && !hasAnyText) {
            finishSend(selectionSnapshot.map { it.uri }, finalCaption)
        } else {
            val rotationsSnapshot = perImageRotations.toList()
            val textsSnapshot = perImageTextEntries.map { it.toList() }
            ioExecutor.execute {
                val finalUris = selectionSnapshot.mapIndexed { i, img ->
                    val deg = rotationsSnapshot.getOrElse(i) { 0 }
                    val rotatedUri = ImageRotationUtil.rotateAndSave(context, img.uri, deg)
                    val entries = textsSnapshot.getOrElse(i) { emptyList() }
                    ImageTextUtil.burnTextAndSave(context, rotatedUri, entries)
                }
                mainHandler.post { finishSend(finalUris, finalCaption) }
            }
        }
    }

    private fun finishSend(uris: List<Uri>, caption: String) {
        hideKeyboardNow()
        onImageSend?.invoke(uris, caption)
        clearSelection(); closeEditor(); etCaption.setText(""); hidePanel()
    }

    // -------------------------------------------------------------------------
    // Editor
    // -------------------------------------------------------------------------
    private fun openEditor() {
        if (selection.isEmpty()) return
        syncCaptionSize()
        editorWasOpened = true
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
            iv.rotation = perImageRotations.getOrElse(k) { 0 }.toFloat()
            Glide.with(iv).load(img.uri).centerCrop().into(iv)
            iv.setOnClickListener {
                saveCurrentEditorCaption()
                edIndex = k
                edCaption.setText(perImageCaptions.getOrElse(k) { "" })
                edCaption.hint = captionHint(k)
                renderEditor()
            }
            edStrip.addView(iv)
        }
        Glide.with(edImage).load(selection[edIndex].uri).fitCenter().into(edImage)
        edImage.animate().cancel()
        edImage.rotation = perImageRotations.getOrElse(edIndex) { 0 }.toFloat()
        renderTextOverlaysForCurrentImage()
        edCount.text = selection.size.toString(); syncHdChip()
    }
    private fun captionHint(index: Int): String =
        if (selection.size > 1) "Photo ${index + 1} ke liye caption..." else "Caption likhein..."
    private fun closeEditor() {
        if (!editorOpen) return
        saveCurrentEditorCaption(); editorOpen = false
        hideTextToolbar(); hideDustbin()
        editorOverlay.animate().alpha(0f).setDuration(140).withEndAction {
            editorOverlay.visibility = GONE; editorOverlay.alpha = 1f
        }.start()
    }

    // -------------------------------------------------------------------------
    // HD
    // -------------------------------------------------------------------------
    private fun setHd(on: Boolean) {
        btnHd.isSelected = on; btnHd.text = if (on) "HD \u2713" else "HD"
        btnHd.setTextColor(if (on) 0xFF25D366.toInt() else 0xFFFFFFFF.toInt()); syncHdChip()
    }
    private fun syncHdChip() {
        val on = btnHd.isSelected; edHd.text = if (on) "HD \u2713" else "HD"
        edHd.setTextColor(if (on) 0xFF25D366.toInt() else 0xFFFFFFFF.toInt())
    }

    // -------------------------------------------------------------------------
    // Media loading
    // -------------------------------------------------------------------------
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
                allImages = loaded; imagesLoaded = true
                buckets = MediaStoreHelper.getBuckets(loaded); applyFilter()
            }
        }
    }
    private fun applyFilter() {
        val list = if (selectedBucketId == null) allImages else allImages.filter { it.bucketId == selectedBucketId }
        imagesAdapter.update(list.map { RecentImage(it.uri) })
    }

    // -------------------------------------------------------------------------
    // Album picker
    // -------------------------------------------------------------------------
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
        albumSheet.animate().translationY(albumHeightPx.toFloat()).setDuration(180)
            .withEndAction { albumSheet.visibility = GONE }.start()
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

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------
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
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
    }
    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    private fun topRadii(r: Float) = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    private fun dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    companion object {
        private const val MIN_FLING    = 1000f
        private const val STRONG_FLING = 2600f
        private const val PREFS_NAME   = "attach_panel_prefs"
        private const val KEY_KB_PX    = "kb_px"
        private const val PENDING_OPEN_TIMEOUT_MS = 650L
        private const val DEBUG_OVERLAY = false
        private const val LONG_PRESS_MS = 350L
        /** SOURCE-2 captions ke beech separator — user kabhi type nahi kar sakta. */
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
