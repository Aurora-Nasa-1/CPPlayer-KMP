package cp.player.core.license

import cp.player.core.util.SettingsStorage
import cp.player.core.util.defaultSettingsStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * VIP / 发烧友特权许可证信息。
 */
data class VipLicenseInfo(
    val isActivated: Boolean,
    val licenseKey: String? = null,
    val planName: String = if (isActivated) "Hi-Res 专业发烧版 (永久授权)" else "免费标准版",
    val activatedAt: Long = 0L,
)

/**
 * 轻量级 VIP 授权与发烧特权管理器。
 *
 * 适用于前期低成本、免复杂服务器搭建的独立商业化方案：
 * - 支持离线算号校验与预置体验卡密兑换；
 * - 统一管控发烧后端（WASAPI Exclusive 独占、USB DAC 直通、DSD 源码输出）的特权准入；
 * - 后续对接支付平台（微信/支付宝/爱发电/Stripe）时，可直接平滑迁移为云端 API 验签。
 */
object VipLicenseManager {

    private const val KEY_VIP_ACTIVATED = "vip_license_activated"
    private const val KEY_VIP_KEY = "vip_license_key"
    private const val KEY_VIP_TIME = "vip_license_time"

    private var storage: SettingsStorage = defaultSettingsStorage("vip_license")

    private val _licenseState = MutableStateFlow(loadLicenseFromStorage())
    val licenseState: StateFlow<VipLicenseInfo> = _licenseState.asStateFlow()

    val isVipActive: Boolean
        get() = _licenseState.value.isActivated

    fun init(customStorage: SettingsStorage? = null) {
        if (customStorage != null) {
            storage = customStorage
            _licenseState.value = loadLicenseFromStorage()
        }
    }

    private fun loadLicenseFromStorage(): VipLicenseInfo {
        val activated = storage.getString(KEY_VIP_ACTIVATED) == "true"
        val key = storage.getString(KEY_VIP_KEY)
        val time = storage.getString(KEY_VIP_TIME)?.toLongOrNull() ?: 0L
        return VipLicenseInfo(
            isActivated = activated,
            licenseKey = key,
            activatedAt = time,
        )
    }

    /**
     * 校验并激活卡密。
     */
    fun activate(inputKey: String): Result<String> {
        val trimmed = inputKey.trim().uppercase()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("激活码不能为空"))
        }

        if (!verifyOfflineLicenseKey(trimmed)) {
            return Result.failure(IllegalArgumentException("无效的激活码，请检查输入或联系开发者获取"))
        }

        val now = System.currentTimeMillis()
        storage.putString(KEY_VIP_ACTIVATED, "true")
        storage.putString(KEY_VIP_KEY, trimmed)
        storage.putString(KEY_VIP_TIME, now.toString())

        val newInfo = VipLicenseInfo(
            isActivated = true,
            licenseKey = trimmed,
            activatedAt = now,
        )
        _licenseState.value = newInfo
        return Result.success("恭喜！已成功激活 Hi-Res 专业发烧版特权！")
    }

    /** 撤销/重置激活状态（方便测试或解绑） */
    fun deactivate() {
        storage.putString(KEY_VIP_ACTIVATED, "false")
        storage.putString(KEY_VIP_KEY, "")
        storage.putString(KEY_VIP_TIME, "0")
        _licenseState.value = VipLicenseInfo(isActivated = false)
    }

    /**
     * 离线卡密校验算法（防小白篡改）：
     * 格式形如：`CP88-XXXX-YYYY-ZZZZ`，要求段落字符 ASCII 累加和符合特定的模校验。
     */
    private fun verifyOfflineLicenseKey(key: String): Boolean {
        val parts = key.split("-")
        if (parts.size != 4) return false
        if (!parts[0].startsWith("CP")) return false

        val sum = parts.joinToString("").sumOf { it.code }
        return sum % 17 == 0
    }
}
