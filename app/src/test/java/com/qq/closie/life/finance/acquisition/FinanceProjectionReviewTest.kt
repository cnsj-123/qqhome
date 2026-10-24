package com.qq.closie.life.finance.acquisition

import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.data.LifeContainer
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.finance.FinanceReceiptOverlay
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FinanceProjectionReviewTest:FinanceRoomTest() {
    private val activityController=Robolectric.buildActivity(ComponentActivity::class.java)
    private val edits=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private lateinit var activity:ComponentActivity
    private lateinit var review:FinanceProjectionReview
    @Before fun setup() {
        activity=activityController.setup().visible().get();FinanceScreenshotPreference.set(activity,true)
        ShadowSettings.setCanDrawOverlays(false)
        val container=LifeContainer.createForTesting(activity,db)
        review=FinanceProjectionReview(activity){root,closed->FinanceReceiptOverlay(activity,edits,{container},edits,closed,root)}
    }
    @After fun cleanup(){review.close();edits.cancel();FinanceReceiptOverlay.endCapture();FinanceScreenshotPreference.set(activity,false);activityController.destroy()}
    private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap {all(v.getChildAt(it))} else emptyList())
    @Test fun projectionWithoutAccessibilityUsesEditableSameWindowPaperWithoutOverlayPermission()=runBlocking {
        review.awaitReady();review.recognizing()
        val token=finance.economics.token()
        val id=finance.media.createScreenshotCandidate(token,FinanceLocalOcrParser.parse("支付成功\n人民币12.34")){true}
        val reservation=Any();assertTrue(FinanceReceiptOverlay.beginCapture(reservation))
        val request=FinanceProjectionRequest("owned-page",token,RestoreStartupGate.state.value.restoreGeneration,
            activity.resources.configuration.orientation,reservation=reservation,review=review)
        review.onTerminate={request.cancel()}
        assertTrue(review.present(id,token.restoreEpoch){request.current(activity)})
        FinanceReceiptOverlay.endCapture(reservation);assertTrue(FinanceReceiptOverlay.occupied)
        val views=all(activity.window.decorView)
        assertTrue(views.filterIsInstance<EditText>().isNotEmpty())
        views.filterIsInstance<EditText>().first().setText("23.45")
        for(label in listOf("收付/转出账户","分类")) {
            val button=all(activity.window.decorView).filterIsInstance<Button>().first {it.text.toString().startsWith(label)}
            button.performClick()
            assertTrue(all(activity.window.decorView).filterIsInstance<Button>().any {it.text.toString().startsWith("返回小票")})
            review.back();assertTrue(FinanceReceiptOverlay.occupied)
        }
        assertTrue(finance.snapshot().entries.isEmpty())
        activityController.pause().stop()
        assertFalse(review.live);assertFalse(request.current(activity));assertFalse(FinanceReceiptOverlay.occupied)
        assertTrue(db.financeIntakeDao().proposals().isNotEmpty())
    }
    @Test fun leavingConsentOwnerPreventsLatePaperAndOriginalEpochCannotBeRenewed()=runBlocking {
        val token=finance.economics.token()
        val request=FinanceProjectionRequest("late-page",token,RestoreStartupGate.state.value.restoreGeneration,
            activity.resources.configuration.orientation,review=review)
        review.onTerminate={request.cancel()}
        activityController.pause().stop()
        assertFalse(request.current(activity));assertFalse(review.present("late",token.restoreEpoch){true})
        RestoreStartupGate.beginRestore();RestoreStartupGate.markReady()
        assertFalse(request.current(activity));assertTrue(db.financeIntakeDao().proposals().isEmpty())
    }
}
