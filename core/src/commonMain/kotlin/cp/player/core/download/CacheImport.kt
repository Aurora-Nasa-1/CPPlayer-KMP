package cp.player.core.download

import cp.player.core.music.CPMediaId

/**
 * 「歌曲缓存 → 正式下载」的结果统计。
 *
 * 三个数分开而不是只回一个成功数：一次「一键转下载」里同时会发生
 * 「转成功」「本来就下载过 / 缺音源信息，跳过」「拷贝失败」，
 * 合成一个数字后用户无从判断「剩下那些为什么没转」。
 *
 * @param converted 真的搬成下载的条数
 * @param skipped 有意跳过的条数（已是下载 / 正在下载 / 旧缓存缺 mediaId 或音质）
 * @param failed 尝试过但失败的条数（文件已被清理、磁盘写失败 …）
 */
data class CacheImportSummary(
    val converted: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
) {
    val total: Int get() = converted + skipped + failed
}

/**
 * 两个 mediaId 是否指向**同一个音源下的同一个资源**。
 *
 * ### 为什么不能只比字符串
 * 缓存键里的 mediaId 来自播放队列，下载入队的 mediaId 来自界面点选 + 当前音源补全，
 * 两者在历史上出现过写法差异（大小写、多余空白）。直接 `==` 会漏命中，
 * 于是「明明缓存里就有」的歌还是重下一遍。
 *
 * ### 为什么必须比较 providerId（本功能的硬约束）
 * 不同音源会有**同一个数字 id 的同名歌曲**（歌名歌手一模一样，音频却不是同一份）。
 * 只按「歌名 + 音质」或「裸 id」匹配，会把 A 音源的缓存当成 B 音源的下载交付给用户 ——
 * 文件名对、听着也是那首歌，但码率/母带/时长可能完全不同，且**没有任何报错**。
 * 所以这里先按 `{providerId}://{type}/{id}` 三段比，provider 不同一律不匹配；
 * 解析失败的串只承认**完全相等**。
 */
internal fun isSameMediaResource(a: String?, b: String?): Boolean {
    if (a.isNullOrBlank() || b.isNullOrBlank()) return false
    val left = a.trim()
    val right = b.trim()
    if (left == right) return true
    val pa = runCatching { CPMediaId.parse(left) }.getOrNull() ?: return false
    val pb = runCatching { CPMediaId.parse(right) }.getOrNull() ?: return false
    return pa.providerId.equals(pb.providerId, ignoreCase = true) &&
        pa.resourceType.equals(pb.resourceType, ignoreCase = true) &&
        pa.resourceId == pb.resourceId
}

/** 从 mediaId 里取 providerId；解析不出来时给中性占位（与 enqueue 的既有行为一致）。 */
internal fun providerIdOf(mediaId: String): String =
    runCatching { CPMediaId.parse(mediaId).providerId }.getOrDefault("unknown")
