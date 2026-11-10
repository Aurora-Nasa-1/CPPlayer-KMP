package cp.player.core.listentogether

import cp.player.core.BackendResult
import cp.player.core.music.MusicSourceIdentity
import cp.player.core.music.UnifiedMusicSource
import cp.player.sdk.*
import kotlinx.coroutines.CancellationException

/** Maps typed SDK operations to the room engine and fences room writes across account changes. */
class SdkListenTogetherBackend(private val source: UnifiedMusicSource) : ListenTogetherBackend {
    override val providerId: String get() = source.accountIdentity()?.providerId.orEmpty()
    private var supportedIdentity: MusicSourceIdentity? = null
    private var supported = false
    private var roomIdentity: MusicSourceIdentity? = null
    private var boundRoomId: String? = null

    override fun isSupported(): Boolean =
        source.accountIdentity()?.let { if (it == supportedIdentity) supported else true } ?: false

    override suspend fun membership(): BackendResult<ListenTogetherMembership> {
        val identity = source.accountIdentity()
        return convert(session { source.getListenTogetherStatus(Bytes(byteArrayOf())) }) {
            supportedIdentity = identity
            supported = true
            val room = it.room?.toRoom()
            roomIdentity = identity.takeIf { _ -> it.inRoom }
            boundRoomId = room?.roomId.takeIf { _ -> it.inRoom }
            ListenTogetherMembership(it.inRoom, room, it.connectionStatus)
        }.also {
            if (it is BackendResult.Unsupported) {
                supportedIdentity = identity
                supported = false
            }
        }
    }

    override suspend fun createRoom(): BackendResult<ListenTogetherRoom> =
        convert(session { source.createListenTogetherRoom(Bytes(byteArrayOf())) }) {
            it.toRoom().also { room -> bind(room.roomId) }
        }

    override suspend fun accept(roomId: String, inviterId: Long): BackendResult<Unit> =
        convert(session { source.acceptListenTogetherInvitation(ListenInvitationRequest(roomId, inviterId.toString())) }) {
            require(it.id == roomId) { "Provider joined a different room" }
            it.toRoom()
            bind(roomId)
        }

    override suspend fun end(roomId: String): BackendResult<Unit> = inRoom(roomId) {
        acknowledge(source.endListenTogether(ListenRoomRequest(roomId))).also {
            if (it is BackendResult.Success) { boundRoomId = null; roomIdentity = null }
        }
    }

    override suspend fun snapshot(roomId: String): BackendResult<RoomSnapshot> = inRoom(roomId) {
        convert(source.getListenTogetherSnapshot(ListenRoomRequest(roomId))) { snapshot ->
            RoomSnapshot(
                snapshot.command?.let {
                    RoomPlaybackCommand(
                        LtCommandType.from(it.action), LtCommandType.from(it.playStatus), it.positionMs,
                        it.trackId, it.formerTrackId, it.userId?.legacyId() ?: 0L, it.sequence, it.serverSequence,
                    )
                },
                snapshot.playlist?.let {
                    RoomPlaylist(it.trackIds, it.replace, it.versions.associate { v -> v.userId.legacyId() to v.version })
                },
            )
        }
    }

    override suspend fun heartbeat(roomId: String, songId: String, playStatus: String, progressMs: Long): BackendResult<Unit> =
        inRoom(roomId) {
            convert(source.heartbeatListenTogether(ListenHeartbeatRequest(
                roomId, songId.takeIf { it.isNotEmpty() }, progressMs, playStatus,
            ))) { Unit }
        }

    override suspend fun reportPlayCommand(
        roomId: String, commandType: String, playStatus: String, progressMs: Long,
        formerSongId: String?, targetSongId: String?, clientSeq: Long,
    ): BackendResult<Unit> = inRoom(roomId) {
        acknowledge(source.sendListenTogetherCommand(ListenCommandRequest(roomId, ListenCommand(
            commandType, targetSongId?.takeIf { it.isNotEmpty() }, progressMs, clientSeq, playStatus,
            formerSongId?.takeIf { it.isNotEmpty() }, serverSequence = 0,
        ))))
    }

    override suspend fun reportPlaylist(roomId: String, userId: Long, version: Long, songIds: List<String>): BackendResult<Unit> =
        inRoom(roomId) {
            acknowledge(source.syncListenTogetherQueue(ListenQueueRequest(
                roomId, songIds, version, userId.toString(), "REPLACE",
            )))
        }

    private fun bind(roomId: String) {
        roomIdentity = source.accountIdentity()
        boundRoomId = roomId
    }

    private suspend fun <T> inRoom(roomId: String, action: suspend () -> BackendResult<T>): BackendResult<T> {
        if (roomIdentity == null || roomIdentity != source.accountIdentity() || roomId != boundRoomId)
            return BackendResult.Unsupported("Listen-together room belongs to a different session")
        return session(action)
    }

    private suspend fun <T> session(action: suspend () -> BackendResult<T>): BackendResult<T> {
        val identity = source.accountIdentity()
        val result = action()
        if (source.accountIdentity() != identity) throw CancellationException("Listen-together session changed")
        return result
    }

    private fun acknowledge(result: BackendResult<MutationResult>): BackendResult<Unit> = when (result) {
        is BackendResult.Success -> if (result.data.success) BackendResult.Success(Unit)
            else BackendResult.Error("Provider rejected the listen-together operation")
        is BackendResult.Error -> result
        is BackendResult.Unsupported -> result
    }

    private fun <T, R> convert(result: BackendResult<T>, map: (T) -> R): BackendResult<R> = when (result) {
        is BackendResult.Success -> try { BackendResult.Success(map(result.data)) }
            catch (e: CancellationException) { throw e }
            catch (_: UnsupportedIdentity) {
                BackendResult.Unsupported("This room uses user IDs not supported by the current listen-together UI")
            }
            catch (e: Exception) { BackendResult.Error("Invalid listen-together response", cause = e) }
        is BackendResult.Error -> result
        is BackendResult.Unsupported -> result
    }

    private fun ListenRoom.toRoom() = ListenTogetherRoom(
        id, creatorId?.legacyId() ?: 0L, roomType, createdAtEpochMs, effectiveDurationMs, waitMs,
        members.map { ListenTogetherMember(it.user.id.legacyId(), it.user.nickname, it.user.avatarUrl) },
    )

    // Existing room/UI models use Long IDs. Reject rather than silently rewriting opaque SDK IDs.
    private fun String.legacyId(): Long =
        toLongOrNull()?.takeIf { it > 0 && it.toString() == this } ?: throw UnsupportedIdentity()
    private class UnsupportedIdentity : Exception()
}
