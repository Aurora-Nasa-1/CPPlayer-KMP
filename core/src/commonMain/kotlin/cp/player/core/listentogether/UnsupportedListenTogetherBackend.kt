package cp.player.core.listentogether

import cp.player.core.BackendResult

/**
 * 音源未声明「一起听」能力时的占位后端。
 *
 * 一起听是**音源侧**能力（房间协议、心跳、队列上报都由音源实现），宿主不代办。
 * 旧的实现直接打网易云 `listentogether/...` 端点，属于 JSON 兼容层的一部分，已随该层删除；
 * 新音源只要想支持，实现 [ListenTogetherBackend] 并在宿主侧装配即可。
 *
 * [isSupported] 恒为 false，UI 据此把入口置灰并说明原因，而不是让用户点进去再报错。
 */
object UnsupportedListenTogetherBackend : ListenTogetherBackend {

    const val UNSUPPORTED_MESSAGE: String = "当前音源不支持一起听"

    override val providerId: String = "unified"

    override fun isSupported(): Boolean = false

    override suspend fun membership(): BackendResult<ListenTogetherMembership> = unsupported()

    override suspend fun createRoom(): BackendResult<ListenTogetherRoom> = unsupported()

    override suspend fun accept(roomId: String, inviterId: Long): BackendResult<Unit> = unsupported()

    override suspend fun end(roomId: String): BackendResult<Unit> = unsupported()

    override suspend fun snapshot(roomId: String): BackendResult<RoomSnapshot> = unsupported()

    override suspend fun heartbeat(
        roomId: String,
        songId: String,
        playStatus: String,
        progressMs: Long,
    ): BackendResult<Unit> = unsupported()

    override suspend fun reportPlayCommand(
        roomId: String,
        commandType: String,
        playStatus: String,
        progressMs: Long,
        formerSongId: String?,
        targetSongId: String?,
        clientSeq: Long,
    ): BackendResult<Unit> = unsupported()

    override suspend fun reportPlaylist(
        roomId: String,
        userId: Long,
        version: Long,
        songIds: List<String>,
    ): BackendResult<Unit> = unsupported()

    private fun <T> unsupported(): BackendResult<T> = BackendResult.Unsupported(UNSUPPORTED_MESSAGE)
}
