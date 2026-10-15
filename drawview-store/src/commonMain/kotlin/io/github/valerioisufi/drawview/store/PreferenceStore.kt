package io.github.valerioisufi.drawview.store

/**
 * The preferences of an application, as text by key (e.g. those of the editor, written by
 * `DrawPreferences.encode`): the store keeps them without reading them.
 */
public interface PreferenceStore {
    /** The preference [key], or `null` when none is kept. */
    public suspend fun preference(key: String): String?

    /** Keeps [value] as the preference [key] (`null`: forgets it). */
    public suspend fun setPreference(key: String, value: String?)
}
