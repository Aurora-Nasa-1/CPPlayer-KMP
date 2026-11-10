import { providerMethods } from "./methods.generated.mjs";

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
