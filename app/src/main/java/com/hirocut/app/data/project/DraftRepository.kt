package com.hirocut.app.data.project

import android.content.Context
import com.hirocut.app.domain.editor.ClipRange
import com.hirocut.app.domain.editor.EditProject
import com.hirocut.app.domain.model.SourceVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class DraftSnapshot(val source: SourceVideo, val project: EditProject, val selectedId: String)
data class DraftRestore(val project: EditProject? = null, val selectedId: String? = null, val discarded: Boolean = false)

/** One local draft, schema versioned and matched to the source. Writes run serially on IO. */
class DraftRepository(context: Context, name: String = "editor-draft") {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    suspend fun load(source: SourceVideo): DraftRestore = withContext(Dispatchers.IO) {
        val raw = prefs.getString("draft", null) ?: return@withContext DraftRestore()
        try {
            val doc = JSONObject(raw)
            require(doc.getInt("schema") == 1)
            require(doc.getString("uri") == source.uri && doc.getLong("duration") == source.durationMs)
            require(doc.getInt("width") == source.width && doc.getInt("height") == source.height)
            require(doc.getInt("rotation") == source.rotationDegrees)
            if (doc.has("size") && source.fileSizeBytes != null) require(doc.getLong("size") == source.fileSizeBytes)
            val array = doc.getJSONArray("clips")
            val clips = (0 until array.length()).map { i ->
                val clip = array.getJSONObject(i)
                ClipRange(clip.getString("id"), clip.getLong("in"), clip.getLong("out"))
            }
            val project = EditProject(source.durationMs, clips)
            val selected = doc.optString("selected").takeIf { id -> clips.any { it.id == id } } ?: clips.first().id
            DraftRestore(project, selected)
        } catch (_: Exception) { DraftRestore(discarded = true) }
    }

    suspend fun save(snapshot: DraftSnapshot): Boolean = withContext(Dispatchers.IO) {
        val source = snapshot.source
        val clips = JSONArray()
        snapshot.project.clips.forEach { clips.put(JSONObject().put("id", it.id).put("in", it.inMs).put("out", it.outMs)) }
        val doc = JSONObject().put("schema", 1).put("uri", source.uri).put("duration", source.durationMs)
            .put("width", source.width).put("height", source.height).put("rotation", source.rotationDegrees)
            .put("selected", snapshot.selectedId).put("clips", clips)
        source.fileSizeBytes?.let { doc.put("size", it) }
        prefs.edit().putString("draft", doc.toString()).commit()
    }
}
