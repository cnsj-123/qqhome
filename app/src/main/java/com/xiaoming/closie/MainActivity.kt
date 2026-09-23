package com.xiaoming.closie

import android.os.Bundle
import androidx.activity.ComponentActivity
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
        enableEdgeToEdge()
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
