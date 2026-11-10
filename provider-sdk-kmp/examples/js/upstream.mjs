import http from "node:http";

const origin = "http://127.0.0.1:48482";
const tracks = ["001", "UUID-A", "A", "a"].map(id =>
    ({ id, title: `Catalog ${id}`, artist: "Test artist", durationMs: 9500 }));
const wav = Buffer.alloc(152044);
wav.write("RIFF"); wav.writeUInt32LE(152036, 4); wav.write("WAVEfmt ", 8);
wav.writeUInt32LE(16, 16); wav.writeUInt16LE(1, 20); wav.writeUInt16LE(1, 22);
wav.writeUInt32LE(8000, 24); wav.writeUInt32LE(16000, 28);
wav.writeUInt16LE(2, 32); wav.writeUInt16LE(16, 34);
wav.write("data", 36); wav.writeUInt32LE(152000, 40);
for (let i = 0; i < 76000; i++) wav.writeInt16LE(Math.round(Math.sin(i * 2 * Math.PI * 440 / 8000) * 8192), 44 + i * 2);

export function createCatalogServer() {
    return http.createServer((request, response) => {
        const path = new URL(request.url, origin).pathname;
        if (request.method !== "GET") { response.writeHead(405).end(); return; }
        if (path === "/catalog.json") {
            response.writeHead(200, { "content-type": "application/json; charset=utf-8" });
            response.end(JSON.stringify(tracks));
        } else if (path === "/tone.wav") {
            response.writeHead(200, { "content-type": "audio/wav", "content-length": wav.length });
            response.end(wav);
        } else response.writeHead(404).end();
    });
}
if (process.argv[1] && import.meta.url === (await import("node:url")).pathToFileURL(process.argv[1]).href) {
    createCatalogServer().listen(48482, "127.0.0.1", () => console.log(`Local catalog: ${origin}`));
}
