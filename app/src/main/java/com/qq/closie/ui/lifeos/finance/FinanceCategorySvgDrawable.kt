package com.qq.closie.ui.lifeos.finance

import android.graphics.*
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser

/** Native overlay renderer shares the same path data as the Compose SVG icon. */
internal class FinanceCategorySvgDrawable(key:String,color:Int):Drawable() {
    private val path=PathParser.createPathFromPathData(FinanceCategoryVectors.path(key))
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color=color;style=Paint.Style.STROKE;strokeWidth=1.44f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND
    }
    override fun draw(canvas:Canvas) {
        val save=canvas.save()
        canvas.translate(bounds.left.toFloat(),bounds.top.toFloat())
        canvas.scale(bounds.width()/24f,bounds.height()/24f)
        path?.let {canvas.drawPath(it,paint)}
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha:Int){paint.alpha=alpha;invalidateSelf()}
    override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter;invalidateSelf()}
    @Suppress("DEPRECATION") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
