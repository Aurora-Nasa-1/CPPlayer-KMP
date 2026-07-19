package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cp.player.app.AppModel
import cp.player.app.ui.component.MiniPlayer
import cp.player.app.ui.component.CpSpacing

/** Responsive application shell for the four primary destinations. */
class MainScreen : Screen {
    @OptIn(ExperimentalSharedTransitionApi::class)
    @Composable
    override fun Content() {
        var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
        val visitedTabs = remember { mutableStateListOf(selectedIndex) }
        val navigator = LocalNavigator.current
        val tabs = remember {
            listOf(
                TabItem(HomeScreen(), "首页", Icons.Filled.Home, Icons.Outlined.Home),
                TabItem(SearchScreen(), "搜索", Icons.Filled.Search, Icons.Outlined.Search),
                TabItem(LibraryScreen(), "我的", Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic),
                TabItem(SettingsScreen(), "设置", Icons.Filled.Settings, Icons.Outlined.Settings),
            )
        }
        val playbackState by AppModel.playback.state.collectAsState()
        val controller = AppModel.playback
        val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
        val selectTab: (Int) -> Unit = { index ->
            if (index !in visitedTabs) visitedTabs.add(index)
            selectedIndex = index
        }

        // 全局 Snackbar：收集各 Screen/ScreenModel 发出的操作反馈
        androidx.compose.runtime.LaunchedEffect(Unit) {
            cp.player.app.ui.util.UiEvents.messages.collect { message ->
                snackbarHostState.showSnackbar(
                    message,
                    withDismissAction = true,
                    duration = androidx.compose.material3.SnackbarDuration.Short,
                )
            }
        }

        var isPlayerExpanded by rememberSaveable { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        
        // 捕捉返回键
        androidx.activity.compose.BackHandler(enabled = isPlayerExpanded) {
            isPlayerExpanded = false
        }

        val expandProgress by animateFloatAsState(
            targetValue = if (isPlayerExpanded) 1f else 0f,
            animationSpec = tween(400, easing = LinearEasing),
            label = "expandProgress"
        )

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val expanded = maxWidth >= 840.dp
            
            // 主内容，应用缩放和变暗
            Box(Modifier.fillMaxSize().graphicsLayer {
                val scale = 1f - expandProgress * 0.03f
                scaleX = scale
                scaleY = scale
                translationY = expandProgress * 8f
            }) {
                if (expanded) {
                    Row(Modifier.fillMaxSize()) {
                        AppNavigationRail(tabs, selectedIndex, selectTab)
                        Column(Modifier.weight(1f)) {
                            AppTopBar(tabs[selectedIndex].label, navigator)
                            TabContent(tabs, visitedTabs, selectedIndex, Modifier.weight(1f))
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        AppTopBar(tabs[selectedIndex].label, navigator)
                        TabContent(tabs, visitedTabs, selectedIndex, Modifier.weight(1f))
                        // 占位 miniplayer 的高度，如果需要的话可以放个 Spacer
                        AppNavigationBar(tabs, selectedIndex, selectTab)
                    }
                }
            }

            if (expandProgress > 0.01f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = expandProgress * 0.3f))
                )
            }

            // 全局 Snackbar（操作反馈）
            androidx.compose.material3.SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                    .padding(bottom = if (playbackState.currentTrack != null) 180.dp else 96.dp),
            ) { data ->
                androidx.compose.material3.Snackbar(
                    snackbarData = data,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }

            SharedTransitionLayout(Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = isPlayerExpanded,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(400)) togetherWith fadeOut(animationSpec = tween(400))
                    },
                    label = "PlayerTransition",
                    modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                ) { isExpanded ->
                    if (isExpanded) {
                    PlayerScreenContent(
                        state = playbackState,
                        animatedVisibilityScope = this@AnimatedContent,
                        onBack = { isPlayerExpanded = false },
                        onTogglePlay = controller::togglePlayPause,
                        onSeek = controller::seekTo,
                        onSkipNext = controller::skipNext,
                        onSkipPrev = controller::skipPrevious,
                        onRepeat = {
                            controller.setRepeatMode(
                                when (playbackState.repeatMode) {
                                    cp.player.kmp.playback.RepeatMode.OFF -> cp.player.kmp.playback.RepeatMode.ALL
                                    cp.player.kmp.playback.RepeatMode.ALL -> cp.player.kmp.playback.RepeatMode.ONE
                                    cp.player.kmp.playback.RepeatMode.ONE -> cp.player.kmp.playback.RepeatMode.OFF
                                }
                            )
                        },
                        onShuffle = controller::toggleShuffle,
                        onClearQueue = controller::clearQueue,
                        onPlayAt = { idx -> scope.launch { controller.playAt(idx) } },
                        onRemoveQueue = { idx -> scope.launch { controller.removeQueueItem(idx) } },
                        onMoveQueue = { from, to -> scope.launch { controller.moveQueueItem(from, to) } },
                    )
                } else {
                    Box(Modifier.fillMaxSize()) {
                        // 在底部渲染 MiniPlayer
                        if (playbackState.currentTrack != null) {
                            val bottomPadding = if (expanded) 24.dp else 104.dp // 增加与底栏的间距，提升视觉呼吸感
                            Box(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = bottomPadding)) {
                                MiniPlayer(
                                    state = playbackState,
                                    animatedVisibilityScope = this@AnimatedContent,
                                    onClick = { isPlayerExpanded = true },
                                    onTogglePlay = controller::togglePlayPause,
                                    onSkipPrev = controller::skipPrevious,
                                    onSkipNext = controller::skipNext,
                                )
                            }
                        }
                    }
                }
            }
        } // End of SharedTransitionLayout
        } // End of BoxWithConstraints
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(title: String, navigator: cafe.adriel.voyager.navigator.Navigator?) {
    val activeProvider by AppModel.activeProviderFlow.collectAsState()
    
    TopAppBar(
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                activeProvider?.let {
                    Text(
                        it.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        actions = {
            IconButton(
                onClick = { navigator?.push(SettingsScreen()) },
                modifier = Modifier.padding(end = 8.dp)
            ) {
                val profile by AppModel.userProfileFlow.collectAsState()
                val avatarUrl = profile?.avatarUrl
                if (!avatarUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = avatarUrl,
                        contentDescription = "用户头像",
                        modifier = Modifier.size(32.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    )
}

@Composable
private fun TabContent(
    tabs: List<TabItem>,
    visitedTabs: List<Int>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
) {
    val retainedIndices = visitedTabs.sorted()
    Layout(
        modifier = modifier.fillMaxSize(),
        content = {
            retainedIndices.forEach { index ->
                Box(Modifier.fillMaxSize()) { tabs[index].screen.Content() }
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.getOrNull(retainedIndices.indexOf(selectedIndex))?.placeRelative(0, 0)
        }
    }
}

@Composable
private fun AppNavigationBar(tabs: List<TabItem>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        tabs.forEachIndexed { index, tab ->
            val selected = selectedIndex == index
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(index) },
                icon = { Icon(if (selected) tab.selectedIcon else tab.unselectedIcon, tab.label) },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            )
        }
    }
}

@Composable
private fun AppNavigationRail(tabs: List<TabItem>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            tabs.forEachIndexed { index, tab ->
                val selected = selectedIndex == index
                NavigationRailItem(
                    selected = selected,
                    onClick = { onSelect(index) },
                    icon = { Icon(if (selected) tab.selectedIcon else tab.unselectedIcon, tab.label) },
                    label = { Text(tab.label) },
                    colors = NavigationRailItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                )
            }
        }
    }
}

private data class TabItem(
    val screen: Screen,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)
