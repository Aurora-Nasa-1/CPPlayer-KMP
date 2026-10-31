package cp.player.core.api

import kotlinx.serialization.json.JsonElement

// Additive string overloads retain the legacy Long API without coercing opaque IDs.
suspend fun MusicApiService.getPlaylistDetail(id: String): JsonElement =
    callApi(MusicApiMethod.PLAYLIST_DETAIL, mapOf("id" to id))

suspend fun MusicApiService.getPlaylistTracks(id: String, limit: Int = 300, offset: Int = 0): JsonElement =
    callApi(MusicApiMethod.PLAYLIST_TRACK_ALL, mapOf("id" to id, "limit" to limit.toString(), "offset" to offset.toString()))

suspend fun MusicApiService.addTracksToPlaylist(pid: String, trackIds: List<String>): JsonElement =
    callApi(MusicApiMethod.PLAYLIST_TRACKS, mapOf("op" to "add", "pid" to pid, "tracks" to trackIds.joinToString(",")))

suspend fun MusicApiService.removeTracksFromPlaylist(pid: String, trackIds: List<String>): JsonElement =
    callApi(MusicApiMethod.PLAYLIST_TRACKS, mapOf("op" to "del", "pid" to pid, "tracks" to trackIds.joinToString(",")))

suspend fun MusicApiService.deletePlaylist(id: String): JsonElement =
    callApi(MusicApiMethod.PLAYLIST_DELETE, mapOf("id" to id))

suspend fun MusicApiService.subscribePlaylist(id: String, t: Int = 1): JsonElement =
    callApi(MusicApiMethod.PLAYLIST_SUBSCRIBE, mapOf("id" to id, "t" to t.toString()))

suspend fun MusicApiService.updatePlaylistName(id: String, name: String): JsonElement =
    callApi(MusicApiMethod.PLAYLIST_NAME_UPDATE, mapOf("id" to id, "name" to name))

suspend fun MusicApiService.getAlbumDetail(id: String): JsonElement =
    callApi(MusicApiMethod.ALBUM_DETAIL, mapOf("id" to id))

suspend fun MusicApiService.subscribeAlbum(id: String, t: Int = 1): JsonElement =
    callApi(MusicApiMethod.ALBUM_SUB, mapOf("id" to id, "t" to t.toString()))

suspend fun MusicApiService.getArtistDetail(id: String): JsonElement =
    callApi(MusicApiMethod.ARTIST_DETAIL, mapOf("id" to id))

suspend fun MusicApiService.getArtistSongs(id: String, limit: Int = 100): JsonElement =
    callApi(MusicApiMethod.ARTIST_SONGS, mapOf("id" to id, "limit" to limit.toString()))

suspend fun MusicApiService.getArtistTopSong(id: String): JsonElement =
    callApi(MusicApiMethod.ARTIST_TOP_SONG, mapOf("id" to id))

suspend fun MusicApiService.getArtistAlbums(id: String, limit: Int = 50): JsonElement =
    callApi(MusicApiMethod.ARTIST_ALBUM, mapOf("id" to id, "limit" to limit.toString()))

suspend fun MusicApiService.getUserDetail(uid: String): JsonElement =
    callApi(MusicApiMethod.USER_DETAIL, mapOf("uid" to uid))

suspend fun MusicApiService.getUserPlaylists(uid: String): JsonElement =
    callApi(MusicApiMethod.USER_PLAYLIST, mapOf("uid" to uid))

suspend fun MusicApiService.getLikeList(uid: String): JsonElement =
    callApi(MusicApiMethod.USER_LIKE_LIST, mapOf("uid" to uid))

suspend fun MusicApiService.search(keywords: String, type: Int, limit: Int, offset: Int): JsonElement {
    require(limit > 0 && offset >= 0)
    return callApi(MusicApiMethod.SEARCH_CLOUD, mapOf(
        "keywords" to keywords, "type" to type.toString(),
        "limit" to limit.toString(), "offset" to offset.toString(),
    ))
}
