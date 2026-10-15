package io.github.valerioisufi.drawview.store.room.db

import androidx.room.TypeConverter
import io.github.valerioisufi.drawview.model.ElementOrder
import io.github.valerioisufi.drawview.model.Stamp
import io.github.valerioisufi.drawview.model.Versions

/**
 * Stores the [Stamp]s, the [ElementOrder]s and the [Versions] as their text (whose order is the
 * one of the stamps and of the orders).
 */
class VersionConverters {
    @TypeConverter
    fun fromStamp(stamp: Stamp): String = stamp.toString()

    @TypeConverter
    fun toStamp(value: String): Stamp = Stamp.parse(value) ?: Stamp.ZERO

    @TypeConverter
    fun fromOrder(order: ElementOrder): String = order.toString()

    @TypeConverter
    fun toOrder(value: String): ElementOrder = ElementOrder.parse(value) ?: ElementOrder.UNASSIGNED

    @TypeConverter
    fun fromVersions(versions: Versions): String = versions.toString()

    @TypeConverter
    fun toVersions(value: String): Versions = Versions.parse(value)
}
