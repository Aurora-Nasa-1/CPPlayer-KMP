package cp.player.kmp.local

import cp.player.kmp.media.LocalMediaItem
import cp.player.kmp.media.LocalMediaOrigin
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 索引中每个 path 的轻量元信息（增量扫描比对用）。
 */
@Serializable
data class LocalMediaPathMeta(
    val size: Long,
    val lastModified: Long,
)

/**
 * 本地媒体索引持久化结构。
 *
 * @param items 全部条目（[LocalMediaItem] 列表）
 * @param meta path → {size, lastModified} 元信息（增量比对依据）
 */
@Serializable
data class LocalMediaIndexData(
    val items: List<LocalMediaItem> = emptyList(),
    val meta: Map<String, LocalMediaPathMeta> = emptyMap(),
)

/**
 * 增量 reconcile 结果。
 *
 * @param added 新增条目
 * @param updated 因 size / lastModified 变化而重建的条目
 * @param removedPaths 消失文件的 path
 * @param all 合并后的完整条目列表
 */
data class ReconcileResult(
    val added: List<LocalMediaItem>,
    val updated: List<LocalMediaItem>,
    val removedPaths: List<String>,
    val all: List<LocalMediaItem>,
)

/**
 * 本地媒体索引（commonMain，JSON 持久化到平台数据目录）。
 *
 * 文件存放位置参照 `PlatformSupport.modulesDir` 的同级目录风格，
 * 由各平台实现决定（Android: `filesDir/local-media/`；Desktop: `~/.kmp-pro/local-media/`）。
 *
 * ### 增量扫描语义（[reconcile]）
 * - 仅对新增 / 变更（size 或 lastModified 变化）文件重建条目
 * - 移除消失条目
 * - SAF `content://` 树 URI 条目与 [LocalMediaOrigin.DOWNLOADED] 条目原样保留，
 *   重扫时不做文件 stat
 */
class LocalMediaIndex(private val indexFile: String) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 当前索引数据（load 后可用）。 */
    var data: LocalMediaIndexData = LocalMediaIndexData()
        private set

    /** 当前条目快照。 */
    val items: List<LocalMediaItem> get() = data.items

    /** 从磁盘加载索引；文件缺失或损坏时重置为空索引。 */
    fun load(): LocalMediaIndexData {
        val text = localMediaReadText(indexFile)
        data = if (text == null) {
            LocalMediaIndexData()
        } else {
            runCatching { json.decodeFromString<LocalMediaIndexData>(text) }
                .getOrElse { LocalMediaIndexData() }
        }
        return data
    }

    /** 持久化当前索引到磁盘。 */
    fun save(): Boolean = localMediaWriteText(indexFile, json.encodeToString(data))

    /** 直接以给定条目列表整体替换索引（meta 由条目字段重建）。 */
    fun updateItems(newItems: List<LocalMediaItem>) {
        data = LocalMediaIndexData(
            items = newItems,
            meta = newItems.associate { it.path to LocalMediaPathMeta(it.sizeBytes, it.lastModified) },
        )
    }

    /**
     * 增量合并一次扫描发现的文件。
     *
     * @param discovered 本次扫描发现的条目（需携带 sizeBytes / lastModified）
     * @param scanRoots 本次扫描的根目录列表；非 null 时，只有位于根目录之下的
     *   普通文件条目才可能被判为「消失」而移除；null 时（如 MediaStore 全量扫描）
     *   除 content:// 与 DOWNLOADED 外的未再现条目均视为消失
     */
    fun reconcile(discovered: List<LocalMediaItem>, scanRoots: List<String>? = null): ReconcileResult {
        val existing = data.items.associateBy { it.path }
        val discoveredPaths = discovered.mapTo(mutableSetOf()) { it.path }

        val added = mutableListOf<LocalMediaItem>()
        val updated = mutableListOf<LocalMediaItem>()
        val kept = mutableListOf<LocalMediaItem>()

        for (item in discovered) {
            val old = existing[item.path]
            val meta = data.meta[item.path]
            when {
                old == null -> added += item
                meta == null || meta.size != item.sizeBytes || meta.lastModified != item.lastModified ->
                    updated += item
                else -> kept += old
            }
        }

        val removedPaths = mutableListOf<String>()
        for (old in existing.values) {
            if (old.path in discoveredPaths) continue
            // SAF 树条目：原样保留，不做文件 stat
            if (isContentUri(old.path)) { kept += old; continue }
            // 下载登记条目：位于应用下载目录，常规扫描不覆盖，原样保留
            if (old.source == LocalMediaOrigin.DOWNLOADED) { kept += old; continue }
            // 限定扫描根时，根之外的条目不归本次扫描管辖，原样保留
            if (scanRoots != null && scanRoots.none { root -> isUnderRoot(old.path, root) }) { kept += old; continue }
            removedPaths += old.path
        }

        val all = kept + added + updated
        data = LocalMediaIndexData(
            items = all,
            meta = all.associate { it.path to LocalMediaPathMeta(it.sizeBytes, it.lastModified) },
        )
        return ReconcileResult(added, updated, removedPaths, all)
    }

    companion object {
        /** 是否为 SAF / content 提供器 URI（重扫时不做文件 stat）。 */
        fun isContentUri(path: String): Boolean = path.startsWith("content://")

        /**
         * 判断 [path] 是否位于扫描根 [root] 之下。
         *
         * 统一分隔符（Windows `\` → `/`）并忽略大小写；要求以 `root + "/"` 为前缀
         * 或与 root 完全相等，避免 `Music2/...` 被裸前缀匹配误判为 `Music` 根下。
         */
        fun isUnderRoot(path: String, root: String): Boolean {
            val normalizedPath = path.replace('\\', '/')
            val normalizedRoot = root.replace('\\', '/').trimEnd('/')
            return normalizedPath.equals(normalizedRoot, ignoreCase = true) ||
                normalizedPath.startsWith("$normalizedRoot/", ignoreCase = true)
        }
    }
}

// ============ 平台文件 IO（commonMain expect / jvmMain actual） ============

/** 读取文本文件；不存在或失败返回 null。 */
internal expect fun localMediaReadText(path: String): String?

/** 写入文本文件（自动创建父目录）。 */
internal expect fun localMediaWriteText(path: String, content: String): Boolean
