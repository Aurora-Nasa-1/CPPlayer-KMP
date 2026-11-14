/*
 * Minimal kmp-sdk-v1 provider template.
 *
 * Copy this file next to a manifest.json, keep the capabilities you actually
 * serve, and delete the rest. Undeclared or unimplemented methods are never
 * dispatched, so a three-method provider is complete.
 */
globalThis.provider = {
    // The runtime switch. manifest.capabilities is display metadata only.
    capabilities: ["tracks.search", "tracks.get", "tracks.resolve"],

    async search(request, context) {
        const page = await fetchPage(context, request.keyword, request.limit, request.offset);
        return { items: page.items, total: page.total, hasMore: page.hasMore };
    },

    async get(request, context) {
        const track = await fetchTrack(context, request.id);
        if (!track) throw failure("NotFound", "Track not found");
        return track;
    },

    async resolve(request, context) {
        const track = await fetchTrack(context, request.id);
        if (!track) throw failure("NotFound", "Track not found");
        const stream = await fetchStream(context, request.id, request.quality);
        return {
            url: stream.url,
            // Report what the upstream actually served, never the requested tier.
            actualQuality: stream.quality,
            headers: [{ name: "X-Stream-Token", value: stream.token }],
            expiresAtEpochMs: stream.expiresAt,
            format: stream.format,
            bitrate: stream.bitrate,
            sizeBytes: stream.size
        };
    }
};

function failure(category, message) {
    return Object.assign(Error(message), { category });
}

async function getJson(context, path) {
    const response = await context.http({
        url: context.settings.origin + path,
        method: "GET",
        headers: context.credential
            ? [{ name: "Authorization", value: "Bearer " + context.credential }]
            : []
    });
    if (response.status !== 200) throw failure("Network", "Upstream status " + response.status);
    return JSON.parse(context.text(response.body));
}

// Replace this function with the provider's real upstream shape.
async function fetchPage(context, keyword, limit, offset) {
    const body = await getJson(context, "/search?q=" + encodeURIComponent(keyword) +
        "&limit=" + limit + "&offset=" + offset);
    return {
        items: body.items.map(toTrack),
        total: typeof body.total === "number" ? body.total : null,
        hasMore: typeof body.hasMore === "boolean" ? body.hasMore : null
    };
}

async function fetchTrack(context, id) {
    const body = await getJson(context, "/track?id=" + encodeURIComponent(id));
    return body && body.id ? toTrack(body) : null;
}

async function fetchStream(context, id, quality) {
    return getJson(context, "/stream?id=" + encodeURIComponent(id) + "&quality=" + encodeURIComponent(quality));
}

// IDs stay opaque strings: no Number(), no split(","), no trimming.
function toTrack(item) {
    return {
        id: String(item.id),
        title: item.title,
        artists: (item.artists || []).map(artist => ({
            id: artist.id == null ? null : String(artist.id),
            name: artist.name
        })),
        // Milliseconds from the upstream. Never guess seconds from magnitude.
        durationMs: item.durationMs,
        album: item.album ?? null,
        coverUrl: item.coverUrl ?? null
    };
}
