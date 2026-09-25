package cp.player.core.playback

/**
 * 音频输出「静音门」——实现 [OutputMode.SERVER_ONLY] 所需的「本机不出声」。
 *
 * ### 为什么用装饰器而不是另写一个播放器
 * 静默模式仍然需要一条**真实的时间轴**：队列推进靠 `Ended` 事件、
 * 歌词高亮靠 `positionMs`、听歌打卡靠播放时长。如果用一个纯计时器伪造播放器，
 * 这些全部要自己实现，且会与接收端的真实进度漂移。
 *
 * 这里改为委托真实播放器（Desktop 的 JavaFX / Android 的 Media3），
 * 只把音量恒定为 0：时间轴、时长、格式信息、播完事件全部保持真实，
 * 代价是本机仍会解码一份音频。
 *
 * ### 运行时切换
 * [setMuted] 可随时调用，不需要重建 [PlaybackController]、不会丢队列。
 * 解除静音时恢复用户此前设定的音量（而非硬编码 1.0），避免突然爆音。
 *
 * @param delegate 真实平台播放器。
 * @param muted 初始是否静音。
 */
class SilentOutputPlayer(
    private val delegate: PlatformPlayer,
    muted: Boolean = false,
) : PlatformPlayer by delegate {

    /** 当前是否静音。 */
    @Volatile
    var muted: Boolean = muted
        private set

    /** 用户最近一次请求的音量；解除静音时据此恢复。 */
    @Volatile
    private var lastRequestedVolume: Float = 1f

    override fun setVolume(volume: Float) {
        lastRequestedVolume = volume.coerceIn(0f, 1f)
        delegate.setVolume(if (muted) 0f else lastRequestedVolume)
    }

    override fun getVolume(): Float = if (muted) 0f else delegate.getVolume()

    /** 切换静音；值未变化时为空操作（避免重复调用底层）。 */
    fun setMuted(value: Boolean) {
        if (muted == value) return
        muted = value
        delegate.setVolume(if (value) 0f else lastRequestedVolume)
    }
}
