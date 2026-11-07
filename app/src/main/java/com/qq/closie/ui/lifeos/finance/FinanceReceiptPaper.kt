package com.qq.closie.ui.lifeos.finance

import android.graphics.*
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser

/** Scalable double-torn paper traced from Figma 1871:22742. */
internal class FinanceReceiptPaper(private val paper: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val source = requireNotNull(PathParser.createPathFromPathData(
        """
        M351.672 503.015 L336.425 512.9 L321.179 497.084 L305.932 509.935 L290.686 501.038
        L275.439 513.889 L260.192 504.992 L244.946 495.107 L229.699 510.923 L214.452 502.026
        L199.206 511.912 L183.959 499.061 L168.713 508.946 L153.466 504.003 L138.22 512.9
        L122.973 498.072 L107.726 510.923 L92.4794 505.98 L77.2327 513.889 L61.9859 501.038
        L46.7402 507.958 L31.4934 503.015 L16.2467 511.912 L0.999939 499.061 L0.999939 8.75101
        L16.2467 12.7051 L31.4934 11.7166 L46.7402 17.6478 L61.9859 5.78543 L77.2327 14.6822
        L92.4794 19.6248 L107.726 15.6707 L122.973 7.76248 L138.22 2.81985 L153.466 4.79691
        L168.713 18.6363 L183.959 5.78543 L199.206 3.80838 L214.452 2.81985 L229.699 17.6478
        L244.946 15.6707 L260.192 13.6936 L275.439 3.80838 L290.686 21.6019 L305.932 13.6936
        L321.179 10.7281 L336.425 4.79691 L351.672 6.77396 Z
        """.trimIndent().replace('\n', ' ')
    ))

    override fun onBoundsChange(bounds: Rect) {
        val horizontal = bounds.width() / 350.672f
        source.transform(Matrix().apply {
            setScale(horizontal, bounds.height() / 513.889f)
            postTranslate(bounds.left - horizontal, bounds.top.toFloat())
        }, path)
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
