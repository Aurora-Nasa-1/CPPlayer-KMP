package cp.player.core.license

import java.security.KeyPairGenerator
import java.security.Signature
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class FlickVipVerifierTest {
    private val id = "0123456789abcdef".repeat(2)

    @Test fun acceptsIssuerSignatureAndReturnsOnlyTheLicenseId() {
        val info = verifyFlickVipLicense(token("cp.player/flick-vip/v1\n$id"), issuer.public)
        assertEquals(true, info?.isActivated)
        assertEquals(id, info?.licenseKey)
    }

    @Test fun rejectsTamperingAndWrongIssuer() {
        val token = token("cp.player/flick-vip/v1\n$id")
        val changedPayload = Base64.UrlSafe.encode("cp.player/flick-vip/v1\n${"b".repeat(32)}".encodeToByteArray())
        assertNull(verifyFlickVipLicense("CPV1.$changedPayload.${token.substringAfterLast('.')}", issuer.public))
        assertNull(verifyFlickVipLicense(token, otherIssuer.public))
    }

    @Test fun rejectsOtherProductsEvenWhenSignedAndRejectsLegacyCode() {
        assertNull(verifyFlickVipLicense(token("cp.player/other/v1\n$id"), issuer.public))
        assertNull(verifyFlickVipLicense("CP88-0000-0000-0000", issuer.public))
        assertNull(verifyFlickVipLicense("CPV1.invalid.invalid.extra", issuer.public))
    }

    @Test fun acceptsUnexpiredTrialAndCloudLeasesAndRejectsExpiredOrMalformedClaims() {
        val expiry = System.currentTimeMillis() / 1000L + 3600
        val trial = verifyFlickVipLicense(token("cp.player/flick-vip/v2\n$id\ntrial\n$expiry"), issuer.public)
        assertEquals("trial", trial?.licenseType)
        assertEquals(expiry, trial?.expiresAtEpochSeconds)

        val cloud = verifyFlickVipLicense(token("cp.player/flick-vip/v2\n$id\ncloud\n$expiry"), issuer.public)
        assertEquals("cloud", cloud?.licenseType)
        assertNotNull(cloud)

        assertNull(verifyFlickVipLicense(token("cp.player/flick-vip/v2\n$id\ntrial\n1"), issuer.public))
        assertNull(verifyFlickVipLicense(token("cp.player/flick-vip/v2\n$id\npermanent\n$expiry"), issuer.public))
        assertNull(verifyFlickVipLicense(token("cp.player/flick-vip/v2\n$id\ntrial\n$expiry\nextra"), issuer.public))
    }

    private fun token(claims: String): String {
        val payload = claims.encodeToByteArray()
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(issuer.private)
            update(payload)
            sign()
        }
        return "CPV1.${Base64.UrlSafe.encode(payload)}.${Base64.UrlSafe.encode(signature)}"
    }

    private companion object {
        val issuer = generateKeyPair()
        val otherIssuer = generateKeyPair()
        fun generateKeyPair() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }
}
