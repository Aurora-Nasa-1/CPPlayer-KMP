export * from "./types.generated.js";
import type { Binary, Provider } from "./types.generated.js";
export declare function defineProvider<T extends Provider>(provider: T): T;
/** Encode binary data as Base64URL without padding. */
export declare function encodeBase64Url(bytes: Binary): string;
/** Decode canonical unpadded Base64URL. Throws on invalid input. */
export declare function decodeBase64Url(text: string): Uint8Array;
