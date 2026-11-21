import { providerMethods } from "./methods.generated.mjs";

const base64UrlAlphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

/** @param {import("./types.generated.js").Binary} bytes */
export function encodeBase64Url(bytes) {
    let bits = 0;
    let pending = 0;
    let encoded = "";
    for (const byte of bytes) {
        pending = (pending << 8) | (byte & 255);
        bits += 8;
        while (bits >= 6) {
            bits -= 6;
            encoded += base64UrlAlphabet[(pending >> bits) & 63];
        }
    }
    if (bits) encoded += base64UrlAlphabet[(pending << (6 - bits)) & 63];
    return encoded;
}

/** @param {string} text */
export function decodeBase64Url(text) {
    if (typeof text !== "string" || text.length % 4 === 1 || /[^A-Za-z0-9_-]/u.test(text))
        throw new TypeError("Expected unpadded Base64URL");
    const decoded = new Uint8Array(Math.floor(text.length * 6 / 8));
    let bits = 0;
    let pending = 0;
    let offset = 0;
    for (const char of text) {
        pending = (pending << 6) | base64UrlAlphabet.indexOf(char);
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            decoded[offset++] = (pending >> bits) & 255;
        }
    }
    if ((pending & ((1 << bits) - 1)) !== 0) throw new TypeError("Invalid Base64URL trailing bits");
    return decoded;
}

/**
 * @template {import("./types.generated.js").Provider} T
 * @param {T} provider
 * @returns {T}
 */
export function defineProvider(provider) {
    if (!Array.isArray(provider.capabilities)) throw new TypeError("Expected capabilities");
    for (const capability of provider.capabilities) {
        const method = Object.hasOwn(providerMethods, capability) ? providerMethods[capability] : undefined;
        if (method && typeof provider[method] !== "function")
            throw new TypeError(`Capability requires ${method}`);
    }
    return provider;
}
