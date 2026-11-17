package cp.player.app

import cp.player.app.ui.screen.MainScreen
import cp.player.app.ui.screen.OnboardingScreen
import cp.player.app.ui.screen.PlayerScreen
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 根栈迷你播放器的显示判据。
 *
 * 首次启动时 `startDestination` 不是 Main，本来就不会显示；从设置里重看引导时
 * `startDestination` 仍是 Main，必须靠「当前页是 OnboardingScreen」把它藏掉。
 */
class MiniPlayerVisibilityTest {

    @Test
    fun `onboarding route hides mini player`() {
        assertFalse(
            shouldShowGlobalMiniPlayer(
                startDestination = AppStartDestination.Main,
                lastItem = OnboardingScreen(),
                miniPlayerBlockedByChat = false,
            ),
        )
    }

    @Test
    fun `main route keeps mini player`() {
        assertTrue(
            shouldShowGlobalMiniPlayer(
                startDestination = AppStartDestination.Main,
                lastItem = MainScreen(),
                miniPlayerBlockedByChat = false,
            ),
        )
    }

    @Test
    fun `player route hides mini player`() {
        assertFalse(
            shouldShowGlobalMiniPlayer(
                startDestination = AppStartDestination.Main,
                lastItem = PlayerScreen(),
                miniPlayerBlockedByChat = false,
            ),
        )
    }

    @Test
    fun `onboarding start destination hides mini player`() {
        assertFalse(
            shouldShowGlobalMiniPlayer(
                startDestination = AppStartDestination.Onboarding,
                lastItem = MainScreen(),
                miniPlayerBlockedByChat = false,
            ),
        )
    }

    @Test
    fun `chat blocking hides mini player`() {
        assertFalse(
            shouldShowGlobalMiniPlayer(
                startDestination = AppStartDestination.Main,
                lastItem = MainScreen(),
                miniPlayerBlockedByChat = true,
            ),
        )
    }
}
