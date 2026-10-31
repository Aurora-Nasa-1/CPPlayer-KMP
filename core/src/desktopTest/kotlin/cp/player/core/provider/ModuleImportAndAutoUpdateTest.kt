package cp.player.core.provider

import cp.player.core.util.PlatformContext
import cp.player.core.util.SettingsStorage
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModuleImportAndAutoUpdateTest {

    private class MapSettings : SettingsStorage {
        private val map = mutableMapOf<String, String>()
        override fun getString(key: String, default: String?): String? = map[key] ?: default
        override fun putString(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
        override fun remove(key: String) { map.remove(key) }
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun clear() { map.clear() }
    }

    private lateinit var tempModulesDir: File
    private lateinit var tempZipDir: File
    private lateinit var moduleManager: ModuleManager
    private lateinit var providerManager: ProviderManager
    private val context = PlatformContext()

    @BeforeTest
    fun setup() {
        tempModulesDir = Files.createTempDirectory("cp_modules_test").toFile()
        tempZipDir = Files.createTempDirectory("cp_zips_test").toFile()
        val settings = MapSettings()
        val cookieStorage = ProviderCookieStorage(settings)
        providerManager = ProviderManager(settings, cookieStorage)
        moduleManager = ModuleManager(tempModulesDir.absolutePath, context)
        moduleManager.init(providerManager)
    }

    @AfterTest
    fun tearDown() {
        tempModulesDir.deleteRecursively()
        tempZipDir.deleteRecursively()
    }

    private fun createZip(fileName: String, manifestContent: String, extraFiles: Map<String, String> = emptyMap()): File {
        val zipFile = File(tempZipDir, fileName)
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifestContent.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            for ((path, content) in extraFiles) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return zipFile
    }

    @Test
    fun importZipReturnsExactProviderInsteadOfLastInList() {
        // 先导入酷狗
        val kugouZip = createZip(
            "kugou.zip",
            """{"id":"kugou","name":"酷狗音乐","type":"http","entryPoint":"http://127.0.0.1:8001","version":"1.0.0"}"""
        )
        val kugouResult = moduleManager.importModuleDetails(kugouZip.absolutePath)
        assertNotNull(kugouResult)
        assertEquals("kugou", kugouResult.provider.id)
        assertEquals("酷狗音乐", kugouResult.provider.name)

        // 再导入咪咕
        val miguZip = createZip(
            "migu.zip",
            """{"id":"migu","name":"咪咕音乐","type":"http","entryPoint":"http://127.0.0.1:8002","version":"1.0.0"}"""
        )
        val miguResult = moduleManager.importModuleDetails(miguZip.absolutePath)
        assertNotNull(miguResult)
        assertEquals("migu", miguResult.provider.id)
        assertEquals("咪咕音乐", miguResult.provider.name)

        // 再次导入酷狗的新版包（ID不变）
        val kugouV2Zip = createZip(
            "kugou_v2.zip",
            """{"id":"kugou","name":"酷狗音乐","type":"http","entryPoint":"http://127.0.0.1:8003","version":"2.0.0"}"""
        )
        val kugouV2Result = moduleManager.importModuleDetails(kugouV2Zip.absolutePath)
        assertNotNull(kugouV2Result)
        // 关键断言：必须精确返回酷狗，不能返回列表最后的咪咕！
        assertEquals("kugou", kugouV2Result.provider.id)
        assertEquals("酷狗音乐", kugouV2Result.provider.name)
        assertTrue(kugouV2Result.isUpdate)
        assertEquals("kugou", kugouV2Result.replacedProviderId)
    }

    @Test
    fun importAutoDetectsSameNameWithDifferentIdAndReplacesOldDirectory() {
        // 先导入旧版酷狗，ID 为 kugou-music-rs
        val oldKugouZip = createZip(
            "kugou_old.zip",
            """{"id":"kugou-music-rs","name":"酷狗音乐","type":"http","entryPoint":"http://127.0.0.1:8001","version":"0.1.0"}"""
        )
        val r1 = moduleManager.importModuleDetails(oldKugouZip.absolutePath)
        assertNotNull(r1)
        assertEquals("kugou-music-rs", r1.provider.id)
        assertTrue(File(tempModulesDir, "kugou-music-rs").exists())

        // 用户导入新包，名称同为「酷狗音乐」，但作者将包 ID 改为了 kugou
        val newKugouZip = createZip(
            "kugou_new.zip",
            """{"id":"kugou","name":"酷狗音乐","type":"http","entryPoint":"http://127.0.0.1:8002","version":"0.2.0"}"""
        )
        val r2 = moduleManager.importModuleDetails(newKugouZip.absolutePath)
        assertNotNull(r2)
        assertEquals("kugou", r2.provider.id)
        assertEquals("酷狗音乐", r2.provider.name)
        assertTrue(r2.isUpdate, "同名模块导入必须被自动识别为更新")
        assertEquals("kugou-music-rs", r2.replacedProviderId, "必须记录被替换的旧模块 ID")

        // 校验目录替换
        assertTrue(File(tempModulesDir, "kugou").exists(), "新模块目录必须存在")
        assertTrue(!File(tempModulesDir, "kugou-music-rs").exists(), "旧模块目录必须被清理，避免重复冗余")
    }

    @Test
    fun importPackageWithLegacySha256InManifestSucceedsWithoutMismatch() {
        // 历史 manifest 中包含 sha256 字段的包不再因循环校验失败，而应兼容导入
        val legacyShaZip = createZip(
            "legacy_sha.zip",
            """{"id":"legacy-sha","name":"带SHA音源","type":"http","entryPoint":"http://127.0.0.1:8004","version":"1.0.0","sha256":"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"}"""
        )
        val result = moduleManager.importModuleDetails(legacyShaZip.absolutePath)
        assertNotNull(result, "包含历史 sha256 字段的包应成功导入，不应因包内自哈希自相矛盾而报错")
        assertEquals("legacy-sha", result.provider.id)
    }

    @Test
    fun updatingModuleWhileFilesLockedStagesPendingUpdateForRestart() {
        // 导入初始版本
        val v1Zip = createZip(
            "test_source_v1.zip",
            """{"id":"test-src","name":"测试音源","type":"http","entryPoint":"http://127.0.0.1:8005","version":"1.0.0"}""",
            extraFiles = mapOf("lib/lock.dll" to "locked bytes")
        )
        val r1 = moduleManager.importModuleDetails(v1Zip.absolutePath)
        assertNotNull(r1)
        assertEquals("1.0.0", r1.provider.version)

        // 模拟 Windows 上音源使用中：打开目录下的文件流锁定文件
        val targetFile = File(tempModulesDir, "test-src/lib/lock.dll")
        assertTrue(targetFile.exists())
        val lockStream = java.io.FileInputStream(targetFile)
        try {
            // 此时尝试更新模块（新版本 2.0.0）
            val v2Zip = createZip(
                "test_source_v2.zip",
                """{"id":"test-src","name":"测试音源","type":"http","entryPoint":"http://127.0.0.1:8006","version":"2.0.0"}""",
                extraFiles = mapOf("lib/lock.dll" to "new bytes")
            )
            val r2 = moduleManager.updateModuleDetails(v2Zip.absolutePath, "test-src")
            assertNotNull(r2, "即使文件被锁定无法立即覆盖，也应成功暂存为待重启更新")
            assertTrue(r2.pendingRestart, "应标记 pendingRestart 为 true")
            assertTrue(moduleManager.hasPendingRepositoryUpdate("test-src"), "应在 .repository-pending 登记待更新")
        } finally {
            lockStream.close()
        }

        // 模拟应用重启：重新初始化 ModuleManager
        val newManager = ModuleManager(tempModulesDir.absolutePath, context)
        newManager.init(providerManager)

        assertFalse(newManager.hasPendingRepositoryUpdate("test-src"), "重启后应自动应用并清除暂存记录")
        val updatedProvider = newManager.getProvider("test-src")
        assertNotNull(updatedProvider)
        assertEquals("2.0.0", updatedProvider.version, "重启后音源版本应已成功更新至 2.0.0")
    }
}