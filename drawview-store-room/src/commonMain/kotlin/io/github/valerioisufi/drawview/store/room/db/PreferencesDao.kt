package io.github.valerioisufi.drawview.store.room.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface PreferencesDao {
    @Upsert
    suspend fun upsert(preference: PreferenceEntity)

    @Query("SELECT value FROM preferences WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("DELETE FROM preferences WHERE `key` = :key")
    suspend fun delete(key: String)
}
