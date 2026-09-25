package cp.player.app

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import cp.player.kmp.playback.PlaybackController

/**
 * 把系统媒体会话（通知栏 / 锁屏 / 耳机按键）的传输命令**转交给应用自己的 [PlaybackController]**，
 * 而不是直接落到底层 ExoPlayer。
 *
 * ### 为什么必须这样
 * Media3 的 [androidx.media3.session.MediaSession] 是包在 [cp.player.kmp.playback.SharedMedia3Player]
 * 的 ExoPlayer 上的，而那个 ExoPlayer **永远只持有一个 media item**——真正的播放队列在
 * [PlaybackController] 里。于是：
 * - ExoPlayer 认为"没有下一首"，`getAvailableCommands()` 里不含 SEEK_TO_NEXT/PREVIOUS，
 *   系统通知因此**根本不显示切歌按钮**；
 * - 即使显示了，`seekToNextMediaItem()` 在一个 item 上也是空操作。
 * 结果就是应用内能切歌、通知栏/耳机切不了——即"切歌兼容性差"。
 *
 * ### 安全性
 * 除了下面显式覆写的几个命令，其余调用全部委托给被包装的播放器；
 * 且当 [PlaybackController] 尚不可用时回退到父类默认行为。
 * 因此本类相对原实现是**严格增量**的：最坏情况与改动前完全一致。
 */
internal class ControllerForwardingPlayer(
    player: Player,
    private val controllerProvider: () -> PlaybackController?,
) : ForwardingPlayer(player) {

    /** 后端尚未初始化时 [controllerProvider] 可能抛异常，此处统一兜住并回退默认行为。 */
    private fun controller(): PlaybackController? = runCatching { controllerProvider() }.getOrNull()

    /**
     * 把切歌命令广告出去。
     *
     * 底层 ExoPlayer 只有单个 item，不会报告 SEEK_TO_NEXT/PREVIOUS；
     * 但本类确实实现了这些命令（转交控制器），所以在这里补上，
     * 否则系统通知不会渲染切歌按钮。
     */
    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()

    // ---- 队列导航：交给控制器（它才知道队列） ----

    override fun seekToNext() {
        controller()?.skipNext() ?: super.seekToNext()
    }

    override fun seekToPrevious() {
        controller()?.skipPrevious() ?: super.seekToPrevious()
    }

    override fun seekToNextMediaItem() {
        controller()?.skipNext() ?: super.seekToNextMediaItem()
    }

    override fun seekToPreviousMediaItem() {
        controller()?.skipPrevious() ?: super.seekToPreviousMediaItem()
    }

    /**
     * seek 也走控制器：控制器会把目标位置**乐观写回** [PlaybackController.state]，
     * 否则通知栏拖完进度条会先回弹到旧位置再跳过去。
     */
    override fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        val c = controller()
        if (c != null) c.seekTo(target) else super.seekTo(target)
    }
}
