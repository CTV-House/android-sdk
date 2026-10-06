package com.ctvhouse.sdk.core.ui

import android.content.Context
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ctvhouse.sdk.R

/**
 * Overlay chrome: media surface plus three independently placed pieces —
 * marking (optional brand icon + label), the skip corner (landing chip left of skip),
 * and the playback group (info / mute / pause).
 */
internal class Controls(
    container: ViewGroup,
    actions: Actions = Actions(),
) {
    class Actions(
        var onSkip: () -> Unit = {},
        var onMuteToggle: () -> Unit = {},
        var onPauseToggle: () -> Unit = {},
        var onInfo: () -> Unit = {},
        var onLanding: () -> Unit = {},
    )

    private var container: ViewGroup? = container
    private var actions: Actions? = actions
    private val videoView: TextureView
    private val companionView: ImageView
    private val markingChip: TextView
    private val infoButton: ImageView
    private val muteButton: ImageView
    private val pauseButton: ImageView
    private val skipButton: TextView
    private val landingButton: TextView
    private val skipGroup: LinearLayout
    private val actionGroup: LinearLayout
    private val root: FrameLayout
    private var playbackPlacement: Placement = Placement.PLAYBACK
    private var markingPlacement: Placement = Placement.MARKING
    private var skipPlacement: Placement = Placement.SKIP
    private var logoVisible: Boolean = true
    private var skipArmed: Boolean = false
    private var landingOffered: Boolean = false
    private var chromeArmed: Boolean = false
    private var lastChrome: Chrome? = null
    private var infoDialog: Dialog? = null

    init {
        val context = container.context
        val markPadH = dp(context, 10)
        val markPadV = dp(context, 4)
        videoView = TextureView(context).apply {
            visibility = View.GONE
        }
        companionView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
        }
        markingChip = TextView(context).apply {
            tag = TAG_MARKING
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(markPadH, markPadV, markPadH, markPadV)
            setBackgroundColor(SCRIM)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            isFocusable = false
            maxLines = 1
            visibility = View.GONE
        }
        muteButton = roundButton(context, TAG_MUTE) {
            this.actions?.onMuteToggle?.invoke()
        }
        pauseButton = roundButton(context, TAG_PAUSE) {
            this.actions?.onPauseToggle?.invoke()
        }
        infoButton = roundButton(context, TAG_INFO) {
            this.actions?.onInfo?.invoke()
        }.apply { setImageDrawable(Icons.info()) }
        skipButton = chip(context, TAG_SKIP) {
            if (skipArmed) this.actions?.onSkip?.invoke()
        }
        landingButton = chip(context, TAG_LANDING) {
            this.actions?.onLanding?.invoke()
        }
        skipGroup = LinearLayout(context).apply {
            tag = TAG_SKIP_GROUP
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                landingButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(context, BUTTON),
                ).apply { marginEnd = dp(context, GAP) },
            )
            addView(
                skipButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(context, BUTTON),
                ),
            )
        }
        actionGroup = LinearLayout(context).apply {
            tag = TAG_ACTIONS
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(infoButton)
            addView(muteButton)
            addView(pauseButton)
        }
        root = FrameLayout(context).apply {
            visibility = View.GONE
            isClickable = true
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            setBackgroundColor(Color.TRANSPARENT)
            val mediaLp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            addView(videoView, mediaLp)
            addView(companionView, mediaLp)
            addView(markingChip, cornerLp(context, Placement.MARKING))
            addView(actionGroup, cornerLp(context, Placement.PLAYBACK))
            addView(
                skipGroup,
                cornerLp(context, Placement.SKIP, height = dp(context, BUTTON)),
            )
        }
        applyPlacements()
    }

    fun setPlacement(placement: Placement) {
        if (playbackPlacement == placement) return
        playbackPlacement = placement
        applyPlacements()
    }

    fun setMarkingPlacement(placement: Placement) {
        if (markingPlacement == placement) return
        markingPlacement = placement
        applyPlacements()
    }

    fun setSkipPlacement(placement: Placement) {
        if (skipPlacement == placement) return
        skipPlacement = placement
        applyPlacements()
    }

    fun setLogoVisible(visible: Boolean) {
        logoVisible = visible
        applyLogoVisibility()
    }

    /**
     * The black fill behind the creative. Off by default, so transparent pixels — and the
     * letterbox around a creative that does not fill the screen — show the host's content.
     */
    fun setBackdropVisible(visible: Boolean) {
        root.setBackgroundColor(if (visible) Color.BLACK else Color.TRANSPARENT)
    }

    fun attachToContainer() {
        val parent = container ?: return
        if (root.parent == null) {
            parent.addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    fun detachFromContainer() {
        (root.parent as? ViewGroup)?.removeView(root)
    }

    fun videoSurface(): TextureView = videoView

    fun showVideo(chrome: Chrome) {
        clearImage()
        videoView.visibility = View.VISIBLE
        presentMedia(chrome)
    }

    fun showImage(bitmap: Bitmap?, chrome: Chrome) {
        videoView.visibility = View.GONE
        if (bitmap != null) companionView.setImageBitmap(bitmap)
        companionView.visibility = View.VISIBLE
        presentMedia(chrome)
    }

    /** Chrome stays hidden until the creative has actually been composed on screen. */
    fun revealChrome() {
        if (root.visibility != View.VISIBLE || chromeArmed) return
        chromeArmed = true
        markingChip.visibility = View.VISIBLE
        skipButton.visibility = View.VISIBLE
        applyLandingVisibility()
        applyLogoVisibility()
        wireFocus()
        focusSkip()
    }

    fun updateControls(chrome: Chrome) {
        if (chrome == lastChrome) return
        lastChrome = chrome
        markingChip.text = chrome.marking
        skipButton.text = chrome.skipLabel
        skipArmed = chrome.skipEnabled
        skipButton.alpha = if (chrome.skipEnabled) 1f else DISABLED_ALPHA
        landingButton.text = chrome.landingLabel
        landingOffered = chrome.landingAvailable
        applyLandingVisibility()
        muteButton.visibility = if (chrome.muteAvailable) View.VISIBLE else View.GONE
        pauseButton.visibility = if (chrome.pauseAvailable) View.VISIBLE else View.GONE
        infoButton.visibility = if (chrome.infoAvailable) View.VISIBLE else View.GONE
        if (chrome.muteAvailable) {
            muteButton.setImageDrawable(Icons.volume(chrome.muted))
        }
        if (chrome.pauseAvailable) {
            pauseButton.setImageDrawable(Icons.transport(chrome.paused))
        }
        applyActionMargins()
        applyLogoVisibility()
        if (chromeArmed) wireFocus()
    }

    fun hide() {
        chromeArmed = false
        dismissInfo()
        markingChip.visibility = View.GONE
        skipButton.visibility = View.GONE
        landingButton.visibility = View.GONE
        actionGroup.visibility = View.GONE
        clearImage()
        videoView.visibility = View.GONE
        root.visibility = View.GONE
        lastChrome = null
    }

    fun release() {
        hide()
        detachFromContainer()
        skipButton.setOnClickListener(null)
        skipButton.onFocusChangeListener = null
        landingButton.setOnClickListener(null)
        landingButton.onFocusChangeListener = null
        muteButton.setOnClickListener(null)
        muteButton.onFocusChangeListener = null
        pauseButton.setOnClickListener(null)
        pauseButton.onFocusChangeListener = null
        infoButton.setOnClickListener(null)
        infoButton.onFocusChangeListener = null
        actions = null
        container = null
    }

    private fun presentMedia(chrome: Chrome) {
        chromeArmed = false
        dismissInfo()
        markingChip.visibility = View.GONE
        skipButton.visibility = View.GONE
        landingButton.visibility = View.GONE
        actionGroup.visibility = View.GONE
        root.visibility = View.VISIBLE
        root.bringToFront()
        lastChrome = null
        updateControls(chrome)
    }

    private fun applyPlacements() {
        val gap = dp(actionGroup.context, GAP)
        val size = dp(actionGroup.context, BUTTON)
        fun box(): LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(size, size).apply { marginEnd = gap }
        infoButton.layoutParams = box()
        muteButton.layoutParams = box()
        pauseButton.layoutParams = box()
        applyActionMargins()
        pin(markingChip, markingPlacement)
        pin(actionGroup, playbackPlacement)
        pin(skipGroup, skipPlacement, height = size)
        applyLandingVisibility()
        applyLogoVisibility()
        wireFocus()
    }

    private fun applyLandingVisibility() {
        landingButton.visibility =
            if (chromeArmed && landingOffered) View.VISIBLE else View.GONE
    }

    private fun applyLogoVisibility() {
        val icon = if (logoVisible) markingLogo(markingChip.context) else null
        markingChip.setCompoundDrawablesRelative(icon, null, null, null)
        markingChip.compoundDrawablePadding =
            if (icon != null) dp(markingChip.context, MARK_ICON_GAP) else 0
        val showGroup =
            infoButton.visibility == View.VISIBLE ||
                muteButton.visibility == View.VISIBLE ||
                pauseButton.visibility == View.VISIBLE
        actionGroup.visibility =
            if (chromeArmed && root.visibility == View.VISIBLE && showGroup) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    fun showQr(url: String) {
        dismissInfo()
        infoDialog = Qr.show(root.context, url)
    }

    private fun dismissInfo() {
        infoDialog?.dismiss()
        infoDialog = null
    }

    private fun applyActionMargins() {
        val gap = dp(actionGroup.context, GAP)
        val visible = listOf(infoButton, muteButton, pauseButton)
            .filter { it.visibility == View.VISIBLE }
        visible.forEachIndexed { index, view ->
            val lp = view.layoutParams as LinearLayout.LayoutParams
            lp.marginEnd = if (index == visible.lastIndex) 0 else gap
            view.layoutParams = lp
        }
    }

    private fun wireFocus() {
        val chain = listOf(infoButton, muteButton, pauseButton)
            .filter { it.visibility == View.VISIBLE }
        (chain + listOf(skipButton, landingButton)).forEach { button ->
            button.nextFocusLeftId = View.NO_ID
            button.nextFocusRightId = View.NO_ID
            button.nextFocusUpId = View.NO_ID
            button.nextFocusDownId = View.NO_ID
            button.nextFocusForwardId = View.NO_ID
        }
        for (i in 0 until chain.lastIndex) {
            chain[i].nextFocusRightId = chain[i + 1].id
            chain[i].nextFocusForwardId = chain[i + 1].id
            chain[i + 1].nextFocusLeftId = chain[i].id
        }
        val landing = landingButton.takeIf { it.visibility == View.VISIBLE }
        if (landing != null) {
            landing.nextFocusRightId = skipButton.id
            landing.nextFocusForwardId = skipButton.id
            skipButton.nextFocusLeftId = landing.id
        }
        val last = chain.lastOrNull() ?: return
        // The playback group enters the skip corner at its near edge: landing sits left of skip.
        val entry = if (landing != null && skipPlacement.horizontal == Placement.Horizontal.RIGHT) {
            landing
        } else {
            skipButton
        }
        linkFocus(last, entry, playbackPlacement, skipPlacement)
    }

    /**
     * Points D-pad from [from] toward [to] using the relative corners, and the reverse,
     * so a skip chip in another corner is still reachable from the playback group.
     */
    private fun linkFocus(from: View, to: View, fromAt: Placement, toAt: Placement) {
        when {
            toAt.horizontal != fromAt.horizontal &&
                toAt.horizontal == Placement.Horizontal.RIGHT -> {
                from.nextFocusRightId = to.id
                from.nextFocusForwardId = to.id
                to.nextFocusLeftId = from.id
            }
            toAt.horizontal != fromAt.horizontal -> {
                from.nextFocusLeftId = to.id
                from.nextFocusForwardId = to.id
                to.nextFocusRightId = from.id
            }
        }
        when {
            toAt.vertical != fromAt.vertical && toAt.vertical == Placement.Vertical.TOP -> {
                from.nextFocusUpId = to.id
                to.nextFocusDownId = from.id
            }
            toAt.vertical != fromAt.vertical -> {
                from.nextFocusDownId = to.id
                to.nextFocusUpId = from.id
            }
        }
        if (toAt == fromAt) {
            from.nextFocusRightId = to.id
            from.nextFocusForwardId = to.id
            to.nextFocusLeftId = from.id
        }
    }

    private fun pin(
        view: View,
        placement: Placement,
        width: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
    ) {
        val pad = dp(view.context, CHROME_PAD)
        val lp = (view.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(width, height)
        lp.width = width
        lp.height = height
        lp.gravity = placement.gravity
        lp.setMargins(pad, pad, pad, pad)
        view.layoutParams = lp
    }

    private fun cornerLp(
        context: Context,
        placement: Placement,
        width: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
    ): FrameLayout.LayoutParams {
        val pad = dp(context, CHROME_PAD)
        return FrameLayout.LayoutParams(width, height, placement.gravity).apply {
            setMargins(pad, pad, pad, pad)
        }
    }

    private fun clearImage() {
        companionView.visibility = View.GONE
        companionView.setImageDrawable(null)
    }

    private fun focusSkip() {
        if (!skipButton.isFocused) skipButton.post { skipButton.requestFocus() }
    }

    private fun applyChipFocus(chip: TextView, hasFocus: Boolean) {
        chip.setBackgroundColor(if (hasFocus) FOCUS_FILL else SCRIM)
        chip.setTextColor(if (hasFocus) Color.BLACK else Color.WHITE)
    }

    private fun applyRoundFocus(button: ImageView, hasFocus: Boolean) {
        (button.background as? GradientDrawable)?.setColor(if (hasFocus) FOCUS_FILL else SCRIM)
        button.setColorFilter(if (hasFocus) Color.BLACK else Color.WHITE)
    }

    private fun chip(context: Context, tag: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.tag = tag
            id = View.generateViewId()
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            minHeight = dp(context, BUTTON)
            setPadding(dp(context, 12), 0, dp(context, 12), 0)
            setBackgroundColor(SCRIM)
            isFocusable = true
            isFocusableInTouchMode = true
            visibility = View.GONE
            setOnFocusChangeListener { _, hasFocus -> applyChipFocus(this, hasFocus) }
            setOnClickListener { onClick() }
        }

    private fun roundButton(context: Context, tag: String, onClick: () -> Unit): ImageView {
        val size = dp(context, BUTTON)
        val inset = dp(context, 8)
        return ImageView(context).apply {
            this.tag = tag
            id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(SCRIM)
            }
            setPadding(inset, inset, inset, inset)
            scaleType = ImageView.ScaleType.FIT_CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            contentDescription = tag
            setOnFocusChangeListener { _, hasFocus -> applyRoundFocus(this, hasFocus) }
            setOnClickListener { onClick() }
        }
    }

    private fun dp(context: Context, v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v.toFloat(),
            context.resources.displayMetrics,
        ).toInt()

    /** Brand mark sized for the marking chip's type size. */
    private fun markingLogo(context: Context): Drawable? {
        val raw = context.getDrawable(R.drawable.ctv_sdk_logo)?.mutate() ?: return null
        val size = dp(context, MARK_ICON)
        raw.setBounds(0, 0, size, size)
        return raw
    }

    internal companion object {
        const val TAG_MUTE = "ctv.mute"
        const val TAG_PAUSE = "ctv.pause"
        const val TAG_INFO = "ctv.info"
        const val TAG_SKIP = "ctv.skip"
        const val TAG_SKIP_GROUP = "ctv.skipgroup"
        const val TAG_LANDING = "ctv.landing"
        const val TAG_MARKING = "ctv.marking"
        const val TAG_ACTIONS = "ctv.actions"
        const val SCRIM = 0xB3000000.toInt()
        const val FOCUS_FILL = Color.WHITE
        const val DISABLED_ALPHA = 0.55f
        const val BUTTON = 36
        const val GAP = 8
        const val MARK_ICON = 14
        const val MARK_ICON_GAP = 6
        const val CHROME_PAD = 16
    }
}
