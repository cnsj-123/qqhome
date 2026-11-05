package com.qq.closie.ui.lifeos.finance

import android.graphics.*
import android.graphics.drawable.Drawable

/** Opaque legible paper with stable torn edges. */
internal class FinanceReceiptPaper(private val paper: Int, private val line: Int, private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val dash = DashPathEffect(floatArrayOf(3 * density, 4 * density), 0f)

    override fun onBoundsChange(bounds: Rect) {
        val tear = 9 * density
        val step = bounds.width() / 22f
        path.rewind()
        path.moveTo(bounds.left.toFloat(), bounds.top + tear * .42f)
        for (tooth in 1..22) {
            val edge = when (tooth % 7) {
                0 -> .42f
                1 -> .18f
                2 -> .86f
                3 -> .48f
                4 -> .75f
                5 -> .08f
                else -> .60f
            }
            path.lineTo(bounds.left + tooth * step, bounds.top + tear * edge)
        }
        path.lineTo(bounds.right.toFloat(), bounds.bottom - tear * .25f)
        for (tooth in 21 downTo 0) {
            path.lineTo(bounds.left + tooth * step, bounds.bottom - tear * if (tooth % 3 == 0) .4f else .08f)
        }
        path.close()
    }

    override fun draw(canvas: Canvas) {
        paint.color = paper; paint.style = Paint.Style.FILL; paint.pathEffect = null
        canvas.drawPath(path, paint)
        paint.color = line; paint.style = Paint.Style.STROKE; paint.strokeWidth = density
        paint.pathEffect = dash
        val footer = bounds.bottom - 105 * density
        if (footer > bounds.top) canvas.drawLine(bounds.left + 20 * density, footer, bounds.right - 20 * density, footer, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
