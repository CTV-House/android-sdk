package com.ctvhouse.sdk.core.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** QR bitmap and a D-pad modal for a ClickThrough URL. */
internal object Qr {

    fun bitmap(text: String, sizePx: Int): Bitmap? {
        if (text.isBlank() || sizePx <= 0) return null
        return try {
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val w = matrix.width
            val h = matrix.height
            // Filled as one array and handed over in a single copy. Per-pixel setPixel costs a
            // JNI hop each and stalls the frame the viewer clicked in on a TV SoC.
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.RGB_565)
        } catch (_: Throwable) {
            null
        }
    }

    fun show(context: Context, url: String): Dialog? {
        val size = dp(context, 220)
        val qr = bitmap(url, size) ?: return null
        val pad = dp(context, 28)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 12).toFloat()
                setColor(0xFF111111.toInt())
            }
        }
        content.addView(ImageView(context).apply {
            setImageBitmap(qr)
            setBackgroundColor(Color.WHITE)
            val inset = dp(context, 12)
            setPadding(inset, inset, inset, inset)
            layoutParams = LinearLayout.LayoutParams(dp(context, 244), dp(context, 244))
        })
        val dialog = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        content.addView(closeButton(context) { dialog.dismiss() })
        dialog.setContentView(
            content,
            LinearLayout.LayoutParams(dp(context, 320), LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        dialog.show()
        return dialog
    }

    private fun closeBg(context: Context, focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(context, 8).toFloat()
        setColor(if (focused) Color.WHITE else 0x33FFFFFF)
        if (focused) setStroke(dp(context, 2), Color.WHITE)
    }

    private fun closeButton(context: Context, onClose: () -> Unit): View =
        TextView(context).apply {
            text = context.getString(android.R.string.cancel)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(dp(context, 24), dp(context, 10), dp(context, 24), dp(context, 10))
            isFocusable = true
            isFocusableInTouchMode = true
            background = closeBg(context, false)
            setOnFocusChangeListener { view, hasFocus ->
                view.background = closeBg(context, hasFocus)
                setTextColor(if (hasFocus) Color.BLACK else Color.WHITE)
            }
            setOnClickListener { onClose() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(context, 20) }
            post { requestFocus() }
        }

    private fun dp(context: Context, v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v.toFloat(),
            context.resources.displayMetrics,
        ).toInt()
}
