package io.github.valerioisufi.pageboard.store.space

/**
 * Completes the items of a catalog kept before who brought an item followed its arrivals (GRASP
 * Pure Fabrication, see `docs/plans/brought-by.md`): an item of before had only the replica of its
 * latest addition, which a member adding it again had rewritten.
 *
 * Every device keeps the whole log of the spaces it is in (the log of a catalog is not compacted),
 * so the additions and removals of the log, replayed, give the same items on every device. An item
 * the log does not explain (its latest addition or removal is not the one of the log) keeps who
 * brought it as it was kept, and is told ([Completed.unexplained]): a device never makes up who
 * brought an item.
 */
internal object CatalogMigration {
    /** The items completed ([changed]: to keep), and the ones the log does not explain. */
    class Completed(val items: List<CatalogItem>, val changed: List<CatalogItem>, val unexplained: Set<String>)

    /** [items] with the ones of before completed from [log] (every operation of the space). */
    suspend fun complete(items: List<CatalogItem>, log: suspend () -> List<CatalogOperation>): Completed {
        if (items.none { it.isOfBefore }) return Completed(items, emptyList(), emptySet())
        val replayed = SpaceCatalog()
        for (operation in log().sortedBy { it.stamp }) operation.change.applyTo(replayed, operation.stamp)
        val changed = mutableListOf<CatalogItem>()
        val unexplained = mutableSetOf<String>()
        val completed = items.map { item ->
            if (!item.isOfBefore) return@map item
            val again = replayed[item.id]
            val done = if (again != null && again.presence == item.presence && again.inSpace == item.inSpace) {
                item.copy(removed = again.removed, arrivals = again.arrivals).withBringer()
            } else {
                unexplained += item.id
                // As it was kept: its latest addition brought it (a member adding it again later
                // does not change it), or its removal took it out
                if (item.inSpace) item.copy(arrivals = listOf(item.presence)) else item.copy(removed = item.presence)
            }
            changed += done
            done
        }
        return Completed(completed, changed, unexplained)
    }
}
