package cp.player.core.license

import cp.player.core.util.SettingsStorage
import cp.player.core.util.defaultSettingsStorage
import cp.player.core.util.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * VIP / 发烧友特权许可证信息。
 */
data class VipLicenseInfo(
    val isActivated: Boolean,
    val licenseKey: String? = null,
    val planName: String = if (isActivated) "Hi-Res 专业发烧版" else "免费标准版",
    val activatedAt: Long = 0L,
    val licenseType: String = "none",
    val expiresAtEpochSeconds: Long? = null,
)

/**
 * Persists the issuer-signed entitlement and revalidates it on every load.
 */
object VipLicenseManager {

    private const val KEY_VIP_KEY = "flick_vip_signed_license"

    private var storage: SettingsStorage = defaultSettingsStorage("vip_license")
    private val scope = CoroutineScope(Dispatchers.Main)
    private var maintenance: Job? = null
    private var grantToken: String? = storage.getString(KEY_VIP_KEY)?.takeUnless(::isCloudCard)
    private var revision = 0
    private var deadline: TimeMark? = null

    private val _licenseState = MutableStateFlow(loadLicenseFromStorage())
    val licenseState: StateFlow<VipLicenseInfo> = _licenseState.asStateFlow()

    init {
        deadline = expiryDeadline(_licenseState.value)
        monitor(storage.getString(KEY_VIP_KEY))
    }

    val isVipActive: Boolean
        get() = _licenseState.value.let {
            it.isActivated && it.expiresAtEpochSeconds?.let { expiry ->
                currentTimeMillis() / 1000 < expiry && deadline?.hasPassedNow() == false
            } != false
        }

    internal fun signedToken(): String? = if (isVipActive) grantToken else null

    fun init(customStorage: SettingsStorage? = null) {
        if (customStorage != null) {
            revision++
            storage = customStorage
            val card = storage.getString(KEY_VIP_KEY)
            grantToken = card?.takeUnless(::isCloudCard)
            publish(grantToken, loadLicenseFromStorage())
            monitor(card)
        }
    }

    private fun loadLicenseFromStorage(): VipLicenseInfo {
        return grantToken?.let(::verifyFlickVipLicense)?.takeUnless { it.licenseType == "cloud" }
            ?: VipLicenseInfo(isActivated = false)
    }

    /**
     * 校验并激活卡密。
     */
    suspend fun activate(inputKey: String): Result<String> {
        val trimmed = inputKey.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("激活码不能为空"))
        }

        val attempt = ++revision
        val cloud = isCloudCard(trimmed)
        val token = if (cloud) {
            try {
                fetchFlickCloudLease(trimmed)
                    ?: return Result.failure(IllegalArgumentException("云控卡已停用、已过期或无效"))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return Result.failure(IllegalStateException("无法连接云控授权服务，请检查网络和安装包配置"))
            }
        } else trimmed
        val newInfo = if (cloud) verifyCloudGrant(token, trimmed)
            else verifyFlickVipLicense(token)?.takeUnless { it.licenseType == "cloud" }
        if (attempt != revision) return Result.failure(IllegalStateException("授权已变更，请重试"))
        val validInfo = newInfo
            ?: return Result.failure(IllegalArgumentException("无效的签名授权码，请检查输入或联系发证方"))
        storage.putString(KEY_VIP_KEY, trimmed)
        publish(token, validInfo)
        monitor(trimmed, initialGrant = cloud)
        return Result.success("恭喜！已成功激活 Hi-Res 专业发烧版特权！")
    }

    /** 撤销/重置激活状态（方便测试或解绑） */
    fun deactivate() {
        revision++
        storage.remove(KEY_VIP_KEY)
        maintenance?.cancel()
        publish(null, null)
    }

    private fun publish(token: String?, info: VipLicenseInfo?) {
        if (info != null && info == _licenseState.value && token == grantToken) return
        grantToken = token
        deadline = info?.let(::expiryDeadline)
        _licenseState.value = info ?: VipLicenseInfo(isActivated = false)
    }

    private fun expiryDeadline(info: VipLicenseInfo): TimeMark? = info.expiresAtEpochSeconds?.let {
        TimeSource.Monotonic.markNow() + (it - currentTimeMillis() / 1000).seconds
    }

    private fun monitor(card: String?, initialGrant: Boolean = false) {
        maintenance?.cancel()
        if (card == null) return
        val cloud = isCloudCard(card)
        if (!cloud && _licenseState.value.expiresAtEpochSeconds == null) return
        maintenance = scope.launch {
            var nextRefresh = if (initialGrant) 60_000 else 0
            while (true) {
                if (_licenseState.value.isActivated && !isVipActive) publish(null, null)
                if (cloud && nextRefresh == 0) {
                    try {
                        val token = fetchFlickCloudLease(card)
                        if (token == null) publish(null, null)
                        else verifyCloudGrant(token, card)?.let { publish(token, it) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // Keep the last signed grant only until its original expiry.
                    }
                    nextRefresh = 60_000
                }
                if (!cloud && !_licenseState.value.isActivated) break
                delay(1_000)
                nextRefresh = (nextRefresh - 1_000).coerceAtLeast(0)
            }
        }
    }

    private fun verifyCloudGrant(token: String, card: String): VipLicenseInfo? =
        verifyFlickVipLicense(token)?.takeIf {
            it.licenseType == "cloud" && it.licenseKey == flickCloudCardId(card) &&
                (it.expiresAtEpochSeconds ?: 0) <= currentTimeMillis() / 1000 + 300
        }

    private fun isCloudCard(card: String): Boolean =
        card.length == 49 && card.startsWith("CPVC1.") && card.substring(6).all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_'
        }
}

internal expect fun verifyFlickVipLicense(token: String): VipLicenseInfo?
internal expect suspend fun fetchFlickCloudLease(card: String): String?
internal expect fun flickCloudCardId(card: String): String
