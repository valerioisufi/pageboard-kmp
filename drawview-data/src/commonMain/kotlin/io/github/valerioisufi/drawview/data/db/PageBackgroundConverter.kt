package io.github.valerioisufi.drawview.data.db

import androidx.room.TypeConverter
import io.github.valerioisufi.drawview.model.PageBackground
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PageBackgroundConverter {
    private val jsonFormat = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromPageBackground(background: PageBackground?): String? {
        return background?.let { jsonFormat.encodeToString(it) }
    }

    @TypeConverter
    fun toPageBackground(data: String?): PageBackground? {
        if (data == null) return null
        return try {
            jsonFormat.decodeFromString(data)
        } catch (_: Exception) {
            null
        }
    }
}
