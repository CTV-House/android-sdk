package com.ctvhouse.sdk.core.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

/** Vector glyphs drawn in the view bounds — no resources, no host id clash. */
internal object Icons {
    fun info(): Drawable = GlyphDrawable(GlyphDrawable.Kind.INFO)

    fun volume(muted: Boolean): Drawable =
        GlyphDrawable(if (muted) GlyphDrawable.Kind.VOLUME_OFF else GlyphDrawable.Kind.VOLUME_ON)

    fun transport(paused: Boolean): Drawable =
        GlyphDrawable(if (paused) GlyphDrawable.Kind.PLAY else GlyphDrawable.Kind.PAUSE)

    private class GlyphDrawable(private val kind: Kind) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        override fun onBoundsChange(bounds: Rect) {
            path.reset()
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0f || h <= 0f) return
            when (kind) {
                Kind.VOLUME_ON -> volume(path, w, h, waves = true)
                Kind.VOLUME_OFF -> volume(path, w, h, waves = false)
                Kind.PLAY -> play(path, w, h)
                Kind.PAUSE -> pause(path, w, h)
                Kind.INFO -> info(path, w, h)
            }
            stroke.strokeWidth = (w.coerceAtMost(h) * 0.10f).coerceAtLeast(2f)
        }

        override fun draw(canvas: Canvas) {
            canvas.drawPath(path, paint)
            if (kind == Kind.VOLUME_OFF) {
                val b = bounds
                val w = b.width().toFloat()
                val h = b.height().toFloat()
                canvas.drawLine(
                    b.left + w * 0.18f,
                    b.top + h * 0.18f,
                    b.left + w * 0.82f,
                    b.top + h * 0.82f,
                    stroke,
                )
            }
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            stroke.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            stroke.colorFilter = colorFilter
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        enum class Kind { VOLUME_ON, VOLUME_OFF, PLAY, PAUSE, INFO }
    }

    private fun info(path: Path, w: Float, h: Float) {
        path.addCircle(w * 0.50f, h * 0.26f, w * 0.09f, Path.Direction.CW)
        path.addRect(w * 0.43f, h * 0.42f, w * 0.57f, h * 0.80f, Path.Direction.CW)
    }

    private fun volume(path: Path, w: Float, h: Float, waves: Boolean) {
        path.moveTo(w * 0.12f, h * 0.38f)
        path.lineTo(w * 0.38f, h * 0.38f)
        path.lineTo(w * 0.62f, h * 0.18f)
        path.lineTo(w * 0.62f, h * 0.82f)
        path.lineTo(w * 0.38f, h * 0.62f)
        path.lineTo(w * 0.12f, h * 0.62f)
        path.close()
        if (waves) {
            path.addCircle(w * 0.78f, h * 0.50f, w * 0.08f, Path.Direction.CW)
        }
    }

    private fun pause(path: Path, w: Float, h: Float) {
        val bar = w * 0.16f
        val gap = w * 0.14f
        val top = h * 0.22f
        val bot = h * 0.78f
        val left = (w - bar * 2 - gap) / 2f
        path.addRect(left, top, left + bar, bot, Path.Direction.CW)
        path.addRect(left + bar + gap, top, left + bar * 2 + gap, bot, Path.Direction.CW)
    }

    private fun play(path: Path, w: Float, h: Float) {
        path.moveTo(w * 0.32f, h * 0.20f)
        path.lineTo(w * 0.82f, h * 0.50f)
        path.lineTo(w * 0.32f, h * 0.80f)
        path.close()
    }
}
