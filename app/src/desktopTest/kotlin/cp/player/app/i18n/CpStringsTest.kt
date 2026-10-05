package cp.player.app.i18n

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * 文案完整性守卫。
 *
 * ### 为什么需要它
 *
 * 接口方案下「漏翻译」已经是编译不过 —— 但**编译期兜不住空字符串**：
 * `override val foo = ""` 完全合法，界面上是一处空白，CI 不会说一个字。
 * 两份实现是手写的，这种事迟早发生。
 *
 * ### 为什么用反射而不是手列举
 *
 * 手列举一份 cases 清单＝第三份要和两个实现同步的东西 —— 加了文案忘了往清单里补，
 * 这个测试就会「通过得毫无意义」。反射遍历让「新加的成员自动被检查」。
 *
 * ⚠️ 只在 desktopTest（JVM）里用 `java` 反射，这是它能遍历成员的前提；
 * commonTest 里没有这层能力，别把这套挪过去。
 */
class CpStringsTest {

    private val locales = listOf(
        "zh" to CpStringsZh,
        "en" to CpStringsEn,
    )

    @Test
    fun `no string is blank`() {
        locales.forEach { (language, strings) ->
            val collected = collectStrings(strings)
            assertTrue(collected.isNotEmpty(), "[$language] 一条文案都没收集到 —— 反射遍历的结构变了？")
            collected.forEach { (path, value) ->
                assertTrue(
                    value.isNotBlank(),
                    "[$language] $path 是空文案（界面上会是一片空白，而编译期查不出来）",
                )
            }
        }
    }

    @Test
    fun `both languages expose the same paths`() {
        // 接口保证了「成员存在」，但保证不了两份实现的**嵌套结构**一致
        // （例如把某个字段从 interface 成员改成匿名实现的游离属性，
        //  就会有一份忘了跟着改）。比一下路径集合即可。
        val (_, zhPaths) = "zh" to collectStrings(CpStringsZh).keys
        val (_, enPaths) = "en" to collectStrings(CpStringsEn).keys
        assertTrue(zhPaths.isNotEmpty() && enPaths.isNotEmpty())
        zhPaths.forEach { path -> assertContains(enPaths, path, "英文文案缺少 $path") }
        enPaths.forEach { path -> assertContains(zhPaths, path, "中文文案缺少 $path") }
    }

    @Test
    fun `of() resolves explicit languages deterministically`() {
        assertTrue(CpStrings.of(AppLanguage.ZH_HANS) === CpStrings.zh, "显式选中文必须拿到中文实例")
        assertTrue(CpStrings.of(AppLanguage.ENGLISH) === CpStrings.en, "显式选英文必须拿到英文实例")
    }

    @Test
    fun `of() resolves SYSTEM to a usable instance`() {
        // 系统语言在 CI / 各人机器上不一样，这里不断言具体是哪一种，
        // 只断言「必然落在受支持的两份实例之一」—— 落到别的对象上说明兜底分支写坏了。
        val resolved = CpStrings.of(AppLanguage.SYSTEM)
        assertTrue(
            resolved === CpStrings.zh || resolved === CpStrings.en,
            "跟随系统时解析出了一份未知实例：${resolved::class.qualifiedName}",
        )
    }

    @Test
    fun `unknown storage key falls back to SYSTEM`() {
        assertTrue(
            AppLanguage.ofStorageKey("完全不知道的值") === AppLanguage.SYSTEM,
            "无法识别的持久化值必须回落到跟随系统，不能抛异常",
        )
        assertTrue(AppLanguage.ofStorageKey(null) === AppLanguage.SYSTEM)
        assertTrue(AppLanguage.ofStorageKey("en") === AppLanguage.ENGLISH)
        assertTrue(AppLanguage.ofStorageKey("zh-Hans") === AppLanguage.ZH_HANS)
    }

    // -----------------------------------------------------------------------

    /**
     * 递归收集一棵文案树里的所有字符串，键是「便于定位的访问路径」。
     *
     * 遍历规则：只认**无参方法**（等价于 Kotlin 的 val 成员），按返回值类型分三种处理：
     * - [String] → 收集；
     * - [CpTextPair] → 展开成 `<path>.title` / `<path>.subtitle`；
     * - 其它对象 → 当作子节点递归（对应 `val language` / `val settings` 这类分组）。
     */
    private fun collectStrings(node: Any, prefix: String = ""): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (method in node.javaClass.methods) {
            if (method.parameterCount != 0) continue
            val name = method.name
            if (name in SKIPPED_METHODS) continue
            val path = if (prefix.isEmpty()) name else "$prefix.$name"
            @Suppress("UNCHECKED_CAST")
            when (val value = runCatching { method.invoke(node) }.getOrElse { continue }) {
                is String -> out[path] = value
                is CpTextPair -> {
                    out["$path.title"] = value.title
                    out["$path.subtitle"] = value.subtitle
                }
                is Int, is Long, is Boolean, is Float, is Double -> Unit // 非文案成员，跳过
                else -> out.putAll(collectStrings(value, path))
            }
        }
        return out
    }

    private companion object {
        // JVM 对象自带的方法（`getClass` / `hashCode` …）与 Kotlin 的合成方法，不是文案
        val SKIPPED_METHODS = setOf(
            "getClass",
            "hashCode",
            "toString",
            "component1",
            "component2",
            "copy",
            "getEntries",
            "INSTANCE",
        )
    }
}
