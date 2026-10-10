package io.github.valerioisufi.drawview.core.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import io.github.valerioisufi.drawview.core.model.DrawDocument

/**
 * Recomposes the caller with every change of this document ([DrawDocument.revision]): the model
 * is not Compose state, so what shows the document calls it before reading the document.
 *
 * @return the revision of the document shown.
 */
@Composable
fun DrawDocument.observeChanges(): Long = revision.collectAsState().value
