package com.qq.closie.ui.lifeos.finance

import android.graphics.*
import android.graphics.drawable.Drawable

/** Opaque legible paper with a shallow torn lower edge; never blurs the host application. */
internal class FinanceReceiptPaper(private val paper: Int, private val line: Int, private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat(); val r = 22 * density
        val tear = 3 * density; val step = 12 * density
        val path = Path().apply {
            moveTo(0f, r); quadTo(0f, 0f, r, 0f); lineTo(w - r, 0f); quadTo(w, 0f, w, r)
            lineTo(w, h - tear)
            var x = w
            while (x > 0) { lineTo((x - step / 2).coerceAtLeast(0f), h); lineTo((x - step).coerceAtLeast(0f), h - tear); x -= step }
            lineTo(0f, r); close()
        }
        paint.color = paper; paint.style = Paint.Style.FILL; paint.pathEffect = null
        canvas.drawPath(path, paint)
        paint.color = line; paint.style = Paint.Style.STROKE; paint.strokeWidth = density
        paint.pathEffect = DashPathEffect(floatArrayOf(3 * density, 4 * density), 0f)
        canvas.drawLine(20 * density, 56 * density, w - 20 * density, 56 * density, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
