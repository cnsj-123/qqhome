package com.xiaoming.closie

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.remember
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xiaoming.closie.navigation.ExternalCommandResolver
import com.xiaoming.closie.ui.lifeos.LifeOsRoot
import com.xiaoming.closie.ui.lifeos.LifeOsShellViewModel
import com.xiaoming.closie.ui.lifeos.settings.AppearanceViewModel

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
        // Start with light paper; the loaded Life OS appearance updates the icon contrast.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        // Android 10+ three-button nav draws a translucent scrim over the nav bar by default;
        // The shell draws its paper behind the navigation bar, so disable that scrim.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        handleIntent(intent)

        setContent {
            val app = application as ClosieApplication
            val shell: LifeOsShellViewModel = viewModel()
            val appearanceViewModel: AppearanceViewModel = viewModel(factory = remember(app) { AppearanceViewModel.Factory(app.appearanceRepository) })
            val appearance by appearanceViewModel.appearance.collectAsStateWithLifecycle()
            val ready = appearance
            if (ready == null) {
                Surface { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            } else {
                LifeOsRoot(app.wardrobeRepository, ready, shell, appearanceViewModel, externalCommand, ::consumeExternalCommand) { dark ->
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
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
        externalCommand = ExternalCommandResolver.parse(intent?.action, text, editId, openAdd, nextNonce())
            ?: externalCommand
    }

    private fun consumeExternalCommand() {
        externalCommand = null
        // Prevent configuration recreation from replaying a command already consumed by navigation.
        intent?.removeExtra(Intent.EXTRA_TEXT)
        intent?.removeExtra(EXTRA_EDIT_ITEM_ID)
        intent?.removeExtra(EXTRA_OPEN_ADD)
    }
}
