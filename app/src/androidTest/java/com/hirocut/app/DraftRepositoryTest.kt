package com.hirocut.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hirocut.app.data.project.*
import com.hirocut.app.domain.editor.*
import com.hirocut.app.domain.model.SourceVideo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DraftRepositoryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val source = SourceVideo("content://hirocut.test/source", "test", 600_000, 640, 360, 0, true, true, 100)
    private fun withRepository(test: suspend (DraftRepository) -> Unit) = runBlocking {
        val name = "draft-test-${System.nanoTime()}"
        try { test(DraftRepository(context, name)) } finally { context.deleteSharedPreferences(name) }
    }
    @Test fun roundTripPreservesReverseOrderOverlapsAndSelectedId() = withRepository { repository ->
        val project = EditProject(600_000, listOf(ClipRange("c", 480_000, 540_000),
            ClipRange("a", 60_000, 120_000), ClipRange("b", 60_000, 120_000)))
        assertTrue(repository.save(DraftSnapshot(source, project, "a")))
        val restored = repository.load(source)
        assertEquals(project, restored.project)
        assertEquals("a", restored.selectedId)
        assertFalse(restored.discarded)
    }
    @Test fun rejectsDifferentSourceMetadataOrSize() = withRepository { repository ->
        repository.save(DraftSnapshot(source, EditorRules.create(600_000, "a"), "a"))
        assertTrue(repository.load(source.copy(uri = "content://hirocut.test/other")).discarded)
        assertTrue(repository.load(source.copy(durationMs = 600_001)).discarded)
        assertTrue(repository.load(source.copy(fileSizeBytes = 101)).discarded)
        assertTrue(repository.load(source.copy(rotationDegrees = 90)).discarded)
    }
    @Test fun corruptedDraftCannotCreateInvalidProject() = runBlocking {
        val name = "corrupt-draft-test"
        try {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putString("draft", "not json").commit()
            val restored = DraftRepository(context, name).load(source)
            assertTrue(restored.discarded)
            assertNull(restored.project)
        } finally { context.deleteSharedPreferences(name) }
    }
}
