package cp.player.app.ui.component

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 回归：本地歌曲「更多 → 歌曲信息」必须能打开，且「在文件管理器中打开」不能是一条死路。
 *
 * ## 两个真实故障
 *
 * 1. `SongDetailInfoDialog` 对**任何**曲目都去调在线 `song/detail`。本地曲目的 id 是
 *    `local://audio/<path>`，音源侧不可能解析 ⇒ 请求失败 ⇒ 弹层弹一条「获取歌曲信息失败」
 *    后**自己关掉**。用户看到的就是「Info 打不开」。
 * 2. `openInFileManager` 在 Android 上是 `= false` 的占位实现，在桌面端则要求入参必须是
 *    **目录** —— 而歌曲行传的是**文件**路径 ⇒ 恒返回 false，且调用方把返回值丢掉了，
 *    于是点「在文件管理器中打开」毫无反应。
 *
 * ## 为什么是源码断言
 *
 * 桌面测试进程不初始化 `MusicBackend`（见 `UserProfileSongOptionsWiringTest` 的说明），
 * 组合这些组件会直接抛 `MusicBackend.init() not called`。这里钉的是**分叉点本身**：
 * 本地曲目必须走本地分支、平台实现必须接受文件路径、调用方必须检查返回值。
 */
class LocalTrackInfoDialogTest {

    private fun readSource(relative: String): String {
        var dir: File? = File("").absoluteFile
        val found = buildList {
            while (dir != null) {
                File(dir, relative).takeIf { it.isFile }?.let { add(it) }
                val underApp = File(File(dir, "app"), relative)
                if (underApp.isFile) add(underApp)
                dir = dir.parentFile
            }
        }.firstOrNull()
        check(found != null) { "找不到 $relative（cwd=${File("").absolutePath}）" }
        return found.readText()
    }

    private val dialogSource by lazy {
        readSource("src/commonMain/kotlin/cp/player/app/ui/component/SongDetailInfoDialog.kt")
    }

    @Test
    fun `本地曲目不发在线请求并渲染文件信息`() {
        assertTrue(
            "if (isLocal) return@LaunchedEffect" in dialogSource,
            "本地曲目必须在 LaunchedEffect 里提前返回，否则会去撞一次必然失败的 song/detail",
        )
        assertTrue(
            "localMedia.items()" in dialogSource,
            "本地曲目的信息应来自 LocalMediaItem（路径 / 大小 / 质量标签）",
        )
        assertTrue(
            "removePrefix(\"local://audio/\")" in dialogSource,
            "必须从 mediaId 解出文件路径（local://audio/<path>）",
        )
    }

    @Test
    fun `本地曲目信息包含路径与体积两行`() {
        assertTrue("s.player.infoPath" in dialogSource, "本地曲目信息必须展示文件路径")
        assertTrue("s.player.infoSize" in dialogSource, "本地曲目信息应展示文件体积")
        assertTrue("formatBytes(" in dialogSource, "体积要格式化，不能甩原始字节数")
    }

    @Test
    fun `失败通知不能先于本地分支`() {
        val failNotify = dialogSource.indexOf("loadTrackInfoFailed")
        val localReturn = dialogSource.indexOf("if (isLocal) return@LaunchedEffect")
        assertTrue(failNotify >= 0 && localReturn >= 0, "缺少失败通知或本地提前返回")
        assertTrue(
            localReturn < failNotify,
            "本地曲目必须在走在线失败分支之前返回，否则仍会弹「获取歌曲信息失败」",
        )
    }

    @Test
    fun `桌面端在文件管理器中打开支持文件而不是只支持目录`() {
        val source = readSource("src/desktopMain/kotlin/cp/player/app/platform/PlatformActions.desktop.kt")
        assertFalse(
            "val dir = File(path)\n" in source,
            "旧实现直接要求入参是目录；现在必须先判断是文件还是目录",
        )
        assertTrue(
            "val dir = if (isDirectory) target else target.parentFile" in source,
            "文件路径要落到父目录，否则 target.isDirectory 会把所有歌曲判成失败",
        )
        assertTrue("if (!target.exists()) return@runCatching false" in source, "路径不存在时应快速失败")
        assertTrue("/select," in source, "Windows 上应使用 explorer.exe /select,<file> 定位文件")
        // 断言真实调用形态，而不是 KDoc 里出现的 `open -R` 字样。
        assertTrue("listOf(\"open\", \"-R\"," in source, "macOS 上应使用 open -R 显示文件")
    }

    @Test
    fun `Android 端在文件管理器中打开不再是恒 false 的占位实现`() {
        val source = readSource("src/androidMain/kotlin/cp/player/app/platform/PlatformActions.android.kt")
        assertFalse(
            "actual fun openInFileManager(path: String): Boolean = false" in source,
            "Android 的 openInFileManager 仍是恒 false 的占位实现",
        )
        assertTrue(
            "DocumentsContract" in source,
            "Android 应通过 DocumentsUI（DocumentsContract）定位文件 / 目录",
        )
        assertTrue(
            "FileProvider.getUriForFile" in source,
            "应用私有目录（下载产物）文件管理器不可达，应有 FileProvider 兜底",
        )
        assertTrue(
            ".fileprovider\"" in source,
            "FileProvider 的 authority 必须与 manifest 一致（\${applicationId}.fileprovider）",
        )
    }

    @Test
    fun `调用方检查返回值并给出失败提示`() {
        val helper = readSource("src/commonMain/kotlin/cp/player/app/ui/util/FileManagerReveal.kt")
        assertTrue("openInFileManager(path)" in helper, "revealInFileManager 应委托平台实现")
        assertTrue("UiEvents.notify" in helper, "失败必须通知用户，不能静默")
        assertTrue("openInFileManagerFailed(path)" in helper, "失败提示要带上是哪个路径")

        val callSites = listOf(
            "src/commonMain/kotlin/cp/player/app/ui/screen/LocalLibraryPane.kt",
            "src/commonMain/kotlin/cp/player/app/ui/screen/LibraryScreen.kt",
            "src/commonMain/kotlin/cp/player/app/ui/screen/LibrarySearchScreen.kt",
            "src/commonMain/kotlin/cp/player/app/ui/screen/DownloadsScreen.kt",
            "src/commonMain/kotlin/cp/player/app/ui/screen/RecentPlaysScreen.kt",
            "src/commonMain/kotlin/cp/player/app/ui/screen/InsightsScreen.kt",
        )
        callSites.forEach { relative ->
            val source = readSource(relative)
            assertFalse(
                "cp.player.app.platform.openInFileManager(" in source,
                "$relative 仍直接调用平台实现并丢弃返回值，应改用 revealInFileManager(...)",
            )
        }
    }

    @Test
    fun `本地歌曲的 mediaId 必须带文件路径`() {
        // 空资源 ID（`local://audio/`）会让「歌曲信息」按 id 反查不到本地文件。
        listOf(
            "src/commonMain/kotlin/cp/player/app/ui/screen/LocalLibraryPane.kt",
            "src/commonMain/kotlin/cp/player/app/ui/screen/LibrarySearchScreen.kt",
        ).forEach { relative ->
            val source = readSource(relative)
            assertFalse(
                "\"local://audio/\"" in source,
                "$relative 里存在不带 path 的本地 mediaId 构造",
            )
        }
    }
}
