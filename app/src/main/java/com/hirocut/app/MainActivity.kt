package com.hirocut.app

import android.os.Bundle
import android.content.Context
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.hirocut.app.data.settings.UiPreferencesStore
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.hirocut.app.presentation.EditorViewModel
import com.hirocut.app.ui.editor.EditorScreen
import com.hirocut.app.ui.theme.HiroCutTheme

@UnstableApi
class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(if (android.os.Build.VERSION.SDK_INT >= 33) newBase else UiPreferencesStore.localizedContext(newBase))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val preferences by (application as HiroCutApplication).preferences.state.collectAsStateWithLifecycle()
            val initialLanguage = remember { preferences.language }
            LaunchedEffect(preferences.language) { if (android.os.Build.VERSION.SDK_INT < 33 && preferences.language != initialLanguage) recreate() }
            HiroCutTheme(preferences.theme) {
                val model: EditorViewModel = viewModel()
                val editor by model.state.collectAsStateWithLifecycle()
                val playback by model.playback.state.collectAsStateWithLifecycle()
                EditorScreen(editor, playback, model)
            }
        }
    }
}
