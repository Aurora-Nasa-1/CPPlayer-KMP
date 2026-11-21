package cp.player.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cp.player.app.AppModel
import cp.player.app.presentation.SocialAvailability
import cp.player.app.presentation.SocialFeature
import cp.player.app.presentation.SocialNavigation
import cp.player.core.provider.BackendProvider

internal fun SocialAvailability.statusText(feature: String): String = when (this) {
    SocialAvailability.Loading -> "正在初始化音源"
    SocialAvailability.NoProvider -> "未启用在线音源"
    SocialAvailability.ProviderError -> "音源初始化失败"
    SocialAvailability.Unsupported -> "当前音源不提供$feature"
    SocialAvailability.Available -> "当前音源支持$feature"
}

@Composable
internal fun socialFeatureAvailability(provider: BackendProvider?, feature: SocialFeature): SocialAvailability {
    val backendState by AppModel.backendState.collectAsState()
    val capabilities by AppModel.socialCapabilities.collectAsState()
    val sourceGeneration by AppModel.sourceGeneration.collectAsState()
    val accountGeneration by AppModel.accountGeneration.collectAsState()
    return feature.availability(provider, backendState, capabilities, sourceGeneration, accountGeneration)
}

@Composable
internal fun socialNavigationState(provider: BackendProvider?): SocialNavigation {
    val replaceSearch by AppModel.dynamicReplacesSearchFlow.collectAsState()
    val hideDynamic by AppModel.hideDynamicEntryFlow.collectAsState()
    val hideMessages by AppModel.hideMessagesEntryFlow.collectAsState()
    return SocialNavigation(
        feed = socialFeatureAvailability(provider, SocialFeature.Feed),
        inbox = socialFeatureAvailability(provider, SocialFeature.Inbox),
        replaceSearch = replaceSearch,
        hideDynamic = hideDynamic,
        hideMessages = hideMessages,
    )
}
