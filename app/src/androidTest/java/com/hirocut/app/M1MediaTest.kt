package com.hirocut.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hirocut.app.data.files.SourceRepository
import com.hirocut.app.domain.model.SourceImportException
import com.hirocut.app.media.player.PlayerController
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@UnstableApi
class M1MediaTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private fun withVideo(asset: String?, block: (Uri) -> Unit) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "HiroCut-M1-test-${System.nanoTime()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/HiroCut-tests")
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values))
        try {
            context.contentResolver.openOutputStream(uri)!!.use { output ->
                if (asset == null) output.write(byteArrayOf(0, 1, 2, 3))
                else instrumentation.context.assets.open(asset).use { it.copyTo(output) }
            }
            block(uri)
        } finally { context.contentResolver.delete(uri, null, null) }
    }

    @Test fun importsRealVideoMetadataWithoutPathConversion() = withVideo("m1-test.mp4") { uri ->
        val source = runBlocking { SourceRepository(context).inspect(uri) }
        assertEquals(uri.toString(), source.uri)
        assertEquals(640, source.width)
        assertEquals(360, source.height)
        assertTrue(source.durationMs in 5900..6100)
        assertTrue(source.hasAudio)
    }

    @Test fun silentRotatedVideoRetainsOrientationAndNoAudio() = withVideo("m1-silent-rotated.mp4") { uri ->
        val source = runBlocking { SourceRepository(context).inspect(uri) }
        assertEquals(90, source.rotationDegrees)
        assertFalse(source.hasAudio)
    }

    @Test fun rejectsCorruptVideoWithoutCrashing() = withVideo(null) { uri ->
        try {
            runBlocking { SourceRepository(context).inspect(uri) }
            fail("Expected a structured import failure")
        } catch (_: SourceImportException) { /* Expected. */ }
    }

    @Test fun realPlayerPreparesSeeksPlaysAndPauses() = withVideo("m1-test.mp4") { uri ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var controller: PlayerController
        try {
            instrumentation.runOnMainSync {
                controller = PlayerController(context, scope)
                controller.load(uri.toString())
            }
            waitUntil { controller.state.value.durationMs > 0 && !controller.state.value.isBuffering }
            instrumentation.runOnMainSync { controller.seek(3000) }
            waitUntil { controller.state.value.positionMs in 2900..3100 }
            instrumentation.runOnMainSync { controller.toggle() }
            waitUntil { controller.state.value.isPlaying && controller.state.value.positionMs > 3200 }
            instrumentation.runOnMainSync { controller.pause() }
            waitUntil { !controller.state.value.isPlaying }
            assertFalse(controller.state.value.failed)
        } finally {
            instrumentation.runOnMainSync { controller.release() }
            scope.cancel()
        }
    }

    @Test fun recreationBackgroundAndCloseReleaseResourcesSafely() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(50)
        assertTrue("Timed out waiting for media state", predicate())
    }
}
