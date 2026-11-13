package cp.player.app

/** Pure decisions for notification deep links, kept independent of Compose for regression tests. */
internal object MessageDeepLinkRouting {
    fun shouldHandle(isMain: Boolean, hasPendingRequest: Boolean): Boolean =
        isMain && hasPendingRequest

    /** Returns only the exact requested provider; never falls back to the active provider. */
    fun <T> resolveProvider(
        requestedProviderId: String,
        providers: List<T>,
        providerId: (T) -> String,
    ): T? = providers.firstOrNull { providerId(it) == requestedProviderId }

    fun isAlreadyActive(requestedProviderId: String, activeProviderId: String): Boolean =
        requestedProviderId == activeProviderId
}
