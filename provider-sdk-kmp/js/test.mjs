import { test, describe } from "node:test";
import assert from "node:assert/strict";
import { defineProvider } from "./index.mjs";
import schema from "../../provider-contract/schema.mjs";
import { providerMethods } from "./methods.generated.mjs";

describe("KMP SDK author helper", () => {
test("business provider remains the supplied object", () => {
    const provider = { capabilities: ["tracks.get"], async get(request) { return { id: request.id }; } };
    assert.equal(defineProvider(provider), provider);
});
test("capability without its method is rejected", () => {
    assert.throws(() => defineProvider({ capabilities: ["tracks.resolve"] }), TypeError);
});
test("recommendation capabilities require their typed methods", () => {
    assert.throws(() => defineProvider({ capabilities: ["recommendations.songs"] }), TypeError);
    assert.throws(() => defineProvider({ capabilities: ["recommendations.playlists"] }), TypeError);
    assert.throws(() => defineProvider({ capabilities: ["recommendations.personalizedPlaylists"] }), TypeError);
    assert.throws(() => defineProvider({ capabilities: ["recommendations.topPlaylists"] }), TypeError);
    assert.throws(() => defineProvider({ capabilities: ["recommendations.highqualityPlaylists"] }), TypeError);
    assert.doesNotThrow(() => defineProvider({
        capabilities: [
            "recommendations.songs", "recommendations.playlists", "recommendations.personalizedPlaylists",
            "recommendations.topPlaylists", "recommendations.highqualityPlaylists"
        ],
        async getRecommendedSongs() { return { items: [] }; },
        async getRecommendedPlaylists() { return { items: [] }; },
        async getPersonalizedPlaylists() { return { items: [] }; },
        async getTopPlaylists() { return { items: [] }; },
        async getHighqualityPlaylists() { return { items: [] }; }
    }));
});
test("discovery capabilities require their typed methods", () => {
    const pairs = [
        ["recommendations.newSongs", "getPersonalizedNewSongs"],
        ["discovery.banners", "getBanners"],
        ["discovery.rankings", "getRankings"],
        ["discovery.newAlbums", "getNewAlbums"],
        ["discovery.topSongs", "getTopSongs"],
        ["discovery.hotArtists", "getHotArtists"]
    ];
    for (const [capability] of pairs)
        assert.throws(() => defineProvider({ capabilities: [capability] }), TypeError);
    const provider = { capabilities: pairs.map(([capability]) => capability) };
    for (const [, method] of pairs) provider[method] = async () => ({ items: [] });
    assert.doesNotThrow(() => defineProvider(provider));
});
test("unknown capabilities are forward compatible", () => {
    assert.deepEqual(defineProvider({ capabilities: ["future.discovery"] }).capabilities, ["future.discovery"]);
});
test("every generated capability requires exactly its declared typed method", () => {
    const operations = Object.entries(schema.operations).filter(([, op]) => op.capability);
    assert.equal(operations.length, 160);
    assert.equal(operations.filter(([, op]) => op.extended).length, 105);
    for (const [name, operation] of operations) {
        assert.equal(providerMethods[operation.capability], name);
        assert.throws(() => defineProvider({ capabilities: [operation.capability] }), TypeError, name);
        const provider = { capabilities: [operation.capability], [name]: async () => ({}) };
        assert.equal(defineProvider(provider), provider, name);
    }
    assert.equal(new Set(Object.values(schema.operations).map(op => op.tag)).size, 165);
});
test("undefined capabilities do not silently enable everything", () => {
    assert.throws(() => defineProvider({}), TypeError);
});
});
