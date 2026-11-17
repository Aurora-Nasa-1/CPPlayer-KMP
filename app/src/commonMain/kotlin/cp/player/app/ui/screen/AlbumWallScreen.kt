package cp.player.app.ui.screen

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.core.screen.Screen
import cp.player.app.ui.navigation.LocalAppNavigator

class AlbumWallScreen(private val initialSource: String = "queue") : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.current
        cp.player.app.ui.wall.PosterWallScreen(
            initialSource = initialSource,
            onBack = { navigator.pop() }
        )
    }
}
