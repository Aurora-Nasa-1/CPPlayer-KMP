package cp.player.core.util

import java.io.File

/**
 * 桌面端数据目录的**唯一来源**。
 *
 * 目录名历史上是 `.kmp-pro`（沿用已废弃的模块名 `kmp-pro`），现在改为 `.cpplayer`。
 * 为避免用户丢失既有数据（渲染后端设置、下载记录、本地媒体索引、已导入文件夹），
 * [root] 首次被访问时会尝试一次**一次性迁移**。
 *
 * ### 迁移规则
 *
 * 仅在「旧目录存在**且**新目录不存在」时搬运。两边都存在说明用户已经在新目录上
 * 跑过一段时间了，此时**绝不能用旧数据覆盖新数据**，直接放弃迁移。
 *
 * 迁移是尽力而为的：失败时只记日志，不抛异常 —— 宁可让用户重新配置一次，
 * 也不能让应用起不来。
 *
 * ### 位置
 *
 * 放在 `jvmMain` 而非 `desktopMain`，因为 [cp.player.core.local.DesktopLocalMediaSource]
 * 在 `jvmMain`，而 `jvmMain` 看不到 `desktopMain`。
 */
object DesktopDataDir {

    /** 当前目录名。 */
    const val CURRENT_NAME = ".cpplayer"

    /** 旧目录名（已废弃的模块名）。 */
    const val LEGACY_NAME = ".kmp-pro"

    /** 迁移标记，写入新目录，记录数据从哪来。 */
    const val MIGRATION_MARKER = ".migrated-from"

    /** 用户目录覆写点，**仅供测试隔离**（免得测试去动真实 `~`）。 */
    internal const val PROP_HOME = "cp.player.dataHome"

    private val home: File
        get() = System.getProperty(PROP_HOME)?.takeIf(String::isNotBlank)?.let(::File)
            ?: File(System.getProperty("user.home"))

    /** 旧目录（可能不存在；迁移成功后即消失）。 */
    val legacyRoot: File get() = File(home, LEGACY_NAME)

    /** 数据根目录。返回前确保迁移已尝试、目录已创建。 */
    fun root(): File = File(home, CURRENT_NAME).also {
        migrateIfNeeded(legacyRoot, it)
        if (!it.exists()) it.mkdirs()
    }

    /** 根目录下的文件路径（不创建文件）。 */
    fun file(name: String): File = File(root(), name)

    /** 根目录下的子目录，返回前确保存在。 */
    fun directory(name: String): File = File(root(), name).also { if (!it.exists()) it.mkdirs() }

    /**
     * 一次性迁移，幂等。
     *
     * @return 是否真的发生了迁移
     */
    internal fun migrateIfNeeded(legacy: File, target: File): Boolean {
        if (!legacy.isDirectory) return false
        if (target.exists()) return false

        // 同一用户目录下通常同卷，rename 是原子的；跨卷或权限问题退化为复制。
        val renamed = try {
            legacy.renameTo(target)
        } catch (_: Exception) {
            false
        }

        val migrated = renamed || try {
            legacy.copyRecursively(target, overwrite = false)
            true
        } catch (_: Exception) {
            // 复制失败时清掉半成品，否则下次启动会因为 target 已存在而跳过迁移，
            // 用户就永远停在「部分数据」状态。
            try {
                target.deleteRecursively()
            } catch (_: Exception) {
                // 清不掉也没办法，交给下次启动
            }
            false
        }

        if (migrated) {
            try {
                File(target, MIGRATION_MARKER).writeText(legacy.absolutePath)
            } catch (_: Exception) {
                // 标记写不进去不影响迁移本身
            }
        }
        return migrated
    }
}
