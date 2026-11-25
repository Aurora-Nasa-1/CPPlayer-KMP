package cp.player.core.license

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlin.io.encoding.Base64

private const val PURPOSE = "cp.player/flick-vip/v1"
private const val PURPOSE_V2 = "cp.player/flick-vip/v2"
private val licenseId = Regex("[a-f0-9]{32}")
private val issuerKey by lazy {
    runCatching {
        val bytes = Base64.Default.decode(FLICK_VIP_PUBLIC_KEY)
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes))
    }.getOrNull()
}

internal actual fun verifyFlickVipLicense(token: String): VipLicenseInfo? =
    issuerKey?.let { verifyFlickVipLicense(token, it) }

internal fun verifyFlickVipLicense(token: String, key: PublicKey): VipLicenseInfo? {
    if (token.length > 2048 || !token.startsWith("CPV1.")) return null
    val parts = token.split('.', limit = 4)
    if (parts.size != 3) return null
    return runCatching {
        val payload = Base64.UrlSafe.decode(parts[1])
        val signature = Base64.UrlSafe.decode(parts[2])
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(key)
        verifier.update(payload)
        if (!verifier.verify(signature)) return null
        val claims = payload.decodeToString(throwOnInvalidSequence = true).split('\n')
        when {
            claims.size == 2 && claims[0] == PURPOSE && licenseId.matches(claims[1]) ->
                VipLicenseInfo(isActivated = true, licenseKey = claims[1], licenseType = "permanent")
            claims.size == 4 && claims[0] == PURPOSE_V2 && licenseId.matches(claims[1]) -> {
                val type = claims[2]
                val expiry = claims[3].toLongOrNull() ?: return null
                if (type !in setOf("permanent", "trial", "cloud")) return null
                if ((type == "permanent") != (expiry == 0L) || expiry > Long.MAX_VALUE / 1000L) return null
                if (expiry != 0L && expiry <= System.currentTimeMillis() / 1000L) return null
                VipLicenseInfo(
                    isActivated = true,
                    licenseKey = claims[1],
                    planName = when (type) {
                        "trial" -> "Hi-Res 测试授权"
                        "cloud" -> "Hi-Res 云控授权"
                        else -> "Hi-Res 专业发烧版 (永久授权)"
                    },
                    licenseType = type,
                    expiresAtEpochSeconds = expiry.takeIf { it != 0L },
                )
            }
            else -> return null
        }
    }.getOrNull()
}
