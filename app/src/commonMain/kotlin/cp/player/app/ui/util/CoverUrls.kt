package cp.player.app.ui.util

import cp.player.core.provider.CoverUrlResolver

/**
 * 将请求像素边长收敛到少量规范档位，避免 120/150/160/180/220/240/300 等碎尺寸
 * 为同一张图制造大量重复的磁盘副本与内存位图。
 */
fun quantizeCoverBucketPx(sizePx: Int): Int =
    CoverUrlResolver.quantizeBucketPx(sizePx)

/**
 * 剥离 CDN 缩图参数与临时签名参数（`?param=...` / `sign=` / `expires=` 等），得到用于识别「同一张原图」的规范键。
 */
fun canonicalCoverBaseUrl(url: String): String =
    CoverUrlResolver.canonicalCoverKey(url)

/**
 * 为封面 URL 选择合适的 CDN 多档缩略图尺寸。
 *
 * - null / 空白 / 本地路径（`file://` / `content://`）原样返回；
 * - 优先使用活跃音源可选声明的 `imageResize` 规则（或内置网易云安全规则）；
 * - 结合宿主运行态（托盘后台 / 省流模式）自动收紧目标档位，非适配图床保持原样并交由宿主本地裁剪降采样。
 */
fun String?.resized(size: Int): String? =
    CoverUrlResolver.resizeCoverUrl(this, size)
