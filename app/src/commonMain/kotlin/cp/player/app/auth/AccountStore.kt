package cp.player.app.auth

import cp.player.app.AppModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 按音源保存的多账号存储（旧版 `UserPreferences.savedAccounts*` 的对应实现）。
 *
 * **隔离规则**：每个音源一份账号列表（`saved_accounts_<providerId>`）+ 一个当前账号
 * 标记（`active_account_<providerId>`）。A 音源的账号绝不会出现在 B 音源的列表里，
 * 也不会被 B 音源的登录/登出操作改动 —— 这就是「音源隔离」在账号这一层的落地。
 *
 * Cookie 本身仍由 `ProviderCookieStorage`（key = `cookie_<providerId>`）保管：
 * 那里只存**当前生效**的那一个，切换账号时由本类把目标账号的 cookie 写回去。
 */
object AccountStore {

    @Serializable
    data class SavedAccount(
        val uid: String,
        val nickname: String,
        val avatarUrl: String = "",
        /** 该账号自己的登录态；切换回来时直接写回 Cookie 存储，无需重新扫码。 */
        val cookie: String = "",
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun listKey(providerId: String) = "saved_accounts_$providerId"
    private fun activeKey(providerId: String) = "active_account_$providerId"

    /** 当前音源下已保存的账号（按保存顺序）。 */
    fun list(providerId: String): List<SavedAccount> = runCatching {
        val raw = AppModel.settings.getString(listKey(providerId)) ?: return emptyList()
        json.decodeFromString<List<SavedAccount>>(raw)
    }.getOrDefault(emptyList())

    /** 当前音源的登录账号 uid（未登录为 null）。 */
    fun activeUid(providerId: String): String? =
        AppModel.settings.getString(activeKey(providerId))?.takeIf { it.isNotBlank() }

    /** 新登录成功：按 uid 覆盖写入并标记为当前账号。 */
    fun save(providerId: String, account: SavedAccount) {
        val current = list(providerId)
        val merged = (listOf(account) + current.filterNot { it.uid == account.uid }).take(8)
        putList(providerId, merged)
        setActive(providerId, account.uid)
    }

    /** 标记当前账号；传 null 表示该音源当前未登录。 */
    fun setActive(providerId: String, uid: String?) {
        if (uid == null) AppModel.settings.remove(activeKey(providerId))
        else AppModel.settings.putString(activeKey(providerId), uid)
    }

    /** 移除某个已保存账号；若它正是当前账号，登录态一并清掉。 */
    fun remove(providerId: String, uid: String) {
        putList(providerId, list(providerId).filterNot { it.uid == uid })
        if (activeUid(providerId) == uid) {
            AppModel.settings.remove(activeKey(providerId))
            AppModel.cookieStorage.clear(providerId)
        }
    }

    private fun putList(providerId: String, accounts: List<SavedAccount>) {
        if (accounts.isEmpty()) AppModel.settings.remove(listKey(providerId))
        else AppModel.settings.putString(listKey(providerId), json.encodeToString(accounts))
    }
}
