package cp.player.kmp.util

/**
 * 键值存储抽象（替代 Android SharedPreferences）。
 *
 * 每个平台提供 actual 实现：
 * - Android：SharedPreferences
 * - Desktop：内存 Map + 可选持久化
 *
 * 用于 cookie、最近 Provider ID、缓存元信息等。
 */
interface SettingsStorage {
    fun getString(key: String, default: String? = null): String?
    fun putString(key: String, value: String?)
    fun remove(key: String)
    fun contains(key: String): Boolean
    /** 清空全部（主要用于测试/重置） */
    fun clear()
}

/**
 * 平台提供的默认 [SettingsStorage] 工厂。
 * 由各平台 actual 提供（命名空间隔离通过 [namespace] 参数）。
 */
expect fun defaultSettingsStorage(namespace: String = "cp_player_prefs"): SettingsStorage