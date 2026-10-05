package io.github.valerioisufi.drawview.data.db

import androidx.room.TypeConverter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class BrushPresetListConverter {
    private val jsonFormat = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromPresetList(presets: List<BrushSettingsData>?): String? {
        return presets?.let { jsonFormat.encodeToString(it) }
    }

    @TypeConverter
    fun toPresetList(jsonString: String?): List<BrushSettingsData> {
        if (jsonString.isNullOrEmpty()) return emptyList()
        return try {
            jsonFormat.decodeFromString(jsonString)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
