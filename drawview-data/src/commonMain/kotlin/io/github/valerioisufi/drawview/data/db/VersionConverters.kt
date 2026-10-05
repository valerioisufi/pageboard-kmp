package io.github.valerioisufi.drawview.data.db

import androidx.room.TypeConverter
import io.github.valerioisufi.drawview.core.model.ElementOrder
import io.github.valerioisufi.drawview.core.model.Stamp

/**
 * Stores the [Stamp]s and the [ElementOrder]s as their text (whose order is theirs).
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
}
