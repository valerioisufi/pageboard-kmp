package io.github.valerioisufi.drawview.search

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/**
 * Tells [SearchIndexer] when the device allows background indexing and embedding work
 * (Protected Variations):
 * * on a computer, at once (except while recording a lecture);
 * * on a phone or a tablet, when it is charging or idle (and never while recording).
 */
public interface IndexingConditions {
    /** Emits `true` when background indexing work may run, `false` when it should pause. */
    public val canIndex: Flow<Boolean>

    /** Always allows indexing (default on desktop when no lecture is recording, and in tests). */
    public object Always : IndexingConditions {
        override val canIndex: Flow<Boolean> = flowOf(true)
    }

    public companion object {
        /**
         * Combines a device readiness flow ([deviceReady]) with a recording state flow
         * ([isRecording]), pausing indexing whenever a lecture is being recorded.
         */
        public fun from(
            deviceReady: Flow<Boolean> = flowOf(true),
            isRecording: Flow<Boolean> = flowOf(false)
        ): IndexingConditions = object : IndexingConditions {
            override val canIndex: Flow<Boolean> =
                combine(deviceReady, isRecording) { ready, recording -> ready && !recording }
        }
    }
}

/** Mutable [IndexingConditions] controlled by application state (charging, idle, recording). */
public class MutableIndexingConditions(
    initialCanIndex: Boolean = true
) : IndexingConditions {
    private val state = MutableStateFlow(initialCanIndex)

    override val canIndex: StateFlow<Boolean> = state.asStateFlow()

    /** Updates whether background indexing is currently allowed. */
    public fun setCanIndex(allowed: Boolean) {
        state.value = allowed
    }
}
