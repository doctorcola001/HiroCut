package com.hirocut.app

import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hirocut.app.presentation.EditorViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@UnstableApi
class M1PlaybackUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun centerPlayButtonAndPreviewTapControlActualPlayback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "HiroCut-M1-UI-${System.nanoTime()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/HiroCut-tests")
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values))
        try {
            context.contentResolver.openOutputStream(uri)!!.use { output ->
                InstrumentationRegistry.getInstrumentation().context.assets.open("m1-test.mp4").use { it.copyTo(output) }
            }
            lateinit var model: EditorViewModel
            compose.runOnUiThread { model = ViewModelProvider(compose.activity)[EditorViewModel::class.java] }
            compose.waitUntil(10_000) { !model.state.value.loading }
            compose.runOnUiThread { model.importVideo(uri) }
            compose.waitUntil(10_000) { model.state.value.source?.uri == uri.toString() && model.playback.state.value.durationMs > 0 && !model.playback.state.value.isBuffering }
            compose.waitForIdle()
            compose.onNodeWithTag("preview-toggle").performClick()
            compose.waitUntil(10_000) { model.playback.state.value.isPlaying && model.playback.state.value.positionMs > 200 }
            compose.onNodeWithTag("preview-toggle").performClick()
            compose.waitUntil(5_000) { !model.playback.state.value.isPlaying }
        } finally { context.contentResolver.delete(uri, null, null) }
    }
}
