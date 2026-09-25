package com.budjetame.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.budjetame.android.ui.theme.BudjetameTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = AppContainer(applicationContext)
        setContent {
            // Provide the ActivityResultRegistryOwner explicitly at the root:
            // rememberLauncherForActivityResult reads ONLY
            // LocalActivityResultRegistryOwner.current (no fallback in
            // activity-compose 1.13), and compositions created detached from
            // the attached view chain — pager prefetch, dialog windows,
            // recompositions of a freshly-entered group — can miss it and
            // crash with "No ActivityResultRegistryOwner was provided".
            // Providing it here guarantees it for every descendant
            // composition.
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides this,
            ) {
                BudjetameTheme {
                    BudjetameApp(container)
                }
            }
        }
    }
}
