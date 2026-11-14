package cp.player.core.provider

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModuleManifestCountryCodeTest {
    private fun manifest(countryCode: String?) = ModuleManifest(
        id = "country-fixture",
        name = "Country",
        version = "1.0.0",
        type = "native",
        entryPoint = "provider.dll",
        contract = PROVIDER_SDK_CONTRACT,
        defaultCountryCode = countryCode,
    )

    @Test
    fun `declared country code is trimmed and loadable`() {
        val declared = manifest(" 86 ")

        assertEquals("86", declared.resolvedDefaultCountryCode)
        assertTrue(declared.isLoadable)
    }

    @Test
    fun `blank country code is treated as absent`() {
        val blank = manifest("   ")

        assertNull(blank.resolvedDefaultCountryCode)
        assertTrue(blank.isLoadable)
    }

    @Test
    fun `non digit or out of range country code is rejected`() {
        for (invalid in listOf("+86", "12345", "8a", "８６")) {
            val rejected = manifest(invalid)

            assertFalse(rejected.isLoadable, "Expected $invalid to be rejected")
            assertEquals("Invalid provider defaultCountryCode", rejected.loadabilityError)
        }
    }

    @Test
    fun `json round trip keeps the optional field`() {
        val json = Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(manifest("86"))
        val decoded = json.decodeFromString<ModuleManifest>(encoded)

        assertEquals("86", decoded.defaultCountryCode)
        val withoutField = """
            {"id":"country-fixture","name":"Country","version":"1.0.0","type":"native","entryPoint":"provider.dll","contract":"kmp-sdk-v1"}
        """.trimIndent()
        assertNull(json.decodeFromString<ModuleManifest>(withoutField).defaultCountryCode)
    }
}
