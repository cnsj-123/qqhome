package com.qq.closie.ui.lifeos.finance

import android.graphics.*
import android.graphics.drawable.Drawable

/** Opaque legible paper with stable torn edges. */
internal class FinanceReceiptPaper(private val paper: Int, private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

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
        // Rules belong to the measured content rows, never to a fixed offset through the action bar.
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
