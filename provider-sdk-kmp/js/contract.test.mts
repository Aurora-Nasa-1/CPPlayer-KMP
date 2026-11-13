import { defineProvider, type AuthActionRequest, type AuthActionResult, type Provider, type Track, type Context, type KnownCapability } from "./index.mjs";

const commentCapability: KnownCapability = "comments.page";
// @ts-expect-error KnownCapability detects typos while Provider still permits future capability strings.
const typoCapability: KnownCapability = "comments.pages";
const futureProvider: Provider = { capabilities: ["future.business"] };
void commentCapability; void typoCapability; void futureProvider;

const authRequest: AuthActionRequest = {
    action: "login.phone",
    phone: "447361501642",
    md5: false,
    countryCode: "44",
};
const authRequestWithProviderDefault: AuthActionRequest = {
    action: "login.phone",
    md5: false,
};
const omittedCountryCode: string | null | undefined = authRequestWithProviderDefault.countryCode;
const authChallenge: AuthActionResult = {
    success: false,
    challenge: true,
    verifyUrl: "https://verify.example.test/session",
};
const authProvider: Provider = defineProvider({
    capabilities: ["auth.actions"],
    async authenticate(request) {
        const countryCode: string | null | undefined = request.countryCode;
        void countryCode;
        return authChallenge;
    },
});
void authRequest; void omittedCountryCode; void authProvider;

const provider: Provider = defineProvider({
    capabilities: [
        "tracks.get", "tracks.search", "tracks.resolve",
        "recommendations.songs", "recommendations.playlists",
        "recommendations.newSongs", "discovery.banners", "discovery.rankings",
        "discovery.newAlbums", "discovery.topSongs", "discovery.hotArtists"
    ],
    async get(request) { return { id: request.id, title: "Title", durationMs: 9500 }; },
    async search(request, context: Context) {
        await context.http({ url: context.settings.origin, method: "GET" });
        return { items: [], hasMore: false };
    },
    async resolve(request) { return { url: "https://example.com/audio", actualQuality: request.quality }; },
    async getRecommendedSongs(request) {
        return { items: [], hasMore: request.limit > 0 };
    },
    async getRecommendedPlaylists(request) {
        return { items: [], hasMore: request.limit > 0 };
    },
    async getPersonalizedNewSongs(request) { return { items: [], hasMore: request.limit > 0 }; },
    async getBanners() { return { items: [] }; },
    async getRankings() { return { items: [] }; },
    async getNewAlbums(request) { return { items: [], hasMore: request.limit > 0 }; },
    async getTopSongs(request) { return { items: [], hasMore: request.limit > 0 }; },
    async getHotArtists(request) { return { items: [], hasMore: request.limit > 0 }; }
});
void provider;
const track: Track = {
    // @ts-expect-error Opaque resource IDs cannot be numeric.
    id: 123,
    title: "Invalid ID",
    durationMs: 1
};
void track;

const social: Provider = defineProvider({
    capabilities: ["messages.history", "messages.send", "listenTogether.snapshot", "videos.resolve", "playlists.update"],
    async getMessageHistory(request) {
        const cursor: string | null | undefined = request.cursor;
        return { items: [], cursor, hasMore: request.limit > 0 };
    },
    async sendMessage(request) {
        const users: readonly string[] = request.userIds ?? [];
        return { success: users.length > 0 && request.text.length > 0 };
    },
    async getListenTogetherSnapshot(request) {
        const room: string = request.roomId;
        return { command: null, playlist: { trackIds: [room], replace: true, versions: [] } };
    },
    async resolveVideo(request) {
        return { url: "https://example.test/video", actualQuality: String(request.resolution) };
    },
    async updatePlaylist(request) {
        const tags: string[] | null | undefined = request.tags;
        return { success: tags !== undefined };
    },
});
void social;

const invalidSocial: Provider = {
    capabilities: ["messages.history"],
    // @ts-expect-error Raw JSON/string envelopes are not typed message pages.
    async getMessageHistory() { return '{"msgs":[]}'; },
};
void invalidSocial;

const migration: Provider = defineProvider({
    capabilities: ["videos.search", "comments.page", "messages.notices", "library.cloud.upload.chunk"],
    async searchVideos(request) {
        return { items: [{ id: request.keyword, title: "Video", durationMs: 1200 }], total: 1 };
    },
    async getCommentPage(request) {
        const cursor: string | null | undefined = request.cursor;
        return { comments: [], totalCount: 0, hasMore: false, cursor: cursor ?? "", sortType: request.sortType };
    },
    async getSystemNotifications(request) {
        return { items: [], cursor: request.cursor };
    },
    async uploadCloudChunk(request) {
        const content: Int8Array | Uint8Array = request.content;
        return { uploadId: request.uploadId, offset: request.offset + content.byteLength, complete: request.last };
    },
});
void migration;

const invalidBinary: Provider = {
    capabilities: ["accounts.avatar.upload"],
    async uploadAvatar(request) {
        // @ts-expect-error File paths and base64 text are not upload bytes.
        const content: string = request.content;
        void content;
        return { imageId: "avatar" };
    },
};
void invalidBinary;
