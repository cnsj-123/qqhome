package com.qq.closie

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.qq.closie.navigation.ExternalCommandViewModel
import com.qq.closie.ui.lifeos.LifeOsApp

/** Android lifecycle/intent entry only. Intake, persistence and routes have independent owners. */
class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_EDIT_ITEM_ID = "edit_item_id"
        const val EXTRA_OPEN_ADD = "open_add"
    }
    private val external by lazy { ViewModelProvider(this)[ExternalCommandViewModel::class.java] }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        receiveIntent(intent, initial = true)
        setContent {
            LifeOsApp(application as ClosieApplication, external) { dark ->
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveIntent(intent, initial = false)
    }
    private fun receiveIntent(intent: Intent?, initial: Boolean) {
        val action = intent?.action
        val text = if (action == Intent.ACTION_SEND) intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() else null
        val edit = intent?.getStringExtra(EXTRA_EDIT_ITEM_ID)
        val add = intent?.getBooleanExtra(EXTRA_OPEN_ADD, false) == true
        if (initial) external.receiveInitial(action, text, edit, add) else external.receive(action, text, edit, add)
    }
}
