package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.i18n.AppLanguage
import cp.player.app.i18n.ProvideCpStrings
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.core.model.Message
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ChatMessageKeysTest {
    private fun message(
        id: String,
        text: String,
        time: Long,
        fromUserId: Long = 42L,
    ) = Message(
        id = id,
        fromUserId = fromUserId,
        fromNickname = "Alice",
        fromAvatarUrl = "",
        text = text,
        time = time,
    )

    @Test
    fun `opaque IDs including leading zeroes and punctuation remain distinct`() {
        val messages = listOf(
            message(id = "0007", text = "first", time = 1),
            message(id = "7", text = "second", time = 2),
            message(id = "msg:part-1", text = "third", time = 3),
        )

        val keys = stableChatMessageKeys(messages)

        assertEquals(3, keys.toSet().size)
        assertNotEquals(keys[0], keys[1])
    }

    @Test
    fun `keys survive insertion of an older unrelated message`() {
        val older = message(id = "old", text = "older", time = 1)
        val first = message(id = "id:1", text = "first", time = 2)
        val second = message(id = "id:2", text = "second", time = 3)
        val original = stableChatMessageKeys(listOf(first, second))
        val afterInsertion = stableChatMessageKeys(listOf(older, first, second))

        assertEquals(original[0], afterInsertion[1])
        assertEquals(original[1], afterInsertion[2])
    }

    @Test
    fun `duplicate IDs remain unique and exact duplicate records remain deterministic`() {
        val first = message(id = "same", text = "one", time = 1)
        val second = message(id = "same", text = "two", time = 2)
        val duplicate = message(id = "same", text = "two", time = 2)
        val messages = listOf(first, second, duplicate)

        val keys = stableChatMessageKeys(messages)

        assertEquals(messages.size, keys.toSet().size)
        assertEquals(keys, stableChatMessageKeys(messages))
    }

    @Test
    fun `only strict appends are eligible for animated scrolling`() {
        val original = listOf("a", "b")
        assertTrue(isStrictlyAppendedChatList(original, listOf("a", "b", "c")))
        assertFalse(isStrictlyAppendedChatList(emptyList(), listOf("a")))
        assertFalse(isStrictlyAppendedChatList(original, original))
        assertFalse(isStrictlyAppendedChatList(original, listOf("x", "a", "b")))
        assertFalse(isStrictlyAppendedChatList(original, listOf("a")))
    }

    @Test
    fun `chat message list with lazy scroll column renders offscreen with desktop scrollbar`() {
        val messages = (1..20).map { index ->
            message(
                id = "msg-$index",
                text = "这是第 $index 条消息内容，测试桌面端滚动条正常渲染与展示",
                time = 1700000000L + index * 60L,
                fromUserId = if (index % 2 == 0) 0L else 42L,
            )
        }
        val scene = ImageComposeScene(width = 800, height = 600, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.DARK) {
                ProvideCpStrings(AppLanguage.ZH_HANS) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        val keys = stableChatMessageKeys(messages)
                        LazyScrollColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = CpSpacing.pageHorizontal, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(messages.size, key = { index -> keys[index] }) { index ->
                                MessageBubble(messages[index], emptyText = "空消息")
                            }
                        }
                    }
                }
            }
        }
        val bytes = try {
            scene.render().encodeToData()?.bytes ?: error("PNG encoding failed")
        } finally {
            scene.close()
        }
        File("build/chat-messages-desktop.png").apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
        assertTrue(bytes.size > 5000, "离屏渲染图片大小异常: ${bytes.size}")
    }
}
