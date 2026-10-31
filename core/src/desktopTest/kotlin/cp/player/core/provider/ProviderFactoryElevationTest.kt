package cp.player.core.provider

import cp.player.core.util.PlatformSupport
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderFactoryElevationTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setup() {
        tempDir = File.createTempFile("cp_provider_test_", "").apply {
            delete()
            mkdirs()
        }
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `resolveEntryPoint 能够解析 lib 下的候选 so 文件`() {
        // 创建 lib/x86_64/libncm_server.so
        val abiDir = File(tempDir, "lib/x86_64").apply { mkdirs() }
        val soFile = File(abiDir, "libncm_server.so").apply { writeText("mock elf") }

        // entryPoint 声明为 "ncm-server"
        val resolved = PlatformSupport.resolveEntryPoint(
            moduleDir = tempDir.absolutePath,
            entryPoint = "ncm-server",
            supportedAbis = listOf("x86_64")
        )

        assertEquals(soFile.canonicalPath, File(resolved).canonicalPath)
    }

    @Test
    fun `resolveEntryPoint 声明带 exe 时亦能找到对应 so 库`() {
        val abiDir = File(tempDir, "lib/x86_64").apply { mkdirs() }
        val soFile = File(abiDir, "libmyapi.so").apply { writeText("mock elf") }

        val resolved = PlatformSupport.resolveEntryPoint(
            moduleDir = tempDir.absolutePath,
            entryPoint = "myapi.exe",
            supportedAbis = listOf("x86_64")
        )

        assertEquals(soFile.canonicalPath, File(resolved).canonicalPath)
    }

    @Test
    fun `ProviderFactory 在入口为 so 库时将 binary 规范提升至 JniProvider`() {
        val abiDir = File(tempDir, "lib/x86_64").apply { mkdirs() }
        File(abiDir, "libncm_server.so").writeText("mock elf")

        val manifest = ModuleManifest(
            id = "test-binary-to-so",
            name = "Test Binary Elevation",
            version = "1.0.0",
            type = "binary",
            entryPoint = "ncm-server",
            supportedAbis = listOf("x86_64")
        )

        val provider = ProviderFactory.create(manifest, tempDir.absolutePath)
        assertNotNull(provider, "应成功创建 Provider")
        assertTrue(provider is JniProvider, "入口为 .so 时应提升为 JniProvider，实际为: ${provider::class.simpleName}")
        assertEquals(ProviderType.JNI, provider.type)
    }

    @Test
    fun `ProviderFactory 在桌面端常规二进制文件时创建 BinaryProvider`() {
        val binFile = File(tempDir, "my-server.exe").apply { writeText("mock binary") }

        val manifest = ModuleManifest(
            id = "test-desktop-binary",
            name = "Desktop Binary",
            version = "1.0.0",
            type = "binary",
            entryPoint = "my-server.exe"
        )

        val provider = ProviderFactory.create(manifest, tempDir.absolutePath)
        assertNotNull(provider, "桌面端存在可执行文件时应成功创建")
        assertTrue(provider is BinaryProvider, "桌面端应创建 BinaryProvider，实际为: ${provider::class.simpleName}")
        assertEquals(ProviderType.BINARY, provider.type)
    }

    @Test
    fun `ProviderFactory 当二进制和 so 都不存在时返回 null`() {
        val manifest = ModuleManifest(
            id = "test-nonexistent",
            name = "Nonexistent",
            version = "1.0.0",
            type = "binary",
            entryPoint = "missing-server"
        )

        val provider = ProviderFactory.create(manifest, tempDir.absolutePath)
        assertNull(provider, "不存在入口文件时应返回 null")
    }

    @Test
    fun `ProviderFactory 将 apiVersionTag 透传到 Provider`() {
        // 标签只用于展示，但**显示的地方读的是 provider.apiVersionTag**，不是 manifest。
        // 少接一处（比如 http 分支漏传）就会静默退化成「这包没写标签」—— 编译过、界面空。
        val manifest = ModuleManifest(
            id = "tagged-http",
            name = "Tagged Http",
            version = "1.0.0",
            type = "http",
            entryPoint = "http://127.0.0.1:8005",
            apiVersionTag = "netease-v3",
        )

        val provider = ProviderFactory.create(manifest, tempDir.absolutePath)
        assertNotNull(provider, "http 音源不需要入口文件，必须创建成功")
        assertEquals("netease-v3", provider.apiVersionTag)
        assertEquals(1, provider.apiVersion, "声明标签不该顺手改契约编号")
    }

    @Test
    fun `explicit empty capabilities stay empty rather than enabling legacy mode`() {
        for (capabilities in listOf(emptyList(), listOf("futureCapability"))) {
            val manifest = ModuleManifest(
                id = "restricted-http",
                name = "Restricted Http",
                version = "1.0.0",
                type = "http",
                entryPoint = "http://127.0.0.1:8006",
                capabilities = capabilities,
            )
            val provider = ProviderFactory.create(manifest, tempDir.absolutePath)
            assertNotNull(provider)
            assertEquals(emptyList(), provider.capabilities)
        }
    }

    @Test
    fun `ProviderFactory 未声明标签时 Provider 侧为 null`() {
        val manifest = ModuleManifest(
            id = "untagged-http",
            name = "Untagged Http",
            version = "1.0.0",
            type = "http",
            entryPoint = "http://127.0.0.1:8006",
        )

        val provider = ProviderFactory.create(manifest, tempDir.absolutePath)
        assertNotNull(provider)
        assertNull(provider.apiVersionTag, "老包没声明标签时必须为 null，界面才不会多画一个空角标")
    }
}
