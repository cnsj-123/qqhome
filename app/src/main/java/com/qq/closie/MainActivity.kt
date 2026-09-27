package com.qq.closie

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qq.closie.life.capture.ExternalIntentRouter
import com.qq.closie.life.capture.SharedContent
import com.qq.closie.life.ui.shell.LifeShellNavHost
import com.qq.closie.ui.theme.ClosieTheme

/**
 * A single one-shot external navigation request. At most one command exists at a time; a new
 * incoming intent always replaces the previous one, so a stale "add" can never preempt a later
 * share. The NavHost consumes the command once navigation has been performed.
 *
 * The share variants are split by *destination* rather than all funnelling into one `Import`. The
 * previous single-command design sent every `ACTION_SEND` into Closie's product importer, which
 * meant a browser article, a 知乎 answer or a 小红书 tutorial all landed in the wardrobe as a
 * half-filled 商品. The split is what makes "share a page → 资料库" possible at all; see
 * [com.qq.closie.life.capture.ExternalIntentRouter].
 */
sealed interface ExternalNavCommand {
    /** A shopping link. Consumed by Closie's product importer, which owns that flow. */
    data class ProductImport(val text: String, val nonce: Long) : ExternalNavCommand

    /**
     * A non-shopping web link. Consumed by Life OS: capture → fetch metadata → 资料库 → editor.
     *
     * [originalText] is kept alongside [url] so the capture records what the user actually shared,
     * while [url] is what gets fetched.
     */
    data class ReferenceLink(
        val originalText: String,
        val url: String,
        val nonce: Long
    ) : ExternalNavCommand

    /** Shared text with no URL — becomes a 记录, exactly like a clipboard save. */
    data class CaptureText(val text: String, val nonce: Long) : ExternalNavCommand

    data class Edit(val itemId: String, val nonce: Long) : ExternalNavCommand
    data class Add(val nonce: Long) : ExternalNavCommand
}

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_EDIT_ITEM_ID = "edit_item_id"
        const val EXTRA_OPEN_ADD = "open_add"

        private var nonceCounter = 0L
        private fun nextNonce(): Long = ++nonceCounter
    }

    private var externalCommand by mutableStateOf<ExternalNavCommand?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Closie is intentionally light-only. Keep dark system-bar icons on the light
        // Porcelain background regardless of the device's system dark/light setting.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        // Android 10+ three-button nav draws a translucent scrim over the nav bar by default;
        // Closie extends its bottom bar with navigationBarsPadding(), so disable that scrim.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        handleIntent(intent)

        // Interrupted-restore recovery is deliberately NOT here. It belongs to the Application,
        // because this activity is only one of several entry points into the process — quick capture
        // and its service start the process too, and a restore must not stay half-applied just because
        // it was reopened through a notification rather than the main screen. See
        // RestoreRecoveryManager, driven from ClosieApplication.onCreate.

        val app = application as ClosieApplication
        setContent {
            ClosieTheme {
                Surface {
                    // MainActivity now enters the Life OS shell. Closie itself is reachable from
                    // 生活 → 衣橱 (and 我的 → 设置), and keeps its own bottom bar there.
                    LifeShellNavHost(
                        wardrobeRepository = app.wardrobeRepository,
                        lifeContainer = app.lifeContainer,
                        externalCommand = externalCommand,
                        onExternalCommandConsumed = { externalCommand = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Reads incoming share / deep-link intents into a single one-shot command. ACTION_SEND is read
     * CharSequence-safe. launchMode is singleTop, so repeated shares reuse this activity.
     *
     * Shared text is classified by [ExternalIntentRouter] rather than assumed to be a product. This
     * is the one place the decision is made, so every downstream flow takes an already-resolved
     * command and none of them has to re-derive what the user meant.
     */
    private fun handleIntent(intent: Intent?) {
        val text = if (intent?.action == Intent.ACTION_SEND)
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() else null
        val editId = intent?.getStringExtra(EXTRA_EDIT_ITEM_ID)?.takeIf { it.isNotBlank() }
        val openAdd = intent?.getBooleanExtra(EXTRA_OPEN_ADD, false) == true

        val nonce = nextNonce()

        // Replace, never accumulate: a new intent always wins over any stale command.
        externalCommand = when {
            !text.isNullOrBlank() -> when (val shared = ExternalIntentRouter.route(text)) {
                is SharedContent.ProductLink ->
                    ExternalNavCommand.ProductImport(shared.text, nonce)
                is SharedContent.ReferenceLink ->
                    ExternalNavCommand.ReferenceLink(shared.text, shared.url, nonce)
                is SharedContent.PlainText ->
                    ExternalNavCommand.CaptureText(shared.text, nonce)
            }
            editId != null -> ExternalNavCommand.Edit(editId, nonce)
            openAdd -> ExternalNavCommand.Add(nonce)
            else -> externalCommand
        }
    }
}
