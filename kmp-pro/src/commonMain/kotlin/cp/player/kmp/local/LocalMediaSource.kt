package cp.player.kmp.local

import cp.player.kmp.media.LocalMediaItem
import cp.player.kmp.util.PlatformContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 本地媒体源接口（泛化版，替代原 [LocalMusicSource]）。
 *
 * 不再绑定「歌曲」概念，统一管理本地音频 / 视频条目
 * （[cp.player.kmp.media.LocalMediaItem]），覆盖三类来源：
 * 1. 平台媒体库 / 默认目录扫描（[scan]）
 * 2. 用户导入的文件夹 / SAF 树（[importFolder]）
 * 3. 应用内下载完成登记（[addExternalItems]，由下载侧调用）
 *
 * mediaId 规则：`local://{audio|video}/{path}`，path 即 [LocalMediaItem.path]。
 */
interface LocalMediaSource {

    /**
     * 触发一次（增量）扫描，返回进度流。
     *
     * 流按批次（通常 50 条/批）发射 [ScanProgress]，完成后自然结束。
     * 若缺少媒体读取权限（Android），流仅发射一条带
     * [ScanProgress.permissionDenied] 标志的进度供 UI 引导授权，不抛异常。
     */
    suspend fun scan(): Flow<ScanProgress>

    /**
     * 导入文件夹 / SAF 树。
     *
     * - Desktop：[uri] 为本地目录绝对路径，导入后加入持久化的「已导入文件夹」列表
     * - Android：[uri] 为 `content://` 树 URI（调用方已 takePersistableUriPermission；
     *   权限丢失时跳过并报告，返回 0）
     *
     * @return 新增条目数
     */
    suspend fun importFolder(uri: String): Int

    /** 从索引与列表中移除单个条目（不删除磁盘文件）。 */
    fun removeItem(item: LocalMediaItem)

    /** 当前全部本地条目（响应式快照流）。 */
    fun items(): StateFlow<List<LocalMediaItem>>

    /** 是否正在扫描（响应式）。 */
    val isScanningFlow: StateFlow<Boolean>

    /**
     * 登记外部产生的条目（供下载完成回调使用）。
     *
     * 按 [LocalMediaItem.path] 去重合并；重复路径以新条目为准。
     */
    fun addExternalItems(items: List<LocalMediaItem>)
}

/**
 * 扫描进度。
 *
 * @param scanned 已扫描条目数
 * @param total 本次扫描发现的总条目数（未知时为 0）
 * @param batch 当前批次条目（最多 50 条）
 * @param permissionDenied 缺少媒体读取权限标志（UI 据此引导授权）
 * @param errorMessage 错误描述（可空）
 */
data class ScanProgress(
    val scanned: Int,
    val total: Int,
    val batch: List<LocalMediaItem> = emptyList(),
    val permissionDenied: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * 创建平台本地媒体源。
 *
 * - Android（androidMain actual）：MediaStore 扫描 + SAF 树导入，需要 Android Context
 * - Desktop（desktopMain actual）：文件系统遍历默认音乐/视频目录 + 已导入文件夹
 */
expect fun createLocalMediaSource(context: PlatformContext): LocalMediaSource
