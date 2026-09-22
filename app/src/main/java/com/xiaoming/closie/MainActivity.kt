package com.xiaoming.closie

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.xiaoming.closie.data.repository.LocalWardrobeRepository
import com.xiaoming.closie.navigation.ClosieNavHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); enableEdgeToEdge(); setContent {
        val repository = remember { LocalWardrobeRepository(applicationContext) }
        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = Color(0xFF765661), background = Color(0xFFFFF8F7), surface = Color(0xFFFFF8F7))) { Surface { ClosieNavHost(repository) } }
    } }
}
