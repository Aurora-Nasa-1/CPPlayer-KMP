package cp.player.core.provider.typed

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.JsObject
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import cp.player.sdk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One engine/thread per account session. Only constant bridge programs execute. */
class JsSdkProvider(
    private val source: String,
    private val context: ProviderContext,
) : ExtendedJsProviderMethods() {
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "provider-sdk-js").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val engine = AtomicReference<QuickJs?>()
    private var request: Map<String, Any?> = emptyMap()
    private var initialized = false

    private fun jsValue(value: Any?): Any? = when (value) {
        is Map<*, *> -> JsObject(value.entries.associate { (key, item) -> key.toString() to jsValue(item) })
        is List<*> -> value.map(::jsValue).toTypedArray()
        else -> value
    }

    private suspend fun runtime(): QuickJs {
        engine.get()?.let { if (initialized) return it }
        require(source.length <= 1024 * 1024) { "Script size limit" }
        val js = engine.get() ?: QuickJs.create(dispatcher).also(engine::set)
        try {
            js.memoryLimit = 32L * 1024 * 1024
            js.maxStackSize = 512L * 1024
            js.evaluationTimeoutMillis = 250
            js.function<JsObject>("__sdkRequest") { jsValue(request) as JsObject }
            js.function<JsObject>("__sdkContext") {
                jsValue(mapOf("providerId" to context.providerId, "accountId" to context.accountId,
                    "settings" to context.settings, "credential" to context.credential)) as JsObject
            }
            js.asyncFunction<JsObject>("__sdkHttp") { args ->
                jsValue(context.runtime.http(HttpRequest.fromValues(valueObject(args.single()))).values()) as JsObject
            }
            js.asyncFunction<ByteArray>("__sdkSha256") { args -> context.runtime.sha256(valueBytes(args.single())) }
            js.function<String>("__sdkText") { args -> valueBytes(args.single()).decodeToString(throwOnInvalidSequence = true) }
            js.function<ByteArray>("__sdkUtf8") { args -> valueString(args.single()).encodeToByteArray() }
            js.asyncFunction<ByteArray?>("__sdkRead") { args -> context.runtime.read(valueString(args.single())) }
            js.asyncFunction<Unit>("__sdkWrite") { args ->
                context.runtime.write(valueString(args[0]), args[1]?.let(::valueBytes))
            }
            js.evaluate<Any?>("""
                Object.defineProperty(globalThis, "__sdkBound", { value: value => {
                    let nodes = 0, text = 0;
                    const ancestors = new Set();
                    function fail(message) {
                        const error = Error(message);
                        error.category = "InvalidResponse";
                        throw error;
                    }
                    function visit(v, depth) {
                        if (++nodes > 20000 || depth > 16) fail("Result structure limit");
                        if (typeof v === "string") {
                            text += v.length * 3;
                            if (text > 4194304) fail("Result string limit");
                        } else if (v !== null && typeof v === "object") {
                            if (ancestors.has(v)) fail("Cyclic result");
                            ancestors.add(v);
                            const keys = Object.keys(v);
                            if (keys.length > 500) fail("Result item limit");
                            for (const key of keys) visit(v[key], depth + 1);
                            ancestors.delete(v);
                        } else if (v !== null && v !== undefined &&
                            !["boolean", "number"].includes(typeof v)) fail("Invalid result value");
                    }
                    visit(value, 0);
                    return value;
                }, writable: false, configurable: false });
                globalThis.context = Object.freeze({
                    ...__sdkContext(),
                    http: __sdkHttp, sha256: __sdkSha256, text: __sdkText, utf8: __sdkUtf8,
                    read: __sdkRead, write: __sdkWrite
                });
                Object.freeze(context.settings);
            """.trimIndent())
            js.evaluate<Any?>(source)
            js.evaluate<Any?>("""
                if (!globalThis.provider || !Array.isArray(provider.capabilities))
                    throw Error("Expected globalThis.provider with capabilities");
            """.trimIndent())
            initialized = true
            return js
        } catch (e: Throwable) {
            js.close()
            engine.set(null)
            throw e
        }
    }

    private suspend fun <T> invoke(program: String, input: Map<String, Any?>, decode: (Map<String, Any?>) -> T): T {
        check(!closed.get())
        return withContext(dispatcher) {
            mutex.withLock {
                check(!closed.get())
                request = input
                try {
                    val result = runtime().evaluate<JsObject>(program)
                    val values = valueObject(result)
                    values["__sdkFailure"]?.let {
                        val failure = Failure.fromValues(valueObject(it))
                        require(failure.category.isNotBlank() && failure.category.length <= 128 && failure.message.length <= 4096)
                        throw ProviderException(failure)
                    }
                    decode(values)
                } catch (e: CancellationException) { throw e }
                catch (e: QuickJsInterruptedException) {
                    throw ProviderException(Failure(FailureCategory.TIMEOUT, "JS execution budget exceeded"))
                } finally { request = emptyMap() }
            }
        }
    }

    // Method names come only from these constants; neither code nor data is interpolated.
    override suspend fun capabilities(): Capabilities =
        invoke("__sdkBound({ supported: provider.capabilities });", emptyMap(), Capabilities::fromValues)
    override suspend fun search(request: SearchRequest): TrackPage =
        business("search", request.values(), TrackPage::fromValues)
    override suspend fun get(request: IdRequest): Track =
        business("get", request.values(), Track::fromValues)
    override suspend fun getMany(request: BatchRequest): TrackBatch =
        if (Capability.BATCH in capabilities().supported) business("getMany", request.values(), TrackBatch::fromValues)
        else super.getMany(request)
    override suspend fun resolve(request: ResolveRequest): MediaResource =
        business("resolve", request.values(), MediaResource::fromValues)
    override suspend fun getLyrics(request: IdRequest): Lyrics =
        business("getLyrics", request.values(), Lyrics::fromValues)
    override suspend fun searchPlaylists(request: SearchRequest): PlaylistPage =
        business("searchPlaylists", request.values(), PlaylistPage::fromValues)
    override suspend fun getPlaylist(request: IdRequest): Playlist =
        business("getPlaylist", request.values(), Playlist::fromValues)
    override suspend fun getPlaylistTracks(request: PagedIdRequest): TrackPage =
        business("getPlaylistTracks", request.values(), TrackPage::fromValues)
    override suspend fun getUserPlaylists(request: PagedIdRequest): PlaylistPage =
        business("getUserPlaylists", request.values(), PlaylistPage::fromValues)
    override suspend fun searchAlbums(request: SearchRequest): AlbumPage =
        business("searchAlbums", request.values(), AlbumPage::fromValues)
    override suspend fun getAlbum(request: IdRequest): AlbumDetails =
        business("getAlbum", request.values(), AlbumDetails::fromValues)
    override suspend fun searchArtists(request: SearchRequest): ArtistPage =
        business("searchArtists", request.values(), ArtistPage::fromValues)
    override suspend fun getArtist(request: IdRequest): CatalogArtist =
        business("getArtist", request.values(), CatalogArtist::fromValues)
    override suspend fun getArtistTracks(request: PagedIdRequest): TrackPage =
        business("getArtistTracks", request.values(), TrackPage::fromValues)
    override suspend fun getArtistAlbums(request: PagedIdRequest): AlbumPage =
        business("getArtistAlbums", request.values(), AlbumPage::fromValues)
    override suspend fun getRecommendedSongs(request: RecommendationsRequest): TrackPage =
        business("getRecommendedSongs", request.values(), TrackPage::fromValues)
    override suspend fun getRecommendedPlaylists(request: RecommendationsRequest): PlaylistPage =
        business("getRecommendedPlaylists", request.values(), PlaylistPage::fromValues)
    override suspend fun getPersonalizedPlaylists(request: RecommendationsRequest): PlaylistPage =
        business("getPersonalizedPlaylists", request.values(), PlaylistPage::fromValues)
    override suspend fun getTopPlaylists(request: PlaylistBrowseRequest): PlaylistPage =
        business("getTopPlaylists", request.values(), PlaylistPage::fromValues)
    override suspend fun getHighqualityPlaylists(request: PlaylistBrowseRequest): PlaylistPage =
        business("getHighqualityPlaylists", request.values(), PlaylistPage::fromValues)
    override suspend fun getPersonalizedNewSongs(request: RecommendationsRequest): TrackPage =
        business("getPersonalizedNewSongs", request.values(), TrackPage::fromValues)
    override suspend fun getBanners(request: Bytes): BannerPage =
        business("getBanners", request.values(), BannerPage::fromValues)
    override suspend fun getRankings(request: Bytes): RankingPage =
        business("getRankings", request.values(), RankingPage::fromValues)
    override suspend fun getNewAlbums(request: AlbumBrowseRequest): AlbumPage =
        business("getNewAlbums", request.values(), AlbumPage::fromValues)
    override suspend fun getTopSongs(request: TopSongsRequest): TrackPage =
        business("getTopSongs", request.values(), TrackPage::fromValues)
    override suspend fun getHotArtists(request: RecommendationsRequest): ArtistPage =
        business("getHotArtists", request.values(), ArtistPage::fromValues)
    override suspend fun getUserProfile(request: UserRequest): UserProfile =
        business("getUserProfile", request.values(), UserProfile::fromValues)
    override suspend fun getUserRecords(request: UserRecordsRequest): TrackPage =
        business("getUserRecords", request.values(), TrackPage::fromValues)
    override suspend fun getUserFollows(request: UserListRequest): ArtistPage =
        business("getUserFollows", request.values(), ArtistPage::fromValues)
    override suspend fun getUserFolloweds(request: UserListRequest): ArtistPage =
        business("getUserFolloweds", request.values(), ArtistPage::fromValues)
    override suspend fun getUserCloud(request: UserListRequest): TrackPage =
        business("getUserCloud", request.values(), TrackPage::fromValues)
    override suspend fun getLikeList(request: UserRequest): IdList =
        business("getLikeList", request.values(), IdList::fromValues)
    override suspend fun getUserAlbums(request: UserListRequest): AlbumPage =
        business("getUserAlbums", request.values(), AlbumPage::fromValues)
    override suspend fun deletePlaylist(request: IdRequest): MutationResult =
        business("deletePlaylist", request.values(), MutationResult::fromValues)
    override suspend fun subscribePlaylist(request: PlaylistSubscribeRequest): MutationResult =
        business("subscribePlaylist", request.values(), MutationResult::fromValues)
    override suspend fun addTracksToPlaylist(request: PlaylistTracksRequest): MutationResult =
        business("addTracksToPlaylist", request.values(), MutationResult::fromValues)
    override suspend fun removeTracksFromPlaylist(request: PlaylistTracksRequest): MutationResult =
        business("removeTracksFromPlaylist", request.values(), MutationResult::fromValues)
    override suspend fun renamePlaylist(request: PlaylistNameRequest): MutationResult =
        business("renamePlaylist", request.values(), MutationResult::fromValues)
    override suspend fun createPlaylist(request: CreatePlaylistRequest): MutationResult =
        business("createPlaylist", request.values(), MutationResult::fromValues)
    override suspend fun likeSong(request: SongLikeRequest): MutationResult =
        business("likeSong", request.values(), MutationResult::fromValues)
    override suspend fun dislikeSong(request: IdRequest): MutationResult =
        business("dislikeSong", request.values(), MutationResult::fromValues)
    override suspend fun deleteUserCloud(request: BatchRequest): MutationResult =
        business("deleteUserCloud", request.values(), MutationResult::fromValues)
    override suspend fun getComments(request: CommentPageRequest): CommentPage =
        business("getComments", request.values(), CommentPage::fromValues)
    override suspend fun getFloorComments(request: CommentFloorRequest): CommentFloorPage =
        business("getFloorComments", request.values(), CommentFloorPage::fromValues)
    override suspend fun postComment(request: PostCommentRequest): CreatedComment =
        business("postComment", request.values(), CreatedComment::fromValues)
    override suspend fun likeComment(request: CommentLikeRequest): MutationResult =
        business("likeComment", request.values(), MutationResult::fromValues)
    override suspend fun getPersonalFm(request: Bytes): TrackPage =
        business("getPersonalFm", request.values(), TrackPage::fromValues)
    override suspend fun getSimilarSongs(request: SimilarSongsRequest): TrackPage =
        business("getSimilarSongs", request.values(), TrackPage::fromValues)
    override suspend fun getIntelligenceSongs(request: IntelligenceSongsRequest): TrackPage =
        business("getIntelligenceSongs", request.values(), TrackPage::fromValues)
    override suspend fun subscribeAlbum(request: AlbumSubscribeRequest): MutationResult =
        business("subscribeAlbum", request.values(), MutationResult::fromValues)
    override suspend fun getHotSearches(request: Bytes): KeywordPage =
        business("getHotSearches", request.values(), KeywordPage::fromValues)
    override suspend fun getSearchSuggestions(request: SearchSuggestionRequest): SuggestionPage =
        business("getSearchSuggestions", request.values(), SuggestionPage::fromValues)
    override suspend fun scrobble(request: ScrobbleRequest): MutationResult =
        business("scrobble", request.values(), MutationResult::fromValues)
    override suspend fun getCurrentAccount(request: Bytes): AccountSession =
        business("getCurrentAccount", request.values(), AccountSession::fromValues)
    override suspend fun authenticate(request: AuthActionRequest): AuthActionResult =
        business("authenticate", request.values(), AuthActionResult::fromValues)

    private suspend fun <T> business(method: String, input: Map<String, Any?>, decode: (Map<String, Any?>) -> T): T =
        invoke(BUSINESS, mapOf("method" to method, "input" to input), decode)

    override suspend fun <T> extendedBusiness(
        method: String, input: Map<String, Any?>, decode: (Map<String, Any?>) -> T,
    ): T = business(method, input, decode)

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        engine.get()?.interruptEvaluation()
        try {
            withContext(dispatcher + NonCancellable) {
                mutex.withLock { engine.getAndSet(null)?.close() }
            }
        } finally { dispatcher.close() }
    }

    companion object {
        private const val BUSINESS = """
            try {
                const request = __sdkRequest();
                __sdkBound(await provider[request.method](request.input, context));
            }
            catch (e) { if (e instanceof InternalError) throw e; ({ __sdkFailure: { category: e.category || "Runtime", message: String(e.message || e) } }); }
        """
    }
}
