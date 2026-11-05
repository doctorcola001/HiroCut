package com.hirocut.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hirocut.app.data.settings.AppLanguage
import com.hirocut.app.domain.export.ExportMode
import com.hirocut.app.media.export.ExportStage
import com.hirocut.app.presentation.EditorViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@UnstableApi
class M5VisualTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun shot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.filesDir, "m5-qa/$name.png").apply { parentFile!!.mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    }
    @Test fun localizedEditorDrawerSettingsAndRealExport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as HiroCutApplication
        val originalLanguage = app.preferences.state.value.language
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "HiroCut-M5-QA.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/HiroCut-tests")
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values))
        lateinit var model: EditorViewModel
        try {
            context.contentResolver.openOutputStream(uri)!!.use { output -> instrumentation.context.assets.open("m4-colors.mp4").use { it.copyTo(output) } }
            compose.runOnUiThread { app.preferences.setLanguage(AppLanguage.Chinese) }
            compose.waitUntil(10_000) { compose.activity.getString(R.string.settings) == "设置" }
            compose.runOnUiThread { model = ViewModelProvider(compose.activity)[EditorViewModel::class.java] }
            compose.waitUntil(10_000) { !model.state.value.loading }
            compose.runOnUiThread { model.importVideo(uri) }
            compose.waitUntil(10_000) { model.state.value.source?.uri == uri.toString() && model.state.value.project != null }
            compose.runOnUiThread {
                val first = model.state.value.selectedId!!
                model.setRange(first, 0, 1000)
                model.seek(2000); model.addClip()
                model.setRange(model.state.value.selectedId!!, 2000, 3000)
                model.seek(4000); model.addClip()
                model.setRange(model.state.value.selectedId!!, 4000, 5500)
                model.seek(4500)
                model.focusClip(first)
            }
            compose.waitUntil(10_000) { kotlin.math.abs(model.playback.state.value.framePositionMs - 4500) < 100 }
            shot("m5-editor-zh")
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.open_sidebar)).performClick()
            compose.waitForIdle()
            // The cover repository runs off the UI thread; wait for its decoding work before visual capture.
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            android.os.SystemClock.sleep(500)
            shot("m5-drawer-zh")
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.settings)).performClick()
            shot("m5-settings-zh")
            compose.onNodeWithText(compose.activity.getString(R.string.language_english)).performClick()
            compose.waitUntil(10_000) { compose.activity.getString(R.string.settings) == "Settings" }
            assertEquals(AppLanguage.English, com.hirocut.app.data.settings.UiPreferencesStore(context).state.value.language)
            compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(width * .9f, height * .5f)) }
            compose.waitForIdle()
            shot("m5-editor-en")
            compose.runOnUiThread { app.preferences.setLanguage(AppLanguage.Chinese) }
            compose.waitUntil(10_000) { compose.activity.getString(R.string.settings) == "设置" }
            compose.runOnUiThread { model.prepareExport(ExportMode.Precise) }
            compose.waitUntil(10_000) { model.exportState.value.stage in listOf(ExportStage.Review, ExportStage.Failed) }
            assertEquals(ExportStage.Review, model.exportState.value.stage)
            compose.runOnUiThread { model.renderExport() }
            compose.waitUntil(60_000) { model.exportState.value.stage in listOf(ExportStage.Ready, ExportStage.Failed) }
            assertEquals(ExportStage.Ready, model.exportState.value.stage)
            assertEquals(3, model.exportState.value.completedClipIds.size)
            shot("m5-export-ready-zh")
            compose.runOnUiThread { model.dismissExport() }
            compose.waitUntil(10_000) { model.exportState.value.stage == ExportStage.Idle }
        } finally {
            compose.runOnUiThread { app.preferences.setLanguage(originalLanguage) }
            context.contentResolver.delete(uri, null, null)
        }
    }
}
