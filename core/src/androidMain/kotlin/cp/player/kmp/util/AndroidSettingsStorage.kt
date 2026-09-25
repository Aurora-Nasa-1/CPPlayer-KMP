package cp.player.kmp.util

import android.content.Context

/**
 * Android [SettingsStorage] 基于 SharedPreferences。
 * [namespace] 用作 SharedPreferences 文件名。
 */
class AndroidSettingsStorage(
    private val context: Context,
    namespace: String = "cp_player_prefs"
) : SettingsStorage {
    private val prefs = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)

    override fun getString(key: String, default: String?): String? = prefs.getString(key, default)
    override fun putString(key: String, value: String?) = prefs.edit().apply {
        if (value == null) remove(key) else putString(key, value)
    }.apply()
    override fun remove(key: String) = prefs.edit().remove(key).apply()
    override fun contains(key: String): Boolean = prefs.contains(key)
    override fun clear() = prefs.edit().clear().apply()
}

/** Android 默认存储工厂：使用应用 Context（由调用方注入）。 */
private var appContext: Context? = null

/** 在 Application.onCreate 中注入应用级 Context，供默认存储使用。 */
fun initKmpAndroidContext(context: Context) {
    if (appContext == null) appContext = context.applicationContext
}

actual fun defaultSettingsStorage(namespace: String): SettingsStorage {
    val ctx = appContext ?: error("Call initKmpAndroidContext(context) in Application.onCreate() first")
    return AndroidSettingsStorage(ctx, namespace)
}