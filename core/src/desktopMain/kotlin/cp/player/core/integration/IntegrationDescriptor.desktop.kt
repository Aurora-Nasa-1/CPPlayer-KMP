package cp.player.core.integration

import cp.player.core.util.DesktopDataDir
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * Desktop 描述符写入器：把端点写到 `~/.cpplayer/integration.json`。
 *
 * 路径**必须**经 [DesktopDataDir]（仓库约定：桌面数据目录的唯一来源，禁止硬编码 `user.home`）。
 * 顺带白拿一次历史目录迁移（`.kmp-pro` → `.cpplayer`）。
 *
 * ### 三个实现细节，都是「集成方会真的踩到」的
 *
 * 1. **原子替换**：先写同目录临时文件再 `ATOMIC_MOVE`。集成方可能在**任意时刻**读这个文件，
 *    直接覆写会让它读到半截 JSON。同一目录内 move 才可能原子，所以临时文件放在同目录。
 * 2. **权限收紧到仅属主可读**（POSIX 平台）。文件含明文令牌 —— 虽然同用户进程本来就能从
 *    `cp_player_prefs.properties` 读到同样的令牌，但**跨用户**是新增暴露面，必须挡住。
 *    Windows 不支持 POSIX 视图，跳过（ACL 操作复杂且易误伤，收益不成比例）。
 * 3. **一切异常都吞掉**：描述符是便利设施，写不进去只意味着集成方要手抄地址；
 *    为此让本地服务器起不来是本末倒置。
 */
internal class DesktopIntegrationDescriptorWriter : IntegrationDescriptorWriter {

    private val file: File get() = DesktopDataDir.file(INTEGRATION_DESCRIPTOR_FILE_NAME)

    override fun publish(baseUrl: String, token: String) {
        runCatching {
            val descriptor = IntegrationDescriptor(
                app = INTEGRATION_APP_NAME,
                apiVersion = INTEGRATION_API_VERSION,
                baseUrl = baseUrl,
                token = token,
                pid = ProcessHandle.current().pid(),
                // 截到秒：ISO-8601 带偏移，人可读，且不因毫秒抖动而产生无意义 diff
                updatedAt = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString(),
            )
            writeAtomically(IntegrationJson.encodeToString(IntegrationDescriptor.serializer(), descriptor))
        }
    }

    override fun clear() {
        runCatching { if (file.exists()) file.delete() }
    }

    // ============ 内部 ============

    private fun writeAtomically(content: String) {
        val target = file
        target.parentFile?.mkdirs()

        // 临时文件必须与目标**同目录**：跨目录/跨卷的 move 不是原子操作，
        // 就失去了「集成方永远读不到半截 JSON」这个保证。
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(content, Charsets.UTF_8)
        restrictToOwner(temp)

        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            // 少数文件系统（部分网络盘）不支持原子 move，退化为普通替换 ——
            // 仍然比直接覆写原文件好：原文件在被替换前一直是完整的。
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** 把文件权限收紧到「仅属主可读写」。非 POSIX 文件系统静默跳过。 */
    private fun restrictToOwner(target: File) {
        runCatching {
            val view = Files.getFileAttributeView(target.toPath(), java.nio.file.attribute.PosixFileAttributeView::class.java)
                ?: return
            view.setPermissions(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        }
    }
}

actual fun createIntegrationDescriptorWriter(): IntegrationDescriptorWriter =
    DesktopIntegrationDescriptorWriter()
