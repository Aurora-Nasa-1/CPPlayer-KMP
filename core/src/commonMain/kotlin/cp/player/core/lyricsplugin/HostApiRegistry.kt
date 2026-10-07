/*
 * Based on the Lyrico Plugin API host contract (https://github.com/Replica0110/Lyrico) — Apache-2.0.
 * Host contract reference: app/src/main/java/com/ella/music/plugin/runtime/HostApiRegistry.kt
 * Changes: package renamed to cp.player.core.lyricsplugin; the supported-API set was trimmed to
 *          the ops this host actually implements (see THIRD_PARTY_LICENSES.md).
 */
package cp.player.core.lyricsplugin

/**
 * 宿主 / 插件 API 契约版本（与 Lyrico 插件格式对齐）。
 *
 * 插件在 `manifest.json` 里声明自己需要 `apiVersion` / `minHostApiVersion`；
 * 宿主只接受落在支持区间内的插件，避免运行一个依赖了未实现能力的插件。
 */
object HostApiRegistry {
    const val MIN_PLUGIN_API_VERSION = 1
    const val PLUGIN_API_VERSION = 5
    const val MIN_HOST_API_VERSION = 1
    const val HOST_API_VERSION = 4

    fun supportsPluginApiVersion(apiVersion: Int): Boolean =
        apiVersion in MIN_PLUGIN_API_VERSION..PLUGIN_API_VERSION

    fun supportsHostApiVersion(minHostApiVersion: Int): Boolean =
        minHostApiVersion in MIN_HOST_API_VERSION..HOST_API_VERSION

    /**
     * 本宿主**已实现**的 `Platform.*` 调用名。
     *
     * 与 Lyrico 的完整清单相比，这里暂未实现 XML 解析与 AES 系列 —— 歌词源插件极少用到，
     * 且实现它们需要额外依赖。插件若调用未实现的 API，宿主返回 null 并在日志里告警，
     * 而不是让整个插件崩掉。
     */
    val SUPPORTED_HOST_APIS = setOf(
        "i18n.getLocale",
        "i18n.t",
        "app.info",
        "app.userAgent",
        "runtime.info",
        "cache.get",
        "cache.set",
        "cache.remove",
        "cache.clear",
        "crypto.md5",
        "crypto.sha256",
        "base64.encodeText",
        "base64.decodeText",
        "base64.decodeBytes",
        "base64.encodeBytes",
        "base64.encodeUrlText",
        "base64.decodeUrlText",
        "base64.encodeUrlBytes",
        "base64.decodeUrlBytes",
        "bytes.xor",
        "bytes.xorBase64",
        "compression.inflateBytesToText",
        "compression.inflateBase64ToText",
        "http.getText",
        "http.postText",
        "http.get",
        "http.post",
        "http.getBytes",
        "log.debug",
        "log.warn",
        "log.error",
    )
}
