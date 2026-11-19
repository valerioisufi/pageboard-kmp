package io.github.valerioisufi.pageboard.sync.iroh

/**
 * The relays the devices of an application meet through: the devices find each other there, and
 * the relay carries their traffic (encrypted, which it cannot read) when they cannot reach each
 * other directly. [urls] are the addresses of `iroh-relay` servers (`https://relay.example.com`),
 * [token] the access token they ask for (`access.shared_token`), if any.
 */
public data class RelayConfig(val urls: List<String> = emptyList(), val token: String? = null) {

    /** The relays as the bridge reads them: their addresses separated by spaces. */
    internal val bridgeRelays: String get() = urls.joinToString(" ")

    /** Without the token, a secret (logs). */
    override fun toString(): String = "RelayConfig(urls=$urls, token=${if (token == null) "none" else "hidden"})"

    public companion object {
        /** No relay: the devices reach each other directly only (the tests, one network). */
        public val NONE: RelayConfig = RelayConfig()

        /**
         * The public relays of n0, the authors of iroh: rate-limited, for development only. An
         * application gives its own relays.
         */
        public val N0_PUBLIC: RelayConfig = RelayConfig(listOf("n0"))
    }
}
