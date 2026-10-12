package com.petwalk.photosorter

import android.content.Context
import android.location.Address
import android.location.Geocoder
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.tasks.await
import java.io.Closeable
import java.util.Locale

object Folders {
    const val UNKNOWN_LOCATION = "Unknown location"
    const val UNCATEGORIZED = "Uncategorized"
    const val OTHER = "Other"
    const val UNREADABLE = "Unreadable"

    val special = setOf(UNKNOWN_LOCATION, UNCATEGORIZED, OTHER, UNREADABLE)

    /** Makes a "Country/Region/City" style path safe to use as folder names. */
    fun sanitize(path: String): String = path.split("/")
        .map { part -> part.map { if (it.isLetterOrDigit() || it in " -_.,'&()") it else '_' }.joinToString("").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("/")
        .ifEmpty { "Unnamed" }
}

enum class Granularity(val label: String) { CITY("City"), REGION("Region"), COUNTRY("Country") }

/** Turns GPS coordinates into place names with Android's built-in geocoder. */
class LocationGrouper(context: Context) {
    private val geocoder = if (Geocoder.isPresent()) Geocoder(context, Locale.getDefault()) else null
    private val cache = HashMap<String, Address?>()

    fun group(coords: Pair<Double, Double>?, granularity: Granularity): String {
        val (lat, lon) = coords ?: return Folders.UNKNOWN_LOCATION
        // Photos within ~1 km share a lookup, which keeps geocoder calls down.
        val key = String.format(Locale.US, "%.2f, %.2f", lat, lon)
        val address = if (cache.containsKey(key)) cache[key] else lookup(lat, lon).also { cache[key] = it }
        address ?: return key // Offline or no geocoder: fall back to a coordinate grid.
        val city = address.locality ?: address.subAdminArea
        val parts = when (granularity) {
            Granularity.COUNTRY -> listOf(address.countryName)
            Granularity.REGION -> listOf(address.countryName, address.adminArea)
            Granularity.CITY -> listOf(address.countryName, address.adminArea, city)
        }.filterNotNull().distinct()
        return if (parts.isEmpty()) key else Folders.sanitize(parts.joinToString("/"))
    }

    @Suppress("DEPRECATION") // The async overload is API 33+; we're already off the main thread.
    private fun lookup(lat: Double, lon: Double): Address? = try {
        geocoder?.getFromLocation(lat, lon, 1)?.firstOrNull()
    } catch (e: Exception) {
        null
    }
}

/** Labels photos with ML Kit's on-device model (~400 labels: Dog, Cat, Beach, Food, ...). */
class ContentGrouper(
    private val store: PhotoStore,
    private val minConfidence: Float,
    focusLabels: List<String>,
) : Closeable {
    private val focus = focusLabels.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.1f).build()
    )

    suspend fun group(photo: Photo): String {
        val bitmap = store.thumbnail(photo.uri, 512) ?: return Folders.UNREADABLE
        val labels = try {
            labeler.process(InputImage.fromBitmap(bitmap, 0)).await()
        } catch (e: Exception) {
            return Folders.UNREADABLE
        }
        val candidates = if (focus.isEmpty()) labels else labels.filter { it.text.lowercase() in focus }
        val best = candidates.maxByOrNull { it.confidence }
        return when {
            best != null && best.confidence >= minConfidence -> Folders.sanitize(best.text)
            focus.isEmpty() -> Folders.UNCATEGORIZED
            else -> Folders.OTHER
        }
    }

    override fun close() = labeler.close()
}
