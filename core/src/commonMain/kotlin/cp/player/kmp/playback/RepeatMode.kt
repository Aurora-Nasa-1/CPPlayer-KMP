package cp.player.kmp.playback

/** 播放循环模式。 */
enum class RepeatMode {
    /** 不循环（播完队列最后停）。 */
    OFF,
    /** 单曲循环。 */
    ONE,
    /** 列表循环。 */
    ALL,
}