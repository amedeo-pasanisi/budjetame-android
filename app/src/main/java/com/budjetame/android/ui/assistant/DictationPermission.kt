package com.budjetame.android.ui.assistant

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Start a Dictation once the microphone permission is held, shared by the
 * Assistant and Quick Dictation (tickets #69, #65): the system prompt is
 * asked when the permission is missing, and a refusal starts nothing,
 * sounds nothing and says nothing. Once the user refuses for good, the
 * system answers the request itself without a prompt, so it is not asked
 * again on every open.
 */
@Composable
fun rememberDictate(onStartDictation: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) onStartDictation() }
    return {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) onStartDictation() else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }
}
