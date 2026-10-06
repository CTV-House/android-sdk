package com.ctvhouse.sdk.core.ui

import android.graphics.Bitmap
import android.view.TextureView
import android.view.ViewGroup

internal interface Ui {
    fun attach()
    fun detach()

    /** Terminal cleanup: drop host views and callbacks. The instance is unusable afterwards. */
    fun release() {}
    fun videoSurface(): TextureView?
    fun setPlacement(placement: Placement) {}
    fun setMarkingPlacement(placement: Placement) {}
    fun setSkipPlacement(placement: Placement) {}
    fun setLogoVisible(visible: Boolean) {}
    fun setBackdropVisible(visible: Boolean) {}
    fun showQr(url: String) {}
    fun showVideo(chrome: Chrome)
    fun showImage(bitmap: Bitmap?, chrome: Chrome)
    /** Skip, marking and playback actions — only after the creative is on screen. */
    fun revealChrome() {}
    fun updateControls(chrome: Chrome)
    fun hide()
}

/**
 * Owns the chrome for one screen.
 *
 * [release] drops the whole [Controls] graph, not just its callbacks: the views hold the
 * host Activity context, and a detached slot the host still keeps a field to must not
 * keep that screen alive.
 */
internal class ViewUi(
    container: ViewGroup,
    actions: Controls.Actions,
) : Ui {
    constructor(container: ViewGroup, onSkipClick: () -> Unit) : this(
        container,
        Controls.Actions(onSkip = onSkipClick),
    )

    private var controls: Controls? = Controls(container, actions)

    override fun attach() {
        controls?.attachToContainer()
    }

    override fun detach() {
        controls?.detachFromContainer()
    }

    override fun videoSurface(): TextureView? = controls?.videoSurface()

    override fun setPlacement(placement: Placement) {
        controls?.setPlacement(placement)
    }

    override fun setMarkingPlacement(placement: Placement) {
        controls?.setMarkingPlacement(placement)
    }

    override fun setSkipPlacement(placement: Placement) {
        controls?.setSkipPlacement(placement)
    }

    override fun setLogoVisible(visible: Boolean) {
        controls?.setLogoVisible(visible)
    }

    override fun setBackdropVisible(visible: Boolean) {
        controls?.setBackdropVisible(visible)
    }

    override fun showQr(url: String) {
        controls?.showQr(url)
    }

    override fun showVideo(chrome: Chrome) {
        controls?.showVideo(chrome)
    }

    override fun showImage(bitmap: Bitmap?, chrome: Chrome) {
        controls?.showImage(bitmap, chrome)
    }

    override fun revealChrome() {
        controls?.revealChrome()
    }

    override fun updateControls(chrome: Chrome) {
        controls?.updateControls(chrome)
    }

    override fun hide() {
        controls?.hide()
    }

    override fun release() {
        controls?.release()
        controls = null
    }
}
