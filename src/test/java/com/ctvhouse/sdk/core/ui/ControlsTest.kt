package com.ctvhouse.sdk.core.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ControlsTest {

    private lateinit var container: FrameLayout
    private lateinit var controls: Controls
    private var skips = 0
    private var mutes = 0
    private var pauses = 0
    private var infos = 0
    private var landings = 0

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        container = FrameLayout(activity)
        activity.setContentView(container)
        skips = 0
        mutes = 0
        pauses = 0
        infos = 0
        landings = 0
        controls = Controls(
            container,
            Controls.Actions(
                onSkip = { skips++ },
                onMuteToggle = { mutes++ },
                onPauseToggle = { pauses++ },
                onInfo = { infos++ },
                onLanding = { landings++ },
            ),
        )
    }

    @Test
    fun attach_addsOverlayHidden_andDetachRemovesIt() {
        assertEquals(0, container.childCount)

        controls.attachToContainer()
        assertEquals(1, container.childCount)
        assertEquals(View.GONE, container.getChildAt(0).visibility)

        controls.attachToContainer()
        assertEquals("attach must be idempotent", 1, container.childCount)

        controls.detachFromContainer()
        assertEquals(0, container.childCount)
    }

    @Test
    fun videoRendersInTheViewTree() {
        assertTrue(controls.videoSurface() is TextureView)
    }

    /** Nothing of ours is painted where the creative is not, so transparent pixels show content. */
    @Test
    fun backdrop_isTransparentUntilTheHostAsksForTheBlackFill() {
        controls.attachToContainer()

        assertEquals(Color.TRANSPARENT, (root().background as ColorDrawable).color)

        controls.setBackdropVisible(true)
        assertEquals(Color.BLACK, (root().background as ColorDrawable).color)

        controls.setBackdropVisible(false)
        assertEquals(Color.TRANSPARENT, (root().background as ColorDrawable).color)
    }

    @Test
    fun showVideo_keepsChromeHiddenUntilTheCreativeIsReady() {
        controls.attachToContainer()
        controls.showVideo(videoChrome(skipEnabled = true))

        assertEquals(View.VISIBLE, root().visibility)
        assertEquals(View.VISIBLE, controls.videoSurface().visibility)
        assertEquals(View.GONE, skipButton().visibility)
        assertEquals(View.GONE, marking().visibility)
        assertEquals(View.GONE, actionGroup().visibility)

        controls.revealChrome()
        assertEquals(View.VISIBLE, skipButton().visibility)
        assertEquals(View.VISIBLE, marking().visibility)
        assertEquals(View.VISIBLE, muteButton().visibility)
    }

    @Test
    fun showVideo_revealsOverlayAndPlaybackGroup() {
        controls.attachToContainer()
        presentVideo(videoChrome(skipEnabled = false, skip = "Пропустить через 5"))

        val root = container.getChildAt(0)
        assertEquals(View.VISIBLE, root.visibility)
        assertEquals(View.VISIBLE, controls.videoSurface().visibility)
        assertEquals("Пропустить через 5", skipButton().text.toString())
        assertTrue(skipButton().alpha < 1f)
        assertEquals(View.VISIBLE, muteButton().visibility)
        assertEquals(View.VISIBLE, pauseButton().visibility)
    }

    @Test
    fun showImage_hidesVideoSurfaceAndPlaybackGroup() {
        controls.attachToContainer()
        presentImage(bitmap(), bannerChrome())

        assertEquals(View.GONE, controls.videoSurface().visibility)
        assertTrue(skipButton().isEnabled)
        assertEquals(View.GONE, muteButton().visibility)
        assertEquals(View.GONE, pauseButton().visibility)
    }

    @Test
    fun hide_dropsTheCreativeAndTheOverlay() {
        controls.attachToContainer()
        presentImage(bitmap(), bannerChrome())

        controls.hide()

        assertEquals(View.GONE, container.getChildAt(0).visibility)
        assertNull("the decoded creative must not be retained", imageView().drawable)
    }

    @Test
    fun skipClick_reachesTheFormat_onlyUntilReleased() {
        controls.attachToContainer()
        presentVideo(videoChrome(skipEnabled = true))

        val skip = skipButton()
        skip.performClick()
        assertEquals(1, skips)

        controls.release()
        skip.performClick()
        assertEquals("released chrome must not call back into the format", 1, skips)
    }

    @Test
    fun muteAndPauseClicks_reachTheFormat() {
        controls.attachToContainer()
        presentVideo(videoChrome(skipEnabled = true))

        val mute = muteButton()
        val pause = pauseButton()
        mute.performClick()
        pause.performClick()
        assertEquals(1, mutes)
        assertEquals(1, pauses)

        controls.release()
        mute.performClick()
        pause.performClick()
        assertEquals(1, mutes)
        assertEquals(1, pauses)
    }

    @Test
    fun defaultPlacements_areIndependentCorners() {
        controls.attachToContainer()
        presentVideo(videoChrome(skipEnabled = true))

        assertEquals(LinearLayout.HORIZONTAL, actionGroup().orientation)
        assertEquals(Gravity.START or Gravity.BOTTOM, gravity(actionGroup()))
        assertEquals(Gravity.START or Gravity.TOP, gravity(marking()))
        assertEquals(Gravity.END or Gravity.TOP, gravity(skipGroup()))
        assertFalse("overlay must not steal D-pad focus", root().isFocusable)
    }

    @Test
    fun skipAndMarkingPositions_doNotMovePlayback() {
        controls.setSkipPlacement(Placement(Placement.Horizontal.LEFT, Placement.Vertical.BOTTOM))
        controls.setMarkingPlacement(Placement(Placement.Horizontal.RIGHT, Placement.Vertical.BOTTOM))
        controls.setPlacement(Placement(Placement.Horizontal.RIGHT, Placement.Vertical.TOP))
        controls.attachToContainer()
        presentVideo(videoChrome(skipEnabled = true))

        assertEquals(Gravity.END or Gravity.TOP, gravity(actionGroup()))
        assertEquals(Gravity.START or Gravity.BOTTOM, gravity(skipGroup()))
        assertEquals(Gravity.END or Gravity.BOTTOM, gravity(marking()))
    }

    @Test
    fun logoSitsInTheMarkingChip_andHidesWithoutDroppingTheLabel() {
        controls.attachToContainer()
        presentVideo(videoChrome(skipEnabled = true))

        val icon = marking().compoundDrawablesRelative[0]
        assertNotNull(icon)
        val size = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            Controls.MARK_ICON.toFloat(),
            marking().resources.displayMetrics,
        ).toInt()
        assertEquals(size, icon.bounds.width())
        assertEquals(size, icon.bounds.height())
        assertEquals("РЕКЛАМА", marking().text.toString())

        controls.setLogoVisible(false)
        assertNull(marking().compoundDrawablesRelative[0])
        assertEquals(View.VISIBLE, marking().visibility)
        assertEquals("РЕКЛАМА", marking().text.toString())

        controls.setLogoVisible(true)
        assertNotNull(marking().compoundDrawablesRelative[0])
    }

    @Test
    fun release_detachesFromHostAndStopsReattaching() {
        controls.attachToContainer()
        controls.release()

        assertEquals(0, container.childCount)

        controls.attachToContainer()
        assertEquals("a released instance must not touch the host view tree", 0, container.childCount)
    }

    @Test
    fun updateControls_writesTextOnlyWhenItChanges() {
        controls.attachToContainer()
        presentVideo(videoChrome(skipEnabled = false, skip = "Пропустить через 5"))

        controls.updateControls(videoChrome(skipEnabled = false, skip = "Пропустить через 5"))
        controls.updateControls(videoChrome(skipEnabled = false, skip = "Пропустить через 4"))
        assertEquals("Пропустить через 4", skipButton().text.toString())

        controls.updateControls(videoChrome(skipEnabled = true, skip = "Пропустить ›"))
        ShadowLooper.idleMainLooper()
        assertEquals(1f, skipButton().alpha, 0f)
        assertTrue("skip must be reachable from a remote", skipButton().isFocusable)
        assertTrue(muteButton().isFocusable)
        assertTrue(pauseButton().isFocusable)
    }

    @Test
    fun chromeOpensOnSkip_andLeavesLaterFocusAlone() {
        controls.attachToContainer()
        presentVideo(videoChrome(skipEnabled = false, skip = "Пропустить через 5"))
        ShadowLooper.idleMainLooper()
        assertTrue(skipButton().isFocused)

        muteButton().requestFocus()
        assertTrue(muteButton().isFocused)

        controls.updateControls(videoChrome(skipEnabled = true, skip = "Пропустить ›"))
        ShadowLooper.idleMainLooper()
        assertTrue("ticker must not yank the D-pad back to skip", muteButton().isFocused)
    }

    @Test
    fun skipStaysInTheFocusChainDuringCountdown() {
        controls.attachToContainer()
        presentVideo(videoChrome(skipEnabled = false, skip = "Пропустить через 5"))

        assertTrue(skipButton().isFocusable)
        val skip = skipButton()
        skip.performClick()
        assertEquals("countdown skip must not fire", 0, skips)
    }

    @Test
    fun muteHiddenWhenSoundIsOff() {
        controls.attachToContainer()
        presentVideo(
            videoChrome(skipEnabled = true).copy(muteAvailable = false),
        )

        assertEquals(View.GONE, muteButton().visibility)
        assertEquals(View.VISIBLE, pauseButton().visibility)
    }

    @Test
    fun infoShownOnlyWhenAvailable() {
        controls.attachToContainer()
        presentVideo(videoChrome(skipEnabled = true))
        assertEquals(View.GONE, infoButton().visibility)

        controls.updateControls(videoChrome(skipEnabled = true).copy(infoAvailable = true))
        assertEquals(View.VISIBLE, infoButton().visibility)

        val info = infoButton()
        info.performClick()
        assertEquals(1, infos)

        controls.release()
        info.performClick()
        assertEquals(1, infos)
    }

    @Test
    fun landingShownOnlyWithAClickThrough() {
        controls.attachToContainer()
        presentVideo(videoChrome(skipEnabled = true))
        assertEquals(View.GONE, landingButton().visibility)

        controls.updateControls(videoChrome(skipEnabled = true).copy(landingAvailable = true))
        assertEquals(View.VISIBLE, landingButton().visibility)
        assertEquals("Перейти", landingButton().text.toString())

        val landing = landingButton()
        landing.performClick()
        assertEquals(1, landings)

        controls.release()
        landing.performClick()
        assertEquals(1, landings)
    }

    /** Landing sits inside the skip corner, left of skip, and takes the D-pad on the way there. */
    @Test
    fun landingSitsLeftOfSkipInTheFocusChain() {
        controls.attachToContainer()
        presentVideo(videoChrome(skipEnabled = true).copy(landingAvailable = true))

        val group = skipGroup()
        assertEquals(0, group.indexOfChild(landingButton()))
        assertEquals(1, group.indexOfChild(skipButton()))
        assertEquals(landingButton().id, skipButton().nextFocusLeftId)
        assertEquals(skipButton().id, landingButton().nextFocusRightId)
        assertEquals(landingButton().id, pauseButton().nextFocusRightId)
        assertEquals(pauseButton().id, landingButton().nextFocusLeftId)
    }

    @Test
    fun hostCanHidePause() {
        controls.attachToContainer()
        presentVideo(videoChrome(skipEnabled = true).copy(pauseAvailable = false))
        assertEquals(View.GONE, pauseButton().visibility)
        assertEquals(View.VISIBLE, muteButton().visibility)
    }

    private fun presentVideo(chrome: Chrome) {
        controls.showVideo(chrome)
        controls.revealChrome()
    }

    private fun presentImage(bitmap: Bitmap, chrome: Chrome) {
        controls.showImage(bitmap, chrome)
        controls.revealChrome()
    }

    private fun bitmap(): Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

    private fun videoChrome(
        skipEnabled: Boolean,
        skip: String = "Пропустить ›",
        muted: Boolean = false,
        paused: Boolean = false,
    ) = Chrome(
        marking = "РЕКЛАМА",
        skipLabel = skip,
        skipEnabled = skipEnabled,
        landingLabel = "Перейти",
        muted = muted,
        paused = paused,
        pauseAvailable = true,
        muteAvailable = true,
    )

    private fun bannerChrome() = Chrome(
        marking = "РЕКЛАМА",
        skipLabel = "Пропустить ›",
        skipEnabled = true,
        pauseAvailable = false,
        muteAvailable = false,
    )

    private fun actionGroup() = root().findViewWithTag<LinearLayout>(Controls.TAG_ACTIONS)

    private fun marking() = root().findViewWithTag<android.widget.TextView>(Controls.TAG_MARKING)

    private fun skipButton() = root().findViewWithTag<android.widget.TextView>(Controls.TAG_SKIP)

    private fun skipGroup() = root().findViewWithTag<LinearLayout>(Controls.TAG_SKIP_GROUP)

    private fun landingButton() =
        root().findViewWithTag<android.widget.TextView>(Controls.TAG_LANDING)

    private fun muteButton() = root().findViewWithTag<ImageView>(Controls.TAG_MUTE)

    private fun pauseButton() = root().findViewWithTag<ImageView>(Controls.TAG_PAUSE)

    private fun infoButton() = root().findViewWithTag<ImageView>(Controls.TAG_INFO)

    private fun imageView() = root().getChildAt(1) as ImageView

    private fun root() = container.getChildAt(0) as FrameLayout

    private fun gravity(view: View) = (view.layoutParams as FrameLayout.LayoutParams).gravity
}
