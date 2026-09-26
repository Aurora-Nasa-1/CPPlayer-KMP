package cp.player.core.integration

import cp.player.core.util.DesktopDataDir
import java.io.File
import java.nio.file.Files
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 端点描述符（发现机制）的测试。
 *
 * ### 为什么这些用例值得写
 * 描述符是**跨进程契约**：集成方按固定路径读它、按固定字段名解析它。
 * 写错了本机完全看不出来（文件确实生成了），只有集成方会拿到 null 然后崩在别处。
 * 所以这里钉三件事：
 *
 * 1. **落盘位置**必须经 [DesktopDataDir]（禁止硬编码 `user.home`）；
 * 2. **字段名与字段集合稳定** —— 少一个字段就是一次静默的契约破坏；
 * 3. **`clear` 幂等且不抛** —— 它在服务停止路径上，抛异常会连带影响关停流程。
 *
 * 隔离：所有用例通过 [DesktopDataDir.PROP_HOME] 把用户目录重定向到临时目录，
 * 保证绝不触碰真实的 `~`。
 */
class IntegrationDescriptorTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
        tempDirs.clear()
    }

    // ============ 写入 ============

    @Test
    fun `publish 写出全部字段且值正确`() {
        withDataHome {
            val writer = createIntegrationDescriptorWriter()
            writer.publish(baseUrl = "http://127.0.0.1:8080", token = "abc123")

            val d = readDescriptor()
            assertEquals(INTEGRATION_APP_NAME, d.app, "app 必须固定，集成方据此确认文件来源")
            assertEquals(INTEGRATION_API_VERSION, d.apiVersion, "apiVersion 必须与 meta.apiVersion 同源")
            assertEquals("http://127.0.0.1:8080", d.baseUrl)
            assertEquals("abc123", d.token)
        }
    }

    @Test
    fun `publish 的 pid 是当前进程`() {
        withDataHome {
            createIntegrationDescriptorWriter().publish("http://127.0.0.1:8080", "t")
            // pid 的**唯一用途**是让集成方判断描述符是否陈旧（进程异常退出时删不掉文件）。
            // 写错成常量或 0 就等于这个字段失效，而失效方式是「集成方永远以为它是活的」。
            assertEquals(ProcessHandle.current().pid(), readDescriptor().pid)
        }
    }

    @Test
    fun `updatedAt 是可解析的带时区偏移时间`() {
        withDataHome {
            createIntegrationDescriptorWriter().publish("http://127.0.0.1:8080", "t")
            val raw = readDescriptor().updatedAt
            // 必须是 ISO-8601 带偏移（不是本地化格式、不是 epoch 数字）——
            // 文档承诺给人工排查用，格式漂了就没法直接读
            val parsed = runCatching { OffsetDateTime.parse(raw) }.getOrNull()
            assertNotNull(parsed, "updatedAt 应是 ISO-8601 带偏移，实际：$raw")
            assertEquals(0, parsed.nano, "应截到秒，避免毫秒抖动产生无意义 diff")
        }
    }

    @Test
    fun `字段集合稳定`() {
        withDataHome {
            createIntegrationDescriptorWriter().publish("http://127.0.0.1:8080", "t")

            // 用「精确集合」而不是「包含」：多一个字段虽然对老集成方无害，
            // 但少一个字段是静默的契约破坏 —— 精确比对能同时抓住两种。
            val keys = Json.parseToJsonElement(descriptorFile().readText()).jsonObject.keys
            assertEquals(
                setOf("app", "apiVersion", "baseUrl", "token", "pid", "updatedAt"),
                keys,
                "描述符字段集合是对外契约，改动必须是有意的",
            )
        }
    }

    @Test
    fun `token 为空时字段仍然出现`() {
        withDataHome {
            createIntegrationDescriptorWriter().publish("http://127.0.0.1:8080", "")

            val keys = Json.parseToJsonElement(descriptorFile().readText()).jsonObject.keys
            assertTrue("token" in keys, "未配置令牌时字段也必须出现，不能静默消失")
            assertEquals("", readDescriptor().token)
        }
    }

    @Test
    fun `publish 覆盖旧内容而不是追加`() {
        withDataHome {
            val writer = createIntegrationDescriptorWriter()
            writer.publish("http://127.0.0.1:1111", "old")
            writer.publish("http://127.0.0.1:2222", "new")

            val d = readDescriptor()
            assertEquals("http://127.0.0.1:2222", d.baseUrl, "端口变了描述符必须跟着变")
            assertEquals("new", d.token, "令牌变了描述符必须跟着变")

            // 追加会让文件变成两段 JSON —— 集成方的解析器会直接崩
            val occurrences = descriptorFile().readText().split("\"baseUrl\"").size - 1
            assertEquals(1, occurrences, "文件里应只有一个 JSON 对象")
        }
    }

    @Test
    fun `publish 不留下临时文件`() {
        withDataHome {
            createIntegrationDescriptorWriter().publish("http://127.0.0.1:8080", "t")

            // 原子替换用同目录临时文件；残留会让「目录里有两个描述符」这种困惑出现
            val leftovers = DesktopDataDir.root().listFiles()
                ?.filter { it.name.startsWith(INTEGRATION_DESCRIPTOR_FILE_NAME) && it.name != INTEGRATION_DESCRIPTOR_FILE_NAME }
                .orEmpty()
            assertTrue(leftovers.isEmpty(), "不应残留临时文件：${leftovers.map { it.name }}")
        }
    }

    // ============ 清除 ============

    @Test
    fun `clear 删除描述符`() {
        withDataHome {
            val writer = createIntegrationDescriptorWriter()
            writer.publish("http://127.0.0.1:8080", "t")
            assertTrue(descriptorFile().exists(), "前置条件：描述符已写入")

            writer.clear()

            assertFalse(descriptorFile().exists(), "服务停了描述符必须消失，否则集成方会连一个死地址")
        }
    }

    @Test
    fun `clear 幂等且文件不存在时不抛异常`() {
        withDataHome {
            val writer = createIntegrationDescriptorWriter()
            // clear 在服务停止 / 停用路径上被调用，可能从未 publish 过。
            // 抛异常会顺着关停流程往上冒，属于「清理逻辑反而制造故障」。
            writer.clear()
            writer.publish("http://127.0.0.1:8080", "t")
            writer.clear()
            writer.clear()
            assertFalse(descriptorFile().exists())
        }
    }

    // ============ 落盘位置 ============

    @Test
    fun `描述符落在 DesktopDataDir 根目录下`() {
        val home = withDataHome {
            createIntegrationDescriptorWriter().publish("http://127.0.0.1:8080", "t")
        }

        // 硬编码 user.home 会让「测试隔离」和「用户改数据目录」两条路径同时失效，
        // 所以这里连**路径形状**一起钉住，而不只是断言「文件存在」。
        val expected = File(File(home, DesktopDataDir.CURRENT_NAME), INTEGRATION_DESCRIPTOR_FILE_NAME)
        assertTrue(expected.isFile, "描述符应在 ${expected.absolutePath}")
    }

    // ============ 脚手架 ============

    /** 把用户目录重定向到临时目录后执行 [block]，返回该临时目录。 */
    private fun withDataHome(block: () -> Unit): File {
        val home = Files.createTempDirectory("cpplayer-descriptor").toFile().also { tempDirs += it }
        val saved = System.getProperty(DesktopDataDir.PROP_HOME)
        System.setProperty(DesktopDataDir.PROP_HOME, home.absolutePath)
        try {
            block()
        } finally {
            if (saved == null) System.clearProperty(DesktopDataDir.PROP_HOME)
            else System.setProperty(DesktopDataDir.PROP_HOME, saved)
        }
        return home
    }

    private fun descriptorFile(): File = DesktopDataDir.file(INTEGRATION_DESCRIPTOR_FILE_NAME)

    /** 读回并用**线上同一份** Json 配置解析，顺带验证落盘内容真的可解码。 */
    private fun readDescriptor(): IntegrationDescriptor =
        IntegrationJson.decodeFromString(IntegrationDescriptor.serializer(), descriptorFile().readText())
}
