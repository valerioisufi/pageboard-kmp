package io.github.valerioisufi.pageboard.store.room.db

import androidx.room.TypeConverter
import io.github.valerioisufi.pageboard.model.geometry.AffineTransform

/**
 * Stores an [AffineTransform] as its six values ("a,b,c,d,tx,ty").
 */
internal class AffineTransformConverter {
    @TypeConverter
    fun fromTransform(transform: AffineTransform): String =
        with(transform) { "$a,$b,$c,$d,$tx,$ty" }

    @TypeConverter
    fun toTransform(value: String): AffineTransform {
        val v = value.split(',').mapNotNull { it.toFloatOrNull() }
        return if (v.size == 6) AffineTransform(v[0], v[1], v[2], v[3], v[4], v[5]) else AffineTransform.IDENTITY
    }
}
