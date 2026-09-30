package cp.player.core.util

import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * Desktop [SettingsStorage]：内存 Map + 简单 Properties 文件持久化。
 *
 * [namespace] 映射到 `~/.cpplayer/<namespace>.properties`（旧目录名 `.kmp-pro` 见 [DesktopDataDir]）。
 */
class DesktopSettingsStorage(
    namespace: String = "cp_player_prefs"
) : SettingsStorage {
    private val store: MutableMap<String, String> = ConcurrentHashMap()
    private val file = DesktopDataDir.file("$namespace.properties")

    init {
        if (file.exists()) {
            try {
                Properties().apply { load(file.inputStream()) }
                    .forEach { k, v -> store[k.toString()] = v.toString() }
            } catch (_: Exception) { /* 持久化失败不阻塞 */ }
        }
    }

    private fun persist() {
        try {
            file.parentFile?.mkdirs()
            Properties().apply {
                store.forEach { (k, v) -> setProperty(k, v) }
                store(file.outputStream(), null)
            }
        } catch (_: Exception) { /* 持久化失败不阻塞 */ }
    }

    override fun getString(key: String, default: String?): String? = store[key] ?: default
    override fun putString(key: String, value: String?) {
        if (value == null) store.remove(key) else store[key] = value
        persist()
    }
    override fun remove(key: String) { store.remove(key); persist() }
    override fun contains(key: String): Boolean = store.containsKey(key)
    override fun clear() { store.clear(); persist() }

    companion object {
        /**
         * 按 **(数据目录, namespace)** 缓存的共享实例。
         *
         * ### 为什么必须有它
         *
         * [persist] 是**全量回写**：把本实例内存 Map 里的全部键写进文件。所以同一个
         * namespace 上存在两个实例时，两者各自持有一份快照，**后写者会用陈旧快照覆盖
         * 先写者的改动**。
         *
         * 修之前，`cp_player_prefs` 上同时有三个写者：
         * `AppModel.settings`（每次访问新建）、`MusicBackend` 注入的那个（`Main.kt`）、
         * 以及 `DesktopRenderTuning` 的 lazy 实例。典型症状是
         * 「改完主题 → 去渲染后端页动一下垂直同步 → 主题被回退」。
         *
         * ### 为什么 key 里带数据目录
         *
         * `DesktopDataDir.PROP_HOME` 是测试隔离点，每个用例都会把用户目录重定向到新的临时
         * 目录。只按 namespace 缓存会让上一个用例的实例（`file` 指向旧目录）泄漏进来，
         * 于是「写盘写到了别的用例的目录」—— 测试会以极难排查的方式失败。
         * 把目录并进 key，生产环境（目录恒定）得到一个单例，测试之间仍然完全隔离。
         *
         * 直接 `DesktopSettingsStorage(namespace)` 构造**不走**这个缓存，供测试与需要
         * 独立快照的场景使用。
         */
        private val shared = ConcurrentHashMap<String, DesktopSettingsStorage>()

        internal fun shared(namespace: String): DesktopSettingsStorage {
            val key = "${DesktopDataDir.root().absolutePath}|$namespace"
            return shared.getOrPut(key) { DesktopSettingsStorage(namespace) }
        }
    }
}

actual fun defaultSettingsStorage(namespace: String): SettingsStorage =
    DesktopSettingsStorage.shared(namespace)