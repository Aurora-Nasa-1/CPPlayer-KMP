package cp.player.app.presentation

import cp.player.core.BackendState
import cp.player.core.provider.BackendProvider
import cp.player.sdk.ExtendedCapability

internal enum class SocialAvailability {
    Loading, NoProvider, ProviderError, Unsupported, Available
}

internal data class SocialCapabilities(
    val provider: BackendProvider? = null,
    val sourceGeneration: Int = 0,
    val accountGeneration: Int = 0,
    val operations: List<String>? = null,
    val failed: Boolean = false,
)

internal enum class SocialFeature(vararg val operations: String) {
    Feed(ExtendedCapability.GETEVENTS),
    UserFeed(ExtendedCapability.GETUSEREVENTS),
    Inbox(ExtendedCapability.GETPRIVATEMESSAGES, ExtendedCapability.GETRECENTCONTACTS),
    Unread(ExtendedCapability.GETUNREADCOUNT),
    History(ExtendedCapability.GETMESSAGEHISTORY);

    fun availability(
        provider: BackendProvider?,
        state: BackendState,
        capabilities: SocialCapabilities = SocialCapabilities(),
        sourceGeneration: Int = 0,
        accountGeneration: Int = 0,
    ): SocialAvailability = when {
        state is BackendState.Error && provider?.isReady() != true -> SocialAvailability.ProviderError
        state == BackendState.NoProvider -> SocialAvailability.NoProvider
        provider?.isReady() != true || provider !== capabilities.provider ||
            sourceGeneration != capabilities.sourceGeneration ||
            accountGeneration != capabilities.accountGeneration -> SocialAvailability.Loading
        capabilities.failed -> SocialAvailability.ProviderError
        else -> capabilities.operations?.let(::fromOperations) ?: SocialAvailability.Loading
    }

    fun fromOperations(declared: List<String>): SocialAvailability =
        if (operations.any { it in declared }) SocialAvailability.Available else SocialAvailability.Unsupported
}

internal data class SocialNavigation(
    val feed: SocialAvailability,
    val inbox: SocialAvailability,
    val replaceSearch: Boolean,
    val hideDynamic: Boolean,
    val hideMessages: Boolean,
) {
    val dynamicAvailable get() = feed == SocialAvailability.Available && !hideDynamic
    val dynamicAsTab get() = dynamicAvailable && replaceSearch
    val showMessages get() = inbox == SocialAvailability.Available && !hideMessages
}
