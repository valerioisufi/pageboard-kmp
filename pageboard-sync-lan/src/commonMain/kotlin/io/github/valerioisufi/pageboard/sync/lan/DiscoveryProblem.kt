package io.github.valerioisufi.pageboard.sync.lan

import kotlin.reflect.KClass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Why the devices of the local network cannot be found, or cannot find this one (see
 * [LanDiscovery.problems]): what the user, or the developer of the application, can do something
 * about.
 */
public sealed interface DiscoveryProblem {
    /** The system refuses the local network to this application (a permission of the user). */
    public data object PermissionDenied : DiscoveryProblem

    /**
     * The application does not declare what the system needs to use the local network: the
     * [missing] keys of its `Info.plist` (e.g. `NSBonjourServices` with `_pageboard._tcp`).
     */
    public data class MissingConfiguration(val missing: List<String>) : DiscoveryProblem

    /** This device has no address on a local network (no Wi-Fi, no cable). */
    public data object NoLocalNetwork : DiscoveryProblem

    /** Announcing this device failed: the [code] of the system if it has one, and its [message]. */
    public data class AdvertiseFailed(val code: Int? = null, val message: String? = null) : DiscoveryProblem

    /** Looking for the other devices failed: the [code] of the system if it has one, and its [message]. */
    public data class BrowseFailed(val code: Int? = null, val message: String? = null) : DiscoveryProblem
}

/** The problems of a discovery, one of each kind: reported as they happen, cleared once it works again. */
internal class DiscoveryProblems {
    private val state = MutableStateFlow<List<DiscoveryProblem>>(emptyList())

    val problems: StateFlow<List<DiscoveryProblem>> = state.asStateFlow()

    /** Reports [problem], in place of the one of the same kind. */
    fun report(problem: DiscoveryProblem) = state.update { list -> list.filterNot { it::class == problem::class } + problem }

    /** Clears the problems of the [kinds] (what failed works again). */
    fun clear(vararg kinds: KClass<out DiscoveryProblem>) = state.update { list -> list.filterNot { problem -> kinds.any { it.isInstance(problem) } } }

    fun clearAll() {
        state.value = emptyList()
    }

    companion object {
        /** The problems of a discovery that reports none. */
        val NONE: StateFlow<List<DiscoveryProblem>> = MutableStateFlow(emptyList<DiscoveryProblem>()).asStateFlow()
    }
}

/** The keys of the `Info.plist` an Apple application needs for the local network. */
internal const val BONJOUR_SERVICES_KEY = "NSBonjourServices"
internal const val LOCAL_NETWORK_USAGE_KEY = "NSLocalNetworkUsageDescription"

/**
 * The problem of a failure of Bonjour (`NSNetServicesErrorCode`, or a code of DNS-SD) while
 * [announcing] this device or looking for the others.
 */
internal fun bonjourProblem(code: Int?, announcing: Boolean): DiscoveryProblem = when (code) {
    // kDNSServiceErr_PolicyDenied: the local network refused by the user
    -65570 -> DiscoveryProblem.PermissionDenied
    // NSNetServicesMissingRequiredConfigurationError: the service missing from NSBonjourServices
    -72008 -> DiscoveryProblem.MissingConfiguration(listOf("$BONJOUR_SERVICES_KEY: ${LanDiscovery.SERVICE_TYPE}"))
    else -> {
        val message = when (code) {
            -72000 -> "NSNetServicesUnknownError"
            -72001 -> "NSNetServicesCollisionError"
            -72002 -> "NSNetServicesNotFoundError"
            -72003 -> "NSNetServicesActivityInProgress"
            -72004 -> "NSNetServicesBadArgumentError"
            -72005 -> "NSNetServicesCancelledError"
            -72006 -> "NSNetServicesInvalidError"
            -72007 -> "NSNetServicesTimeoutError"
            else -> null
        }
        if (announcing) DiscoveryProblem.AdvertiseFailed(code, message) else DiscoveryProblem.BrowseFailed(code, message)
    }
}
