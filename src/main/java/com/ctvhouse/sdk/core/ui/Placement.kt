package com.ctvhouse.sdk.core.ui

import android.view.Gravity

/** Corner of the creative. Mapped from the public horizontal × vertical pair. */
internal data class Placement(
    val horizontal: Horizontal = Horizontal.LEFT,
    val vertical: Vertical = Vertical.BOTTOM,
) {
    enum class Horizontal { LEFT, RIGHT }
    enum class Vertical { TOP, BOTTOM }

    val gravity: Int
        get() {
            val h = if (horizontal == Horizontal.LEFT) Gravity.START else Gravity.END
            val v = if (vertical == Vertical.TOP) Gravity.TOP else Gravity.BOTTOM
            return h or v
        }

    companion object {
        val PLAYBACK = Placement(Horizontal.LEFT, Vertical.BOTTOM)
        val MARKING = Placement(Horizontal.LEFT, Vertical.TOP)
        val SKIP = Placement(Horizontal.RIGHT, Vertical.TOP)
    }
}
