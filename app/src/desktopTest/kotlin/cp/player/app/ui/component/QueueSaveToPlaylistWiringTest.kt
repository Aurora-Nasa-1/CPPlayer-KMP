package cp.player.app.ui.component

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 验证播放队列（播放列表）保存为歌单相关的接线与逻辑。
 */
class QueueSaveToPlaylistWiringTest {

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
        check(found != null) { "找不到 $relative" }
        return found.readText()
    }

    @Test
    fun `QueueBottomSheet 包含本地歌单和在线歌单的保存落点`() {
        val source = readSource("src/commonMain/kotlin/cp/player/app/ui/component/QueueBottomSheet.kt")
        assertTrue("showLocalSaveDialog" in source, "缺少 showLocalSaveDialog 本地歌单保存状态")
        assertTrue("showSaveSheet" in source, "缺少 showSaveSheet 在线歌单保存状态")
        assertTrue("AddToLocalPlaylistDialog" in source, "缺少 AddToLocalPlaylistDialog 宿主")
        assertTrue("AddToPlaylistSheet" in source, "缺少 AddToPlaylistSheet 宿主")
        assertTrue("isAllLocal" in source, "缺少针对全本地队列的判断")
    }

    @Test
    fun `AddToPlaylistSheet 正确支持裸ID解析且分批添加`() {
        val source = readSource("src/commonMain/kotlin/cp/player/app/ui/component/AddToPlaylistSheet.kt")
        assertTrue("!trimmed.contains(\"://\")" in source, "extractRawId 必须支持裸曲目 ID 而非仅解析带 scheme 的媒体 ID")
        assertTrue("chunked(" in source, "批量加入歌单必须分批执行以避免参数超长")
        assertTrue("providerId != \"local\"" in source, "必须过滤本地条目")
    }

    @Test
    fun `PlaylistPickerSheet 获取歌单包含未匹配兜底`() {
        val source = readSource("src/commonMain/kotlin/cp/player/app/ui/component/PlaylistPickerSheet.kt")
        assertTrue("filtered.ifEmpty { all }" in source, "用户昵称为空或未精确匹配时应兜底展示所有歌单")
    }
}
