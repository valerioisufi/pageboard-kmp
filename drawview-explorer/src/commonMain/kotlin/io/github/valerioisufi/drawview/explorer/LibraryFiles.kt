package io.github.valerioisufi.drawview.explorer

import io.github.valerioisufi.drawview.store.DocumentSummary

// The names and the order of the items of the library

/**
 * Whether the [name] of an item holds every word of [query], in any order, whatever their case and
 * their accents ("universita" finds "Università").
 */
internal fun nameMatches(name: String, query: String): Boolean {
    val folded = foldAccents(name)
    return foldAccents(query).split(' ', '\t').filter { it.isNotEmpty() }.all { it in folded }
}

/** [text] in lower case, its Latin letters without their accents. */
private fun foldAccents(text: String): String = buildString(text.length) {
    for (char in text) {
        val lower = char.lowercaseChar()
        append(
            when (lower) {
                'à', 'á', 'â', 'ã', 'ä', 'å', 'ā', 'ă', 'ą' -> 'a'
                'ç', 'ć', 'č' -> 'c'
                'ď', 'đ' -> 'd'
                'è', 'é', 'ê', 'ë', 'ē', 'ĕ', 'ė', 'ę', 'ě' -> 'e'
                'ì', 'í', 'î', 'ï', 'ī', 'ĭ', 'į' -> 'i'
                'ł' -> 'l'
                'ñ', 'ń', 'ň' -> 'n'
                'ò', 'ó', 'ô', 'õ', 'ö', 'ø', 'ō', 'ŏ', 'ő' -> 'o'
                'ř', 'ŕ' -> 'r'
                'ś', 'š', 'ş' -> 's'
                'ť', 'ţ' -> 't'
                'ù', 'ú', 'û', 'ü', 'ū', 'ŭ', 'ů', 'ű', 'ų' -> 'u'
                'ý', 'ÿ' -> 'y'
                'ź', 'ż', 'ž' -> 'z'
                else -> lower
            }
        )
    }
}

/**
 * Sorts the items of a folder by [option], keeping the folders before the documents when
 * [foldersOnTop] (the relative order of equal items is preserved).
 */
internal fun sortFiles(
    items: List<FileExplorerViewModel.FileItem>,
    option: FileExplorerViewModel.SortOption,
    ascending: Boolean,
    foldersOnTop: Boolean
): List<FileExplorerViewModel.FileItem> {
    val comparator: Comparator<FileExplorerViewModel.FileItem> = when (option) {
        FileExplorerViewModel.SortOption.NAME -> compareBy { it.name.lowercase() }
        FileExplorerViewModel.SortOption.DATE_CREATED -> compareBy { it.createdAt }
        FileExplorerViewModel.SortOption.DATE_MODIFIED -> compareBy { it.modifiedAt }
        FileExplorerViewModel.SortOption.LAST_OPENED -> compareBy { it.lastOpenedAt ?: 0L }
    }
    val sorted = items.sortedWith(if (ascending) comparator else comparator.reversed())
    return if (foldersOnTop) sorted.sortedBy { it.type != FileExplorerViewModel.FileType.FOLDER } else sorted
}

internal fun DocumentSummary.toFileItem() = FileExplorerViewModel.FileItem(
    type = FileExplorerViewModel.FileType.FILE,
    id = id,
    name = name,
    createdAt = createdAt,
    modifiedAt = modifiedAt,
    lastOpenedAt = lastOpenedAt,
    isShared = isShared,
    folderId = folderId,
    pageCount = pageCount,
    hasPdf = hasPdf
)
