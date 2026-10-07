package cp.player.app.auth

import cp.player.app.AppModel
import cp.player.core.util.SettingsStorage
import cp.player.core.util.currentTimeMillis
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 一次「还没扫完」的扫码登录现场。
 *
 * ### 为什么需要它
 *
 * 扫码登录的现场原先**全在内存里**（`AccountScreenModel` 的 `qrJob` 加几个 `StateFlow`）：
 * 用户举着手机去扫码，本应用退到后台被系统回收（国产 ROM 上这几乎是必然）——
 * 再打开时进程是新的，二维码、key、轮询一起消失。用户看到的是一张**全新的二维码**，
 * 手机上刚确认的那次登录就此作废，只能重扫。
 *
 * 这里把现场落盘（取到二维码时写一份，登录成功 / 登出 / 换号时清掉）。下次进登录页
 * 时若还在 [QrLoginStore.QR_SESSION_TTL_MS] 窗口内，就**接着这张二维码继续轮询**，
 * 而不是重新申请一张。
 *
 * 顺带修好一个更隐蔽的丢失：用户扫完并在手机上点了确认（服务端已是 `803`），
 * 但本进程在拿到 cookie 之前被杀 —— cookie 从来没落盘，这次登录白做。
 * 恢复之后的第一轮轮询就会读到 `803` 并把 cookie 补上，登录自动完成。
 *
 * ### 边界
 *
 * - 键里带 `providerId` ⇒ **按音源隔离**，与 [AccountStore] 一个规矩，不串台。
 * - 服务端的二维码 key 本身有寿命（约 5 分钟）。超过 [QrLoginStore.QR_SESSION_TTL_MS]
 *   的现场恢复出来也一定是过期的 ⇒ 直接丢掉、重新取一张，别让用户看到一张死二维码。
 * - [imageBase64] 只在「保存二维码」时用得到，过大就不存（别把偏好文件撑大）。
 */
@Serializable
data class QrLoginSession(
    /** 服务端二维码 key：轮询 `login/qr/check` 用它，恢复现场的关键就是这个。 */
    val key: String,
    /** 二维码内容 URL —— 界面是**本地出图**（qrose），所以恢复现场只要有它就够画出二维码。 */
    val url: String,
    /** 服务端回的二维码图（base64）。仅「保存二维码」用；超长 / 取不到时为 null。 */
    val imageBase64: String? = null,
    /** 创建时刻（毫秒），[isFresh] 的判据。 */
    val createdAtMs: Long,
) {
    /**
     * 这份现场是否还在可恢复窗口内。
     *
     * 时钟回拨（`nowMs < createdAtMs`）也判为**不可恢复** —— 负差值的成因说不清，
     * 宁可重新要一张二维码，也别拿一个来路不明的年龄去开 5 分钟的窗口。
     */
    fun isFresh(nowMs: Long, ttlMs: Long = QrLoginStore.QR_SESSION_TTL_MS): Boolean =
        nowMs - createdAtMs in 0..ttlMs

    companion object {
        /**
         * 二维码图落盘上限（字符数）。服务端的 `qrimg` 是一张几百 px 的 PNG base64，
         * 正常在 2–6 KB；真超过这个量级多半是异常响应，宁可不存 —— 它只是「保存二维码」
         * 按钮的料，缺了不影响恢复登录。
         */
        const val MAX_IMAGE_CHARS = 16_000
    }
}

/**
 * 扫码登录现场的落盘 / 恢复（键：`qr_login_<providerId>`，与 [AccountStore] 同规矩）。
 *
 * ⚠️ `SettingsStorage` 参数带默认值只是为了让桌面单测能塞一个内存实现 ——
 * 生产调用一律走默认的 [AppModel.settings]，别在业务代码里显式传。
 */
object QrLoginStore {

    /**
     * 现场的有效期。
     *
     * 服务端的二维码 key 大约 5 分钟失效，而客户端单轮最长轮询 4 分钟（见
     * `QR_POLL_MAX`）—— 取 5 分钟与之对齐：比这更老的现场恢复出来必然是 800，
     * 恢复它只会让用户白等一轮重新取图。
     */
    const val QR_SESSION_TTL_MS = 5 * 60 * 1000L

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun keyOf(providerId: String) = "qr_login_$providerId"

    /** 记下一次现场（取到二维码 / 恢复时都调；[QrLoginSession.url] 为空则不写）。 */
    fun save(providerId: String, session: QrLoginSession, store: SettingsStorage = AppModel.settings) {
        if (providerId.isBlank() || session.key.isBlank() || session.url.isBlank()) return
        val capped = session.copy(
            imageBase64 = session.imageBase64?.takeIf { it.length <= QrLoginSession.MAX_IMAGE_CHARS },
        )
        store.putString(keyOf(providerId), json.encodeToString(capped))
    }

    /**
     * 读一份还能用的现场；没有、解不出来、或者已经超出 [QR_SESSION_TTL_MS] 都返回 null。
     *
     * 过期的条目**顺手清掉**：留着它唯一的后果就是「每次开机都解析一遍又丢掉」。
     */
    fun load(
        providerId: String,
        store: SettingsStorage = AppModel.settings,
        nowMs: Long = currentTimeMillis(),
    ): QrLoginSession? {
        if (providerId.isBlank()) return null
        val raw = store.getString(keyOf(providerId)) ?: return null
        val session = runCatching { json.decodeFromString<QrLoginSession>(raw) }.getOrNull()
        if (session == null || session.url.isBlank() || !session.isFresh(nowMs)) {
            // 解不出来 = 存量脏数据；过期 = 再用也一定是 800。两种都别留。
            store.remove(keyOf(providerId))
            return null
        }
        return session
    }

    /** 清掉现场（登录成功 / 登出 / 换号 / 二维码已作废时调）。 */
    fun clear(providerId: String, store: SettingsStorage = AppModel.settings) {
        if (providerId.isBlank()) return
        store.remove(keyOf(providerId))
    }

    /**
     * 有没有「上次没扫完、现在还能接着扫」的现场 —— 启动提示的判据。
     *
     * `load` 已经替我们把过期与脏数据清掉了，所以这里**只读不写**多余状态：
     * 「一个进程只提示一次」是 UI 的节奏问题，由调用方（`MainScreen`）自己拿一个
     * 进程级标记管，别塞进存储层（那样单测之间会互相消耗掉机会，见 QrLoginStoreTest）。
     */
    fun hasPending(
        providerId: String,
        store: SettingsStorage = AppModel.settings,
        nowMs: Long = currentTimeMillis(),
    ): Boolean = load(providerId, store, nowMs) != null
}
