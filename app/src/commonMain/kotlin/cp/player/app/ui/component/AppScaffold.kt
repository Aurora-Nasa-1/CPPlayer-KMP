package cp.player.app.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp

data class TopBarAction(
    val icon: @Composable () -> Unit,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: @Composable () -> Unit,
    onBackPressed: (() -> Unit)? = null,
    navigationIcon: @Composable (() -> Unit)? = null,
    topBarActions: List<TopBarAction> = emptyList(),
    floatingActionButton: @Composable () -> Unit = {},
    isLoading: Boolean = false,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    bottomBar: @Composable () -> Unit = {},
    containerColor: Color = Color.Transparent,
    topBarContainerColor: Color = Color.Unspecified,
    content: @Composable (PaddingValues) -> Unit
) {
    val actualScrollBehavior = scrollBehavior ?: TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // If topBarContainerColor is Unspecified, make it match the scaffold's containerColor
    // This ensures that the unscrolled TopAppBar blends seamlessly with the background
    val actualTopBarContainerColor = if (topBarContainerColor == Color.Unspecified) {
        if (containerColor == Color.Transparent) MaterialTheme.colorScheme.surface.copy(alpha = 0f) else containerColor
    } else {
        topBarContainerColor
    }

    Scaffold(
        modifier = Modifier.nestedScroll(actualScrollBehavior.nestedScrollConnection),
        containerColor = containerColor,
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
        topBar = {
            LargeTopAppBar(
                title = title,
                navigationIcon = navigationIcon ?: {
                    if (onBackPressed != null) {
                        FilledIconButton(
                            onClick = onBackPressed,
                            modifier = Modifier.padding(start = 4.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    topBarActions.forEach { action ->
                        FilledIconButton(
                            onClick = action.onClick,
                            modifier = Modifier.padding(end = 4.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            action.icon()
                        }
                    }
                },
                scrollBehavior = actualScrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = actualTopBarContainerColor,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            content(PaddingValues(0.dp))
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: String,
    onBackPressed: (() -> Unit)? = null,
    navigationIcon: @Composable (() -> Unit)? = null,
    topBarActions: List<TopBarAction> = emptyList(),
    floatingActionButton: @Composable () -> Unit = {},
    isLoading: Boolean = false,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    bottomBar: @Composable () -> Unit = {},
    containerColor: Color = Color.Transparent,
    topBarContainerColor: Color = Color.Unspecified,
    content: @Composable (PaddingValues) -> Unit
) {
    AppScaffold(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        },
        onBackPressed = onBackPressed,
        navigationIcon = navigationIcon,
        topBarActions = topBarActions,
        floatingActionButton = floatingActionButton,
        isLoading = isLoading,
        scrollBehavior = scrollBehavior,
        bottomBar = bottomBar,
        containerColor = containerColor,
        topBarContainerColor = topBarContainerColor,
        content = content
    )
}