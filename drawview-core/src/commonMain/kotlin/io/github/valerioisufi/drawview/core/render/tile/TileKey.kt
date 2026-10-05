package io.github.valerioisufi.drawview.core.render.tile

/**
 * Immutable key identifying a single tile in the multi-resolution pyramid.
 *
 * @property pageKey Key of the page ([io.github.valerioisufi.drawview.core.model.DrawPage.renderKey]):
 * tiles stay valid when pages are reordered, inserted or deleted.
 * @property lod Level of Detail index (0 = 1x scale, 1 = 2x, -1 = 0.5x, etc.).
 * @property col Column index along the X-axis in the tile grid.
 * @property row Row index along the Y-axis in the tile grid.
 */
data class TileKey(
    val pageKey: Long,
    val lod: Int,
    val col: Int,
    val row: Int
) {
    /**
     * Computes the parent tile key at the lower LOD (lod - 1) for the same page.
     * Used for hierarchical mipmap fallback while high-res tiles are loading.
     */
    fun getParentKey(): TileKey {
        return TileKey(
            pageKey = pageKey,
            lod = lod - 1,
            col = if (col >= 0) col / 2 else (col - 1) / 2,
            row = if (row >= 0) row / 2 else (row - 1) / 2
        )
    }

    /**
     * Executes [action] on each of the four children tile keys at the higher LOD (lod + 1)
     * for the same page without allocating a list.
     */
    inline fun forEachChildKey(action: (TileKey) -> Unit) {
        val nextLod = lod + 1
        val c2 = col * 2
        val r2 = row * 2
        action(TileKey(pageKey, nextLod, c2, r2))
        action(TileKey(pageKey, nextLod, c2 + 1, r2))
        action(TileKey(pageKey, nextLod, c2, r2 + 1))
        action(TileKey(pageKey, nextLod, c2 + 1, r2 + 1))
    }

    /**
     * Computes the four children tile keys at the higher LOD (lod + 1) for the same page.
     */
    fun getChildrenKeys(): List<TileKey> {
        val nextLod = lod + 1
        val c2 = col * 2
        val r2 = row * 2
        return listOf(
            TileKey(pageKey, nextLod, c2, r2),
            TileKey(pageKey, nextLod, c2 + 1, r2),
            TileKey(pageKey, nextLod, c2, r2 + 1),
            TileKey(pageKey, nextLod, c2 + 1, r2 + 1)
        )
    }
}
