package cp.player.app.auth

/**
 * 粘贴式 Cookie 登录的输入清洗。
 *
 * 用户手里那串 cookie 的形态五花八门，直接塞进 `cookie_<providerId>` 会被 Provider
 * 当成畸形 cookie 发出去，表现是「明明粘贴了却提示未登录」。常见来源：
 *
 * - `MUSIC_U=xxx; __csrf=yyy` —— 最干净，直接用
 * - `Cookie: MUSIC_U=xxx; __csrf=yyy` —— 从 DevTools 的 Request Headers **整行**复制
 * - 从「响应标头」复制的 `set-cookie:` 行，或带换行 / 制表符的多行文本
 * - 从教程复制来的、带中文说明或多余空格的串
 *
 * 所以统一在入口清洗一次：去掉 `Cookie:` / `set-cookie:` 前缀、把换行与制表符折成
 * `; `、丢掉不含 `=` 的碎片、按名字去重（**保留最后一个**，后写的通常才是新的）。
 *
 * ⚠️ **不按逗号切分**。`set-cookie` 之间确实用逗号分隔，但 `Expires=Wed, 21 Oct 2015`
 * 的值里本身就带逗号 —— 一切就把日期切成两半。多出来的属性段（`Expires` / `Path` /
 * `Domain`）服务端本来就会忽略，留着无害；切错了才是真出问题。
 */
object CookieLogin {

    /**
     * 会话字段名（比较时大小写不敏感）。
     *
     * 只用来给用户一个**软提示**（「这串东西里没有常见会话字段，可能复制错了」），
     * 不参与放行判断 —— 不同音源的会话字段名不一样，硬性要求必然误杀。
     */
    private val SESSION_KEYS = listOf("MUSIC_U", "MUSIC_A", "__CSRF", "JSESSIONID-WYYY")

    /** 正常 cookie 几百字节；超过这个量级基本是整段请求粘进来了。 */
    private const val MAX_LENGTH = 8 * 1024

    private val HEADER_PREFIX = Regex("(?i)^(set-)?cookie\\s*:")
    private val LINE_BREAKS = Regex("[\\r\\n\\t]+")

    /**
     * 清洗用户粘贴的 cookie。
     *
     * @return 规范化的 `k=v; k=v` 串；一个 `k=v` 都解析不出来时返回 `null`
     *   （调用方据此禁用提交按钮，而不是把垃圾写进存储）。
     */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_LENGTH) return null

        val text = trimmed
            .replace(HEADER_PREFIX, " ")
            .replace(LINE_BREAKS, "; ")

        // LinkedHashMap：既保序，又让同名键「后者覆盖前者」且位置稳定。
        val pairs = LinkedHashMap<String, String>()
        for (segment in text.split(';')) {
            val part = segment.trim().trim('"').trim()
            val eq = part.indexOf('=')
            // eq == 0 ⇒ 名字为空；eq < 0 ⇒ 这段没有 `=`（说明文字、空片段）
            if (eq <= 0) continue
            val name = part.substring(0, eq).trim()
            val value = part.substring(eq + 1).trim()
            // 名字里带空白 ⇒ 多半把整句说明粘进来了，宁可丢掉
            if (name.isEmpty() || value.isEmpty() || name.any { it.isWhitespace() }) continue
            pairs[name] = value
        }
        if (pairs.isEmpty()) return null
        return pairs.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** 清洗后的 cookie 里是否含常见的会话字段（软提示用，不作放行条件）。 */
    fun hasSessionKey(normalized: String): Boolean =
        normalized.split(';').any { segment ->
            val name = segment.substringBefore('=').trim()
            SESSION_KEYS.any { it.equals(name, ignoreCase = true) }
        }

    /**
     * 展示用的脱敏形态：只留字段名 + 值的前 4 个字符 + 原长度。
     *
     * 登录态等同于密码，明文回显在界面上（尤其截图 / 录屏时）没有必要。
     */
    fun mask(normalized: String): String =
        normalized.split(';').joinToString("; ") { segment ->
            val name = segment.substringBefore('=').trim()
            val value = segment.substringAfter('=', "").trim()
            if (name.isEmpty() || value.isEmpty()) segment
            else "$name=${value.take(4)}…(${value.length})"
        }
}
