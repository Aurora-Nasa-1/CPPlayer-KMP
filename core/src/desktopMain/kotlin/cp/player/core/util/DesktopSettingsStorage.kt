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
}

actual fun defaultSettingsStorage(namespace: String): SettingsStorage = DesktopSettingsStorage(namespace)