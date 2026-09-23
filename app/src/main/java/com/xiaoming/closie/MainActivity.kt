package com.xiaoming.closie

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import com.xiaoming.closie.data.repository.LocalWardrobeRepository
import com.xiaoming.closie.navigation.ClosieNavHost
import com.xiaoming.closie.ui.theme.ClosieTheme

class MainActivity : ComponentActivity() {
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
        setContent {
            val repository = remember { LocalWardrobeRepository(applicationContext) }
            ClosieTheme {
                Surface {
                    ClosieNavHost(repository)
                }
            }
        }
    }
}
