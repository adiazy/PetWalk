package com.petwalk.photosorter

import android.content.Context
import android.location.Address
import android.location.Geocoder
import java.util.Locale

object Folders {
    const val UNKNOWN_LOCATION = "Unknown location"
    const val UNCATEGORIZED = "Uncategorized"
    const val OTHER = "Other"
    const val UNREADABLE = "Unreadable"
    const val NOT_CLASSIFIED = "Not classified"
    const val FAMILY = "Family"
    const val TOGETHER = "Together"
    const val NOT_FAMILY = "Not family"

    val special = setOf(UNKNOWN_LOCATION, UNCATEGORIZED, OTHER, UNREADABLE, NOT_CLASSIFIED, NOT_FAMILY)

    /** Groups shown in the preview but never copied or moved. */
    val skipped = setOf(NOT_FAMILY)

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
