package cp.player.kmp.download

import cp.player.kmp.util.PlatformContext
import cp.player.kmp.util.PlatformSupport
import cp.player.kmp.util.SettingsStorage

/**
 * 下载根目录与并发配置。
 *
 * 根目录优先读取 [SettingsStorage] 中的 [KEY_DOWNLOAD_ROOT_DIR]，
 * 未配置时回退平台默认下载目录 [PlatformSupport.defaultDownloadsDir]。
 *
 * [rootDir] 每次读取时动态解析（设置优先、缺省平台目录）：
 * [setRootDir] 修改后新任务立即使用新目录，无需重建实例。
 */
class DownloadConfig(
    private val settings: SettingsStorage,
    private val context: PlatformContext
) {
    /** 下载根目录（自定义优先，缺省平台默认目录）；每次读取动态解析，设置更改后即时生效。 */
    val rootDir: String
        get() = settings.getString(KEY_DOWNLOAD_ROOT_DIR)?.takeIf { it.isNotBlank() }
            ?: PlatformSupport.defaultDownloadsDir(context)

    /** 最大并发下载数 */
    val maxConcurrent: Int = MAX_CONCURRENT

    /** 更新自定义下载根目录（传 null 清除，恢复平台默认） */
    fun setRootDir(path: String?) {
        settings.putString(KEY_DOWNLOAD_ROOT_DIR, path)
    }

    companion object {
        /** SettingsStorage key：自定义下载根目录 */
        const val KEY_DOWNLOAD_ROOT_DIR = "download_root_dir"

        /** 最大并发下载数 */
        const val MAX_CONCURRENT = 3
    }
}
