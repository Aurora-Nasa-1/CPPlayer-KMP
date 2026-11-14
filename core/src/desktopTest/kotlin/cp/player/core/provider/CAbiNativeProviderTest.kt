package cp.player.core.provider

import cp.player.core.provider.typed.cAbiSessionFactory
import cp.player.core.provider.typed.createSdkRuntime
import cp.player.sdk.Bytes
import cp.player.sdk.Capabilities
import cp.player.sdk.ProviderContext
import cp.player.sdk.ProviderProtocol
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C ABI（`cpp_*`）宿主侧回归 —— 规范见 `docs/dev/providers/NATIVE.md`。
 *
 * 注意分工：生产路径里 [cAbiSessionFactory] 只在 **GraalVM Native Image** 构建被调用
 * （JVM / Android 一律走 JNI），所以这个用例是 FFM 层在 JVM 上的**唯一**覆盖 ——
 * 它直接验 create/invoke/cancel/close 的搬运，不经过 `InstalledNativeSdkProvider` 的分流。
 *
 * 主体用例需要一个真的导出了 `cpp_*` 的库（本仓库示例：`reference/netease-module-rust`）：
 *
 * ```
 * cargo build --release --features jni          # 在 reference/netease-module-rust
 * CP_CABI_FIXTURE=<...>/ncm_api_rs.dll ./gradlew :core:desktopTest --tests '*CAbiNativeProviderTest*'
 * ```
 *
 * 没有该环境变量时只跑「非原生库必须安全回落」这一条（与 `NativeSdkInstallationTest`
 * 处理 `cp.native.fixture` 的方式一致）。
 */
class CAbiNativeProviderTest {

    private val fixture: File? = System.getenv("CP_CABI_FIXTURE")?.takeIf { it.isNotBlank() }?.let(::File)

    @Test
    fun `libraries without the c abi are ignored instead of throwing`() {
        val bogus = Files.createTempFile("cabi-bogus", ".dll").toFile()
        bogus.writeBytes(ByteArray(64) { it.toByte() })
        try {
            // JVM 上必须安静地回落到 JNI 路径；抛异常就意味着导入会被整包判死。
            assertNull(cAbiSessionFactory(bogus), "非原生库应返回 null，由调用方回落 JNI")
        } finally {
            bogus.delete()
        }
    }

    @Test
    fun `c abi library opens independent sessions`() {
        val library = fixture
        if (library == null) {
            println("[skip] CP_CABI_FIXTURE 未设置，跳过 C ABI 端到端用例")
            return
        }
        val runtime = createSdkRuntime(emptySet())
        // 两份工厂 = 两个独立的库句柄：这正是「同一符号名不同包互不影响」的宿主侧保障
        // （JNI 那边靠 defineClass，native image 里不可能，只能靠每包一个 SymbolLookup）。
        val first = assertNotNull(cAbiSessionFactory(library), "库未导出 cpp_abi_version: $library")
        val second = assertNotNull(cAbiSessionFactory(library), "同一库的第二份句柄必须能独立打开")
        // providerId 只是会话标识，CAPABILITIES 不联网，用它验证 create/invoke 的整条搬运链。
        val context = ProviderContext("cabi-probe", "account-1", emptyMap(), null, runtime)
        val firstSession = first.open(context, "{}")
        val secondSession = second.open(context, "{}")
        try {
            val capabilities =
                firstSession.invoke(ProviderProtocol.CAPABILITIES, Bytes(ByteArray(0))) as Capabilities
            assertTrue(capabilities.supported.isNotEmpty(), "能力表不应为空: ${capabilities.supported}")
            secondSession.invoke(ProviderProtocol.CAPABILITIES, Bytes(ByteArray(0)))
            firstSession.cancel()
            // close 必须幂等：宿主在 close 前后各会调一次（见 NativePackageRuntime）。
            firstSession.close()
            firstSession.close()
            secondSession.close()
        } finally {
            first.close()
            second.close()
        }
    }
}
