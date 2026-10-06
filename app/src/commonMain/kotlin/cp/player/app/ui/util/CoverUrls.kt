package cp.player.app.ui.util

/**
 * 为封面 URL 追加网易云图片 CDN 缩图参数。
 *
 * 追加后由网易云 CDN 按需裁剪为指定尺寸的缩略图，减少列表页流量消耗。
 *
 * - null / 空白字符串原样返回；
 * - URL 已包含 `param=` 时原样返回（避免重复追加）；
 * - URL 已含 `?` 时用 `&` 拼接，否则用 `?` 拼接，返回 `${sep}param=${size}y${size}`。
 *
 * @param size 目标边长（如 140 表示请求 140x140 缩略图）
 */
fun String?.resized(size: Int): String? {
    if (this.isNullOrBlank()) return this
    // ⚠️ **只对 http(s) 生效**。本地封面是 `file://` / `content://`（安卓 SAF），
    // 在后面追加 `?param=WyH` 会把路径本身弄坏 ⇒ 图片加载失败 ⇒ 界面上是一块灰色占位。
    // 这个坑原先只影响"某几个页面"，专辑墙把它放大了：墙上同时有云端与本地两类封面，
    // 本地那一批会整片发灰。
    if (!startsWith("http", ignoreCase = true)) return this
    if (this.contains("param=")) return this
    val sep = if (this.contains("?")) "&" else "?"
    return this + "${sep}param=${size}y${size}"
}
