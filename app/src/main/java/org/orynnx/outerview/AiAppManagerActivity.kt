package org.orynnx.outerview

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** Opened from the native rear-screen settings after the theme host has started. */
class AiAppManagerActivity : ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OuterViewTheme {
                AiAppManagerApp(
                    resumeTick = resumeTick,
                    onAbout = { startActivity(Intent(this, MainActivity::class.java)) },
                    onBack = { finish() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }
}
