package cp.player.core.control

import cp.player.core.integration.IntegrationGate
import cp.player.core.integration.decideDataApiGate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 令牌规则与「媒体面 / 数据面共用同一条规则」的测试。
 *
 * ### 背景：这里修的是一个真实的洞
 * 媒体面 `/stream` 原本写的是 `if (!config.requiresToken) return true` —— 只要没配令牌就放行。
 * 而「绑定 `0.0.0.0` + 令牌为空」是一个**可达状态**（用户手工清掉令牌键、或配置文件被改），
 * 此时同网段任何人都能无限拉流。数据面则一直正确拒绝。同一个规则在两处各写一遍，
 * 就漂移成了这样。
 *
 * 所以现在规则只有一处实现：[isTokenSatisfied]。本文件钉两件事：
 * 1. 规则的每个分支都对；
 * 2. **两个面确实都用它**，而不是各自又写了一遍。
 *
 * ### 为什么第 2 点要用「读源码」的方式钉
 * 判定「非回环 + 无令牌 → 401」需要真的绑定 `0.0.0.0`。本仓库所有测试都刻意只绑回环
 * （避免 Windows 防火墙弹窗与 CI 抖动），所以这条分支没法用 HTTP 端到端覆盖。
 * 退而求其次：断言 `KtorLocalServer` **委托**给 [isTokenSatisfied]，
 * 且**不再出现**旧的短路写法 —— 这正是会回归的那一行。
 */
class MediaFaceAuthorizationTest {

    // ============ 规则本身 ============

    @Test
    fun `未配令牌时只有回环放行`() {
        val loopback = config(bindAddress = LocalServerConfig.BIND_LOOPBACK, token = "")
        val lan = config(bindAddress = LocalServerConfig.BIND_ALL, token = "")

        assertTrue(isTokenSatisfied(loopback, provided = null), "回环 + 无令牌：本机脚本应免配置")
        assertTrue(isTokenSatisfied(loopback, provided = ""), "空串等同未提供")
        assertTrue(isTokenSatisfied(loopback, provided = "随便什么"), "无令牌时不该因为提供了值而拒绝")

        // 这条是修复的核心：局域网裸奔必须拒绝，且**不可配置关闭**
        assertFalse(isTokenSatisfied(lan, provided = null), "非回环 + 无令牌：必须拒绝")
        assertFalse(isTokenSatisfied(lan, provided = ""), "非回环 + 空令牌：必须拒绝")
    }

    @Test
    fun `配了令牌就必须逐字匹配`() {
        for (bind in listOf(LocalServerConfig.BIND_LOOPBACK, LocalServerConfig.BIND_ALL)) {
            val cfg = config(bindAddress = bind, token = "secret")

            assertTrue(isTokenSatisfied(cfg, "secret"), "绑定 $bind：正确令牌应放行")
            assertFalse(isTokenSatisfied(cfg, "wrong"), "绑定 $bind：错误令牌应拒绝")
            assertFalse(isTokenSatisfied(cfg, null), "绑定 $bind：缺令牌应拒绝")
            assertFalse(isTokenSatisfied(cfg, ""), "绑定 $bind：空令牌应拒绝")
            // 大小写敏感：令牌是十六进制串，宽松比较会削弱熵
            assertFalse(isTokenSatisfied(cfg, "SECRET"), "绑定 $bind：令牌比较必须区分大小写")
        }
    }

    @Test
    fun `回环且配了令牌时仍然强制校验`() {
        // 「回环免令牌」只在**没配**令牌时成立。配了就必须校验 ——
        // 否则用户以为设了令牌有保护，实际本机任意进程都能直接拉流。
        val cfg = config(bindAddress = LocalServerConfig.BIND_LOOPBACK, token = "secret")
        assertFalse(isTokenSatisfied(cfg, null))
        assertTrue(isTokenSatisfied(cfg, "secret"))
    }

    // ============ 两面共用同一条规则 ============

    @Test
    fun `数据面与媒体面的令牌判定完全一致`() {
        val tokens = listOf("", "secret", "wrong", "SECRET")
        val provideds = listOf(null, "", "secret", "wrong")

        for (bind in listOf(LocalServerConfig.BIND_LOOPBACK, LocalServerConfig.BIND_ALL)) {
            for (token in tokens) {
                val cfg = config(bindAddress = bind, token = token).copy(exposeDataApi = true)
                for (provided in provideds) {
                    val mediaAllows = isTokenSatisfied(cfg, provided)
                    val dataAllows = decideDataApiGate(cfg, bearerToken = null, queryToken = provided) ==
                        IntegrationGate.ALLOW

                    // 数据面走的是同一个函数；这条断言的意义是「将来谁把其中一个改成内联实现，
                    // 两个面的行为漂移会立刻在这里暴露」，而不是碰巧相同。
                    assertEquals(
                        mediaAllows,
                        dataAllows,
                        "绑定=$bind 令牌='$token' 提供='$provided' 时两面判定不一致",
                    )
                }
            }
        }
    }

    @Test
    fun `数据面的 Bearer 与 query 回退顺序不变`() {
        val cfg = config(token = "secret").copy(exposeDataApi = true)

        assertEquals(IntegrationGate.ALLOW, decideDataApiGate(cfg, "secret", "wrong"), "Bearer 应优先")
        assertEquals(IntegrationGate.ALLOW, decideDataApiGate(cfg, null, "secret"), "Bearer 缺失时回退 query")
        // 空 Bearer 必须回退到 query，否则 `Authorization: Bearer ` 会遮蔽正确的 query 令牌
        assertEquals(IntegrationGate.ALLOW, decideDataApiGate(cfg, "", "secret"), "空 Bearer 应回退 query")
        assertEquals(IntegrationGate.UNAUTHORIZED, decideDataApiGate(cfg, "wrong", "secret"), "Bearer 错了不能放行")
    }

    // ============ 接线：媒体面确实委托，而不是自己重写 ============

    @Test
    fun `媒体面委托给共用规则而不是自己短路`() {
        val source = mediaFaceSource()
        val codeLines = source.readLines()
            .map { it.trim() }
            // 去掉注释：KDoc 里正当讨论「以前写的是 !config.requiresToken」是必要的
            .filterNot { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }

        assertTrue(
            codeLines.any { it.contains("isTokenSatisfied(") },
            "媒体面必须调用 isTokenSatisfied；否则它会与数据面漂移",
        )
        assertFalse(
            codeLines.any { it.contains("!config.requiresToken") },
            "媒体面不能再用「没配令牌就放行」的短路写法 —— 那正是局域网裸奔的成因",
        )
        assertFalse(
            codeLines.any { it.contains("== config.accessToken") },
            "令牌比较只能有一处实现；内联比较会让两面的判定漂移",
        )
    }

    // ============ 脚手架 ============

    private fun config(
        bindAddress: String = LocalServerConfig.BIND_LOOPBACK,
        token: String = "",
    ) = LocalServerConfig(
        enabled = true,
        bindAddress = bindAddress,
        streamPort = 8080,
        accessToken = token,
    )

    private fun mediaFaceSource(): File {
        val relative = "src/jvmMain/kotlin/cp/player/core/control/LocalServer.jvm.kt"
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            File(File(dir, "core"), relative).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        fail("找不到媒体面实现 $relative；工作目录 = ${File("").absolutePath}")
    }
}
