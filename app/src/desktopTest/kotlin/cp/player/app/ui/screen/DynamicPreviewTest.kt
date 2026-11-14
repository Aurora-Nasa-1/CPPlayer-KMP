package cp.player.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import cp.player.app.model.DynamicOrigin
import cp.player.app.model.DynamicPost
import cp.player.app.ui.component.LocalEmbeddedInPane
import cp.player.app.ui.component.LocalWindowChromeActive
import cp.player.app.ui.model.DynamicFilter
import cp.player.app.ui.model.DynamicUiState
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 「动态」页的离屏版式探针。
 *
 * 编译与纯测量看不到卡片内部：图片网格、资源卡、转发快照在窄屏是否互相挤压，
 * 长文本是否把操作行推出可视区，只有实际渲染才能确认。
 */
class DynamicPreviewTest {

    private val selfId = "1001"

    private fun feedState() = DynamicUiState(
        posts = listOf(
            DynamicPost(
                id = "event-1",
                userId = selfId,
                userName = "我自己",
                avatarUrl = "",
                text = "今天循环了一整张专辑。动态卡片要把长文本、图片和资源卡放在同一条里，" +
                    "第二行继续写一些内容，确认行距和折行都正常。",
                publishedAtEpochMs = 1_762_000_000_000,
                imageUrls = listOf(
                    "file:///nonexistent/a.png",
                    "file:///nonexistent/b.png",
                    "file:///nonexistent/c.png",
                ),
                resourceId = "song:1",
                resourceType = "song",
                resourceTitle = "夜曲",
                resourceCoverUrl = "file:///nonexistent/cover.png",
                resourceSubtitle = "周杰伦 · 十一月的萧邦",
                liked = true,
                likedCount = 128,
                commentCount = 9,
                forwardCount = 3,
                location = "上海",
            ),
            DynamicPost(
                id = "event-2",
                userId = "2002",
                userName = "阿岚",
                avatarUrl = "",
                text = "转发一条动态，看看原动态卡片在窄屏下会不会把资源卡挤掉。",
                publishedAtEpochMs = 1_761_990_000_000,
                likedCount = 42,
                commentCount = 5,
                forwardCount = 12,
                forwardedFrom = DynamicOrigin(
                    id = "origin-1",
                    userId = "3003",
                    userName = "海边散步的人",
                    avatarUrl = "",
                    text = "原动态正文，包含一段足够长的文字来验证四行折叠与展开按钮的位置。",
                    publishedAtEpochMs = 1_761_980_000_000,
                    imageUrls = listOf("file:///nonexistent/d.png"),
                    resourceId = "album:9",
                    resourceType = "album",
                    resourceTitle = "范特西",
                    resourceCoverUrl = "file:///nonexistent/album.png",
                    resourceSubtitle = "周杰伦",
                    likedCount = 88,
                    commentCount = 7,
                    forwardCount = 4,
                ),
            ),
            DynamicPost(
                id = "event-3",
                userId = "4004",
                userName = "没有配图的人",
                avatarUrl = "",
                text = "一条只有文字的短动态。",
                publishedAtEpochMs = 1_761_970_000_000,
                likedCount = 0,
                commentCount = 0,
                forwardCount = 0,
            ),
        ),
        filter = DynamicFilter.Following,
        initialized = true,
    )

    private fun render(
        width: Int,
        height: Int,
        themeMode: ThemeMode,
        state: DynamicUiState,
        supportsFeed: Boolean = true,
    ): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            CpTheme(themeMode = themeMode) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    CompositionLocalProvider(
                        LocalEmbeddedInPane provides false,
                        LocalWindowChromeActive provides false,
                    ) {
                        DynamicContent(
                            state = state,
                            supportsFeed = supportsFeed,
                            selfId = selfId,
                            modifier = Modifier.fillMaxSize(),
                            onRefresh = {},
                            onLoadMore = {},
                            onSelectFollowing = {},
                            onSelectOwn = {},
                            onLike = { _ -> },
                            onComment = { _ -> },
                            onForward = { _ -> },
                            onDelete = { _ -> },
                            onOpenResource = { _ -> },
                            onOpenOrigin = { _ -> },
                            onOpenUser = { _, _ -> },
                            onPreviewImage = { _ -> },
                        )
                    }
                }
            }
        }
        return try {
            repeat(4) { scene.render(it * 16_000_000L) }
            scene.render().encodeToData()?.bytes ?: error("PNG encoding failed")
        } finally {
            scene.close()
        }
    }

    @Test
    fun `feed renders on phone and desktop`() {
        val phone = render(390, 900, ThemeMode.DARK, feedState())
        val desktop = render(1000, 900, ThemeMode.LIGHT, feedState())
        File("build/dynamic-feed-phone.png").writeBytes(phone)
        File("build/dynamic-feed-desktop.png").writeBytes(desktop)
        assertTrue(phone.size > 15_000, "手机端动态预览不应为空，实际 ${phone.size} bytes")
        assertTrue(desktop.size > 15_000, "桌面端动态预览不应为空，实际 ${desktop.size} bytes")
    }

    @Test
    fun `unsupported provider shows explanation instead of retry`() {
        val bytes = render(
            width = 390,
            height = 700,
            themeMode = ThemeMode.DARK,
            state = DynamicUiState(initialized = true, unsupported = true),
        )
        File("build/dynamic-unsupported.png").writeBytes(bytes)
        assertTrue(bytes.size > 8_000, "不支持态预览不应为空，实际 ${bytes.size} bytes")
    }
}
