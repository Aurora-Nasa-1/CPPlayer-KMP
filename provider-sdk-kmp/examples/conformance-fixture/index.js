/*
 * Offline fixture for `:core:providerConformance`.
 *
 * It answers every check with deterministic data and never touches the
 * network, so it can be used as the smallest known-good provider and as a
 * starting point for a real one.
 */
globalThis.provider = {
    capabilities: ["tracks.search", "tracks.get", "tracks.resolve"],

    search() {
        return {
            items: [{
                id: "001",
                title: "Fixture Track",
                artists: [{ id: "artist-1", name: "Fixture Artist" }],
                durationMs: 9500,
                album: null,
                coverUrl: null
            }],
            total: 1,
            hasMore: false
        };
    },

    get(request) {
        return {
            id: request.id,
            title: "Fixture Track",
            artists: [{ id: "artist-1", name: "Fixture Artist" }],
            durationMs: 9500,
            album: null,
            coverUrl: null
        };
    },

    resolve() {
        return {
            url: "https://example.test/fixture.mp3",
            actualQuality: "standard",
            headers: [],
            expiresAtEpochMs: null,
            format: "mp3",
            bitrate: 128000,
            sizeBytes: null
        };
    }
};
