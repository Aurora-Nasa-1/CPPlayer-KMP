package cp.player.app.i18n

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
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
    fun `no string carries markdown or emphasis markers`() {
        // `SettingsNote` / `Text` **不解析** Markdown：`**加粗**` 会把星号原样显示在界面上。
        // 迁移时很容易照搬旧文案里的 `**…**`（本仓真发生过：局域网页的中英文都带着它，
        // 只有出图才看出来 —— 两条警告的粉色背景上飘着四个星号）。
        // 强调语义改用引号（中文「」/ 英文 ""）表达。
        // 注意两个收集器收的不是同一个东西：`strings` 是 `路径 → 文案` 的 Map，
        // 而 `collectParameterizedStrings` 要的是**被遍历的树根**（CpStrings 实例）——
        // 传错成 Map 会在递归里 NPE（默认参数 `out` 变成 null）。
        val locales = listOf<Pair<String, CpStrings>>("zh" to CpStringsZh, "en" to CpStringsEn)
        locales.forEach { (language, root) ->
            collectStrings(root).forEach { (path, value) ->
                BANNED_MARKS.forEach { mark ->
                    assertFalse(
                        value.contains(mark),
                        "[$language] $path 的文案里有 $mark —— 界面不解析 Markdown，星号会原样显示：$value",
                    )
                }
            }
            // 带参文案同样要查（警告语里那几条就是带参拼接出来的）。
            collectParameterizedStrings(root).forEach { (path, sample) ->
                BANNED_MARKS.forEach { mark ->
                    assertFalse(
                        sample.value.contains(mark),
                        "[$language] $path 的输出里有 $mark —— 界面不解析 Markdown，" +
                            "星号会原样显示：${sample.value}",
                    )
                }
            }
        }
    }

    /**
     * 带参数的文案函数必须**真的用到每一个参数**。
     *
     * `collectStrings` 只认无参成员（见下），所以 `fun deleted(name, freed)` 这类
     * 函数是测试盲区：实现里忘写一个参数（`"已删除 $freed"` 掉了 `name`）照样编译通过，
     * 界面上是一句没头没尾的话。这里给每个参数位发一个**可识别的哨兵**并咬住它 ——
     * 漏掉参数 = 输出里找不到那个哨兵 = 失败。
     *
     * 哨兵按参数序号取（`<P0>` / `<P1>` …）而不是固定几个值：不同函数的参数个数不同、
     * 类型也不同（`String` / 数字），按序号生成才能对任意签名通用。
     *
     * ⚠️ **数值参数允许参与算术**（`"${minutes + 1} 分钟后"` —— 向上取整到分钟是
     * 真实需求）。所以数值哨兵不按整串匹配，而是**输出里出现它的任一 3 位数字片段**
     * 就算用过：`12346` 里有 `123` ⇒ 判定通过；真写成常量 `"2 分钟前"` 则一个片段都没有。
     * 这个宽松是**有意的**：收紧就会把合法的算术误判成漏参。
     */
    @Test
    fun `parameterized strings use every argument`() {
        val locales = listOf("zh" to CpStringsZh, "en" to CpStringsEn)
        var checked = 0
        locales.forEach { (language, strings) ->
            collectParameterizedStrings(strings).forEach { (path, sample) ->
                // `labelOf` 这类「查表函数」的 else 分支**故意**原样返回入参
                // （未知档位显示成标识符本身，好过空白），输出等于哨兵是正确行为。
                if (sample.value == sample.sentinels.singleOrNull()) return@forEach
                sample.sentinels.forEach { sentinel ->
                    assertTrue(
                        sentinel.usedIn(sample.value),
                        "[$language] $path 的输出里找不到参数哨兵 $sentinel —— " +
                            "实现漏用参数会产出残缺句子（实际输出：${sample.value}）",
                    )
                }
                checked++
            }
        }
        assertTrue(checked > 0, "一个带参文案都没收集到 —— 反射遍历的结构变了？")
    }

    /**
     * `usedIn` 自己的自检 —— **守卫的守卫**。
     *
     * [parameterized strings use every argument] 为了容忍 `${minutes + 1}` 这类
     * 合法的算术而放宽了数值判定。放宽的代价是「可能漏报」，所以这里用一组
     * 正反例钉住它的边界：放宽只能放过**算术**，放过不了「根本没把参数写进去」。
     *
     * 没有这条的话，将来有人为了少写断言把 `usedIn` 简化成 `output.isNotEmpty()`，
     * 整个哨兵机制就静默退化成永真 —— 而它仍然「全绿」。
     */
    @Test
    fun `the sentinel check still rejects a dropped argument`() {
        // 正例：字符串哨兵原样出现 / 数值哨兵原样出现 / 数值参与算术（+1）。
        assertTrue("<P0>".usedIn("已删除 <P0> 的缓存"), "字符串哨兵出现时应判为「用上了」")
        assertTrue("12345".usedIn("共 12345 条"), "数值哨兵原样出现时应判为「用上了」")
        assertTrue("12345".usedIn("当前：12346 分钟后暂停"), "参与 +1 算术（向上取整）应判为「用上了」")
        assertTrue("12345".usedIn("还剩 123 分钟"), "参与 ÷100 之类的截断应判为「用上了」")

        // 反例：参数被写死成常量、或干脆没用 —— 这两种必须判为「漏用」。
        assertFalse("12345".usedIn("共 3 条"), "把数量写死成常量必须判为漏用")
        assertFalse("12345".usedIn("时间未知"), "完全没提到参数必须判为漏用")
        assertFalse("<P0>".usedIn("已删除该缓存"), "字符串参数被丢掉必须判为漏用")
        assertFalse("12345".usedIn(""), "空输出必然是漏用")
    }

    /**
     * 哨兵是否「被用上了」。
     *
     * 字符串哨兵按整串匹配；数值哨兵按**任一 3 位数字片段**匹配（容忍 `+ 1` 这类算术，
     * 理由见 [parameterized strings use every argument] 的说明）。
     *
     * ⚠️ 3 位窗口是刻意选的：短于 3 位的话 `"共 3 条"` 里那个 `3` 会被误判成
     * 「用了 12345」；长于 3 位则 `${minutes + 1}`（12346 vs 12345）匹配不上。
     */
    private fun String.usedIn(output: String): Boolean {
        if (isBlank()) return false
        if (output.contains(this)) return true
        val digits = filter { it.isDigit() }
        if (digits.length < 3) return false
        return digits.windowed(3).any { window -> output.contains(window) }
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

    /**
     * 递归调用所有「返回 `String` 且带参」的文案方法，收集它们的输出与各自的参数哨兵。
     *
     * 与 [collectStrings] 配对使用：那个只认无参成员（等价于 Kotlin 的 `val`），
     * 这个专门补上 `fun` 形态的**带参**文案（`fun deleted(name, freed)` 之类）。
     * 带参方法没法在 [collectStrings] 里表达 —— 它靠反射调用，必须给实参。
     *
     * 返回值是 `路径 → (输出, 该函数的哨兵列表)`。哨兵按参数序号取（`<P0>` / `<P1>` …）
     * 并**按类型**给不同的实参（字符串位给 `<Pn>`，数值位给 `12345` 这样的真数字，
     * 这样「把数量写死成常量」也会被发现）。
     *
     * 遇到不认识的参数类型就跳过该方法，而不是整棵树放弃 —— 加一种新参数类型时，
     * 这个测试会因为「少检查了成员」在覆盖率上自然体现，不会误报。
     */
    private fun collectParameterizedStrings(
        node: Any,
        prefix: String = "",
        out: MutableMap<String, ParameterizedSample> = linkedMapOf(),
    ): Map<String, ParameterizedSample> {
        for (method in node.javaClass.methods) {
            val name = method.name
            if (name in SKIPPED_METHODS) continue
            if (method.parameterCount == 0) {
                // 无参成员交给 collectStrings；这里只往下钻分组对象。
                val value = runCatching { method.invoke(node) }.getOrElse { continue }
                if (value is String || value is CpTextPair || value is Number || value is Boolean) continue
                collectParameterizedStrings(value, "$prefix.$name", out)
                continue
            }
            if (method.returnType != String::class.java) continue
            val sentinels = mutableListOf<String>()
            // 用显式循环而不是 `map`：需要在遇到未知参数类型时**放弃整个方法**
            // （`map` 里只能 `return@map` 跳过单个元素，那会让参数列表长度对不上）。
            val args = ArrayList<Any?>(method.parameterCount)
            var supported = true
            for (index in 0 until method.parameterCount) {
                val sentinel = "<P$index>"
                when (method.parameterTypes[index]) {
                    String::class.java -> {
                        sentinels += sentinel
                        args += sentinel
                    }
                    // 数值位用真数字：哨兵也能进字符串模板，但真数字能额外咬住
                    // 「把数量写死」这类错（`"共 2 GB"` 而不用参数）。
                    Integer.TYPE, java.lang.Long.TYPE -> {
                        sentinels += NUMBER_ARG
                        args += NUMBER_VALUE
                    }
                    java.lang.Boolean.TYPE -> args += true
                    else -> {
                        supported = false
                        break
                    }
                }
            }
            if (!supported) continue // 未知参数类型：跳过这一个方法
            val path = "$prefix.$name"
            val value = runCatching { method.invoke(node, *args.toTypedArray()) }.getOrNull() ?: continue
            out[path] = ParameterizedSample(value as String, sentinels)
        }
        return out
    }

    /** 一个带参文案的采样结果：输出本身 + 期望出现在输出里的哨兵。 */
    private data class ParameterizedSample(val value: String, val sentinels: List<String>)

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

        /**
         * 数值参数的实参。它同时也是哨兵 —— 输出里必须能找到它。
         *
         * 放 companion 而不是类体：`const val` 在普通类体里非法。
         */
        /**
         * 数值参数的实参。它同时也是哨兵 —— 输出里必须能找到它（的任一 3 位片段）。
         *
         * ⚠️ **为什么不是 12345**：文案层有一条「大数折成 1.2万 / 1.2k」的规则
         * （见 `zhCompactCount` / `enCompactCount`）。喂 12345 时输出是 `1.2万`，
         * 里面**没有一个 3 位数字片段**来自 12345 —— 于是「数量参与了计算、只是换了
         * 一种显示形式」这种**完全正确**的实现被判成漏参（真发生过：`totalCount`）。
         *
         * ⚠️ **为什么也不能是 123400**：它折成 `12.3万`，保留了 3 位但**不连续**，
         * 判据要的是**连续**的 3 位片段，照样误报。
         *
         * 取 `2222222`：任意连续 3 位都是 `222`，因此
         * - 折成 `222.2万`（中文）/ `2222.2k`（英文）后 `222` 仍在；
         * - 完全不折的 `2222222` / `2222222 首` / `2222222 ms` 更是原样命中。
         * 于是「格式化」与「不格式化」两条正确路径都能过，**不用改被守卫的代码**。
         *
         * 误报的代价远高于漏报这里可能漏掉的实现：连 `222` 都不出现的格式化，
         * 说明它根本没把数量当成数值用过（如 `"共 2 GB"` 之类的写死文案），
         * 正是这条守卫要抓的东西。
         */
        const val NUMBER_ARG = "2222222"
        const val NUMBER_VALUE = 2222222L

        /**
         * 文案里不该出现的标记。
         *
         * `SettingsNote` / `Text` 只渲染纯文本，不解析 Markdown（实锤见
         * `no string carries markdown or emphasis markers` 的说明）。
         * 反引号同理 —— 那是 KDoc 里的写法，混进文案就会原样显示。
         */
        val BANNED_MARKS = listOf("**", "`")
    }
}
