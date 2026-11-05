package com.hirocut.app

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hirocut.app.presentation.EditorViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@UnstableApi
class M3EditorUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun withSource(block: (EditorViewModel) -> Unit) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "HiroCut-M3-test-${System.nanoTime()}.mp4")
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
            compose.waitUntil(10_000) { model.state.value.source?.uri == uri.toString() && model.state.value.project != null }
            compose.waitForIdle()
            block(model)
        } finally { context.contentResolver.delete(uri, null, null) }
    }

    @Test fun newTrackStartsAtPlayheadAndUndoRedoWork() = withSource { model ->
        val first = model.state.value.project!!.clips.single().id
        compose.runOnUiThread { model.setRange(first, 1_000, 2_000) }
        compose.runOnUiThread { model.seek(1500) }
        compose.onNodeWithTag("add-clip-card").performScrollTo().performClick()
        val project = model.state.value.project!!
        assertEquals(2, project.clips.size)
        assertEquals(1500L, project.clips.last().inMs)
        assertEquals(project.durationMs, project.clips.last().outMs)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.undo)).performClick()
        assertEquals(1, model.state.value.project!!.clips.size)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.redo)).performClick()
        assertEquals(2, model.state.value.project!!.clips.size)
    }

    @Test fun sharedScrubberClockSpeedAndFrameMarksWork() = withSource { model ->
        val id = model.state.value.project!!.clips.single().id
        compose.waitUntil(10_000) { model.playback.state.value.framePositionMs >= 0 }
        compose.runOnUiThread {
            model.previewSeek(1500)
            assertEquals(1500L, model.playback.state.value.positionMs)
            model.seek(1500)
        }
        compose.waitUntil(10_000) { kotlin.math.abs(model.playback.state.value.framePositionMs - 1500) < 100 }
        compose.runOnUiThread {
            val frame = model.playback.currentFrameMs()!!
            assertEquals(com.hirocut.app.domain.editor.MarkBoundaryResult.Applied, model.markSelectedBoundary(com.hirocut.app.domain.editor.Boundary.In))
            assertEquals(frame, model.state.value.project!!.clips.single().inMs)
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.seek_forward_five)).performClick()
        compose.runOnUiThread { assertEquals(model.state.value.project!!.durationMs, model.playback.state.value.positionMs) }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.seek_back_five)).performClick()
        compose.runOnUiThread { assertTrue(kotlin.math.abs(1000L - model.playback.state.value.positionMs) <= 2) }
        val before = model.state.value.project
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.preview_speed_current, "1")).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.speed_value, "2")).performClick()
        compose.runOnUiThread { assertEquals(2f, model.playback.state.value.speed); assertEquals(before, model.state.value.project) }
        compose.runOnUiThread { model.seek(model.state.value.project!!.durationMs); assertEquals(com.hirocut.app.domain.editor.AddClipResult.AtEnd, model.addClip()) }
    }

    @Test fun backgroundBlursOnTheFirstFrameOfOpeningAndClearsAfterClosing() {
        compose.waitForIdle()
        try {
            compose.mainClock.autoAdvance = false
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.open_sidebar)).performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("editor-background-blurred").assertExists()
            val drawerRight = compose.onNodeWithTag("drawer-sheet").fetchSemanticsNode().boundsInRoot.right
            val screenWidth = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
            assertTrue("Blur must precede settled drawer: right=$drawerRight", drawerRight < screenWidth * .68f)
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { click(Offset(width * .9f, height * .5f)) }
        compose.waitForIdle()
        compose.onNodeWithTag("editor-background-clear").assertExists()
    }

    @Test fun sidebarIsSeventyPercentAndThemeSettingsPersist() = withSource { model ->
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.open_sidebar)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("editor-drawer").assertIsDisplayed()
        val width = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        val drawerWidth = compose.onNodeWithTag("drawer-sheet").fetchSemanticsNode().boundsInRoot.width
        assertTrue("drawer ratio=$drawerWidth/$width", drawerWidth / width in .68f.. .72f)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.settings)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.theme_ocean)).performClick()
        val app = compose.activity.application as HiroCutApplication
        compose.runOnUiThread {
            assertEquals(com.hirocut.app.data.settings.ColorTheme.Ocean, app.preferences.state.value.theme)
            assertEquals(com.hirocut.app.data.settings.ColorTheme.Ocean, com.hirocut.app.data.settings.UiPreferencesStore(context).state.value.theme)
        }
        compose.onNodeWithText(compose.activity.getString(R.string.custom_background_locked)).assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.ok)).performClick()
        compose.runOnUiThread { app.preferences.setTheme(com.hirocut.app.data.settings.ColorTheme.Forest) }
    }

    @Test fun sidebarDeletesTheRequestedClipAndPreservesOtherSelection() = withSource { model ->
        compose.runOnUiThread { model.addClip(); model.addClip() }
        val original = model.state.value.project!!
        val first = original.clips[0].id
        val second = original.clips[1].id
        compose.runOnUiThread { model.selectClip(first) }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.open_sidebar)).performClick()
        compose.onNodeWithTag("drawer-delete-$second").performClick()
        compose.runOnUiThread {
            assertEquals(listOf(first, original.clips[2].id), model.state.value.project!!.clips.map { it.id })
            assertEquals(first, model.state.value.selectedId)
            model.undo()
            assertEquals(original, model.state.value.project)
        }
        compose.onNodeWithTag("drawer-delete-$first").performClick()
        compose.runOnUiThread { assertEquals(second, model.state.value.selectedId) }
        compose.onNodeWithTag("drawer-delete-$second").performClick()
        val last = original.clips[2].id
        compose.onNodeWithTag("drawer-delete-$last").assertIsNotEnabled()
        compose.runOnUiThread {
            model.deleteClip(last)
            assertEquals(listOf(last), model.state.value.project!!.clips.map { it.id })
        }
    }

    @Test fun preciseMillisecondsAreValidatedAndAppliedAsOneEdit() = withSource { model ->
        val initial = model.state.value.project!!
        val id = initial.clips.single().id
        compose.onNodeWithTag("precision-$id").performClick()
        compose.onNodeWithTag("range-start-input").performTextReplacement("00:00:05.000")
        compose.onNodeWithTag("range-end-input").performTextReplacement("00:00:04.875")
        compose.onNodeWithTag("confirm-range").assertIsNotEnabled()
        compose.onNodeWithTag("range-start-input").performTextReplacement("00:00:01.125")
        compose.onNodeWithTag("confirm-range").performClick()
        val clip = model.state.value.project!!.clips.single()
        assertEquals(1125L, clip.inMs); assertEquals(4875L, clip.outMs)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.undo)).performClick()
        assertEquals(initial, model.state.value.project)
        assertFalse(model.state.value.canUndo)
    }

    @Test fun handleDragIsOneUndoAndCanceledDragRestoresBaseline() = withSource { model ->
        val initial = model.state.value.project!!
        val id = initial.clips.single().id
        compose.onNodeWithTag("handle-In-$id").performTouchInput {
            swipe(center, center + Offset(140f, 0f), durationMillis = 400)
        }
        assertTrue(model.state.value.project!!.clips.single().inMs > 0)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.undo)).performClick()
        assertEquals(initial, model.state.value.project)
        assertFalse(model.state.value.canUndo)
        compose.onNodeWithTag("handle-In-$id").performTouchInput {
            down(center); advanceEventTime(100); moveTo(center + Offset(120f, 0f)); advanceEventTime(100); cancel()
        }
        compose.waitForIdle()
        assertEquals(initial, model.state.value.project)
        assertFalse(model.state.value.canUndo)
        assertTrue(model.state.value.canRedo)
    }

    @Test fun moveDeleteAndUndoKeepStableIdsAndRanges() = withSource { model ->
        compose.runOnUiThread { model.addClip(); model.addClip() }
        compose.waitForIdle()
        val original = model.state.value.project!!
        val last = original.clips.last().id
        compose.onNodeWithTag("move-up-$last").performClick()
        assertEquals(last, model.state.value.project!!.clips[1].id)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.delete_track)).performClick()
        assertFalse(model.state.value.project!!.clips.any { it.id == last })
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.undo)).performClick()
        assertEquals(last, model.state.value.project!!.clips[1].id)
    }

    @Test fun anotherTrackCannotHijackAnActiveGesture() = withSource { model ->
        compose.runOnUiThread {
            model.addClip()
            val original = model.state.value.project!!
            val first = original.clips[0].id
            val second = original.clips[1].id
            model.beginRangeGesture(first, com.hirocut.app.domain.editor.Boundary.In)
            model.dragBoundary(first, com.hirocut.app.domain.editor.Boundary.In, 1000)
            model.beginRangeGesture(second, com.hirocut.app.domain.editor.Boundary.Out)
            model.dragBoundary(second, com.hirocut.app.domain.editor.Boundary.Out, 2000)
            model.finishRangeGesture(second)
            assertTrue(model.state.value.inGesture)
            assertEquals(original.clips[1], model.state.value.project!!.clips[1])
            model.cancelGesture(first)
            assertEquals(original, model.state.value.project)
        }
    }

    @Test fun exportDialogRendersAndKeepsEditingFrozen() = withSource { model ->
        val initial = model.state.value.project
        try {
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.export)).performClick()
            compose.onNodeWithTag("prepare-export").performClick()
            compose.waitUntil(10_000) { model.exportState.value.stage == com.hirocut.app.media.export.ExportStage.Review ||
                model.exportState.value.stage == com.hirocut.app.media.export.ExportStage.Failed }
            assertEquals(com.hirocut.app.media.export.ExportStage.Review, model.exportState.value.stage)
            compose.runOnUiThread { model.addClip() }
            assertEquals(initial, model.state.value.project)
            compose.onNodeWithTag("render-export").performClick()
            compose.waitUntil(30_000) { model.exportState.value.stage == com.hirocut.app.media.export.ExportStage.Ready ||
                model.exportState.value.stage == com.hirocut.app.media.export.ExportStage.Failed }
            assertEquals("Export failed: ${model.exportState.value.problem}", com.hirocut.app.media.export.ExportStage.Ready, model.exportState.value.stage)
            compose.onNodeWithTag("save-export").assertIsDisplayed()
            compose.onNodeWithText(compose.activity.getString(R.string.discard_output)).performClick()
            compose.waitUntil(10_000) { model.exportState.value.stage == com.hirocut.app.media.export.ExportStage.Idle }
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.export)).assertIsEnabled()
        } finally {
            compose.runOnUiThread { model.cancelExport() }
            compose.waitUntil(10_000) { !model.exportState.value.working }
            compose.runOnUiThread { model.dismissExport() }
            compose.waitUntil(10_000) { model.exportState.value.stage == com.hirocut.app.media.export.ExportStage.Idle }
        }
    }

    @Test fun oneHundredRowsDoNotShrinkPreviewAndRetainGlobalScale() = withSource { model ->
        val height = compose.onNodeWithTag("video-preview").fetchSemanticsNode().boundsInRoot.height
        compose.runOnUiThread { repeat(99) { model.addClip() } }
        compose.waitForIdle()
        assertEquals(height, compose.onNodeWithTag("video-preview").fetchSemanticsNode().boundsInRoot.height)
        val project = model.state.value.project!!
        assertEquals(100, project.clips.size)
        assertTrue(project.clips.all { it.inMs == 0L && it.outMs == project.durationMs })
        compose.onNodeWithTag("precision-${project.clips.last().id}").assertIsDisplayed()
    }
}
