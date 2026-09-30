package com.xiaoming.closie

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
import com.xiaoming.closie.navigation.ClosieNavHost
import com.xiaoming.closie.ui.theme.ClosieTheme

/**
 * A single one-shot external navigation request. At most one command exists at a time; a new
 * incoming intent always replaces the previous one, so a stale "add" can never preempt a later
 * share. The NavHost consumes the command once navigation has been performed.
 */
sealed interface ExternalNavCommand {
    data class Import(val text: String, val nonce: Long) : ExternalNavCommand
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

        setContent {
            val repository = (application as ClosieApplication).wardrobeRepository
            ClosieTheme {
                Surface {
                    ClosieNavHost(
                        repository = repository,
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
     */
    private fun handleIntent(intent: Intent?) {
        val text = if (intent?.action == Intent.ACTION_SEND)
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() else null
        val editId = intent?.getStringExtra(EXTRA_EDIT_ITEM_ID)?.takeIf { it.isNotBlank() }
        val openAdd = intent?.getBooleanExtra(EXTRA_OPEN_ADD, false) == true

        // Replace, never accumulate: a new intent always wins over any stale command.
        externalCommand = when {
            !text.isNullOrBlank() -> ExternalNavCommand.Import(text, nextNonce())
            editId != null -> ExternalNavCommand.Edit(editId, nextNonce())
            openAdd -> ExternalNavCommand.Add(nextNonce())
            else -> externalCommand
        }
    }
}
