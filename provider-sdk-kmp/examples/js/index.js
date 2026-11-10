/* Ordinary JS business methods; the host supplies context and owns the engine. */
globalThis.provider = {
    capabilities: ["tracks.search", "tracks.get", "tracks.resolve"],
    async catalog(context) {
        const response = await context.http({
            url: context.settings.origin + "/catalog.json", method: "GET"
        });
        if (response.status !== 200)
            throw Object.assign(Error("Catalog unavailable"), { category: "Network" });
        // This is the provider's upstream format, not a host transfer format.
        return JSON.parse(context.text(response.body)).map(item => ({
            id: item.id, title: item.title, artists: [{ id: "artist-A", name: item.artist }],
            durationMs: item.durationMs
        }));
    },
    async search(request, context) {
        const all = await this.catalog(context);
        const items = all.filter(item =>
            request.keyword.toLowerCase() === "all" ||
            item.title.toLowerCase().includes(request.keyword.toLowerCase()));
        return {
            items: items.slice(request.offset, request.offset + request.limit),
            total: items.length, hasMore: request.offset + request.limit < items.length
        };
    },
    async get(request, context) {
        const item = (await this.catalog(context)).find(item => item.id === request.id);
        if (!item) throw Object.assign(Error("Track unavailable"), { category: "NotFound" });
        return item;
    },
    async resolve(request, context) {
        await this.get(request, context);
        return {
            url: context.settings.origin + "/tone.wav", actualQuality: "lossless",
            headers: [{ name: "X-Catalog-Id", value: request.id }],
            expiresAtEpochMs: 1900000000000, format: "WAV", bitrate: 128000, sizeBytes: 152044
        };
    }
};
