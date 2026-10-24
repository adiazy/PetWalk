package com.petwalk.photosorter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File

/** A family member and the face fingerprints learned from their reference photos (no photos are kept). */
data class FamilyMember(val name: String, val faces: List<FloatArray>)

/** Saves family members to the app's private storage. */
class FamilyStore(context: Context) {
    private val file = File(context.filesDir, "family.json")

    fun load(): List<FamilyMember> = try {
        val array = JSONArray(file.readText())
        List(array.length()) { i ->
            val obj = array.getJSONObject(i)
            val faces = obj.getJSONArray("faces")
            FamilyMember(
                name = obj.getString("name"),
                faces = List(faces.length()) { f ->
                    val v = faces.getJSONArray(f)
                    FloatArray(v.length()) { k -> v.getDouble(k).toFloat() }
                },
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(members: List<FamilyMember>) {
        val array = JSONArray()
        members.forEach { m ->
            val faces = JSONArray()
            m.faces.forEach { face -> faces.put(JSONArray().apply { face.forEach { put(it.toDouble()) } }) }
            array.put(JSONObject().put("name", m.name).put("faces", faces))
        }
        file.writeText(array.toString())
    }
}

/** Sorts photos that show family members into Family albums; everything else is left alone. */
class FamilyClassifier(
    context: Context,
    private val store: PhotoStore,
    private val members: List<FamilyMember>,
    private val threshold: Float,
    private val perPerson: Boolean,
) : Closeable {
    private val engine = FaceEngine(context)

    fun classify(photo: Photo): String {
        val bitmap = store.bitmap(photo.uri, DETECT_SIDE) ?: return Folders.NOT_FAMILY
        val found = engine.faces(bitmap).mapNotNull { face -> bestMatch(face) }.distinct()
        return when {
            found.isEmpty() -> Folders.NOT_FAMILY
            !perPerson -> Folders.FAMILY
            found.size == 1 -> Folders.sanitize("${Folders.FAMILY}/${found[0]}")
            else -> "${Folders.FAMILY}/${Folders.TOGETHER}"
        }
    }

    /** The family member this face belongs to, if any is similar enough. */
    private fun bestMatch(face: FloatArray): String? = members
        .map { m -> m.name to m.faces.maxOf { FaceEngine.similarity(it, face) } }
        .maxByOrNull { it.second }
        ?.takeIf { it.second >= threshold }
        ?.first

    override fun close() = engine.close()

    companion object {
        /** Photos are scanned at this size; big enough to find faces in group shots. */
        const val DETECT_SIDE = 1600
    }
}
