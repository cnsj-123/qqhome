package com.qq.closie.ui.lifeos.finance

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.DatePicker
import android.widget.EditText
import android.widget.TimePicker
import androidx.activity.ComponentActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.qq.closie.life.data.LifeContainer
import com.qq.closie.life.finance.*
import com.qq.closie.life.finance.acquisition.FinanceProjectionReview
import com.qq.closie.life.finance.acquisition.FinanceScreenshotPreference
import com.qq.closie.ui.lifeos.captureNativeAttachedWindows
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings
import java.io.File
import java.time.Duration

/** Actual transparent Review host and Room candidate, with an inert synthetic backdrop. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w405dp-h880dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FinanceExternalReceiptVisualTest:FinanceRoomTest() {
    private val activityController=Robolectric.buildActivity(ComponentActivity::class.java)
    private val edits=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private lateinit var activity:ComponentActivity
    private lateinit var review:FinanceProjectionReview

    @Before fun mountHost() {
        activity=activityController.setup().visible().get()
        activity.window.setBackgroundDrawable(ColorDrawable(Color.rgb(246,245,249)))
        FinanceScreenshotPreference.set(activity,true)
        ShadowSettings.setCanDrawOverlays(false)
        FinanceReviewRequests.foreground=false
        val container=LifeContainer.createForTesting(activity,db)
        review=FinanceProjectionReview(activity){root,closed->FinanceReceiptOverlay(activity,edits,{container},edits,closed,root)}
    }
    @After fun unmountHost() {
        review.close();edits.cancel();FinanceReceiptOverlay.endCapture()
        FinanceScreenshotPreference.set(activity,false);activityController.destroy()
    }
    private fun views():List<View> {
        fun all(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap {all(view.getChildAt(it))} else emptyList()
        return all(activity.window.decorView)
    }
    private fun button(description:String)=views().filterIsInstance<Button>().single {it.contentDescription==description}
    private fun date()=views().filterIsInstance<EditText>().single {it.contentDescription.toString().startsWith("实际发生时间")}
    private fun capture(name:String) {
        val decor=activity.window.decorView
        val display=activity.resources.displayMetrics
        decor.measure(View.MeasureSpec.makeMeasureSpec(display.widthPixels,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(display.heightPixels,View.MeasureSpec.EXACTLY))
        decor.layout(0,0,display.widthPixels,display.heightPixels)
        val frame=captureNativeAttachedWindows()
        try {
            val bitmap=frame.bitmap
            val colors=mutableSetOf<Int>()
            for(y in 0 until bitmap.height step 4)for(x in 0 until bitmap.width step 4)colors+=bitmap.getPixel(x,y)
            assertTrue("External review must contain actual rendered controls",colors.size>8)
            val metrics=File(requireNotNull(System.getProperty("lifeos.v16.metricsDir")))
            val output=File(metrics.parentFile,"robolectric-visuals").apply {mkdirs()}
            File(output,"$name.windows.json").writeText(frame.windowAttributes)
            File(output,"$name.host.txt").writeText("Production FinanceProjectionReview -> FinanceReceiptOverlay; synthetic OCR/Room and inert Activity backdrop; DEVICE UNVERIFIED")
            File(output,"$name.png").outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
        } finally {frame.bitmap.recycle()}
    }

    @Test fun actualProjectionReviewRendersMainDateEditorAndAppliedDateWithImeFirstBack()=runBlocking {
        val old=FinanceTime.parse("2026-10-12 14:32")
        val token=finance.economics.token()
        account("合成零钱")
        val id=finance.media.createScreenshotCandidate(token,FinanceOcrFields(2300,old,FinanceProposalKind.EXPENSE,
            FinanceFlowRole.PAYMENT,"",currencyCode="CNY")){true}
        assertTrue(review.present(id,token.restoreEpoch){true})
        button("收付/转出账户").performClick()
        views().filterIsInstance<Button>().single {it.contentDescription==null && it.text.toString()=="合成零钱 · CNY"}.performClick()
        button("应用字段编辑").performClick()
        val confirm=button("确认记账")
        val paper=(confirm.parent as View).parent as View
        views().filterIsInstance<EditText>().single {it.contentDescription=="备注（可留空）"}.setText("合成咖啡")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(320)) // settle the production paper entrance
        capture("external-receipt-main")
        date().performClick()
        views().filterIsInstance<DatePicker>().single().updateDate(2026,10,3)
        views().filterIsInstance<TimePicker>().single().apply {hour=10;minute=44}
        assertFalse(confirm.isEnabled);assertEquals(View.GONE,(confirm.parent as View).visibility)
        confirm.performClick();assertTrue(finance.snapshot().entries.isEmpty())
        capture("external-receipt-date-edit")

        fun ime(visible:Boolean)=WindowInsetsCompat.Builder().setVisible(WindowInsetsCompat.Type.ime(),visible)
            .setInsets(WindowInsetsCompat.Type.ime(),Insets.of(0,0,0,if(visible)500 else 0)).build()
        ViewCompat.dispatchApplyWindowInsets(paper,ime(true))
        review.back()
        assertFalse("The first Back belongs to IME",confirm.isEnabled)
        assertEquals(View.VISIBLE,button("应用字段编辑").parent.let {it as View}.visibility)
        ViewCompat.dispatchApplyWindowInsets(paper,ime(false))
        review.back()
        assertTrue(confirm.isEnabled);assertEquals(FinanceTime.input(old),date().text.toString())
        date().performClick()
        views().filterIsInstance<DatePicker>().single().updateDate(2026,10,3)
        views().filterIsInstance<TimePicker>().single().apply {hour=10;minute=44}
        button("应用字段编辑").performClick()
        assertEquals("2026-11-03 10:44",date().text.toString())
        capture("external-receipt-date-applied")
        assertTrue(finance.snapshot().entries.isEmpty())
    }
}
