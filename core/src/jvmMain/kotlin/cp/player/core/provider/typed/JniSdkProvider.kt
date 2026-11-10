package cp.player.core.provider.typed

import cp.player.core.provider.*
import cp.player.sdk.ProviderContext
import cp.player.sdk.jni.JniMusicProvider
import cp.player.sdk.jni.JniProviderSession

/**
 * Register a built-in JNI library through its own native session class.
 * The factory creates one handle per immutable account/config/credential context.
 */
fun jniSdkProvider(
    id: String,
    name: String,
    version: String,
    capabilities: List<String> = emptyList(),
    settingsSchema: List<ProviderSettingItemSchema>? = null,
    sdkAllowedOrigins: Set<String> = emptySet(),
    openSession: (ProviderContext) -> JniProviderSession,
): SdkBackendProvider = SdkBackendProvider(
    id = id, name = name, version = version, type = ProviderType.JNI,
    factory = { JniMusicProvider(openSession(it)) },
    capabilities = capabilities, settingsSchema = settingsSchema, sdkAllowedOrigins = sdkAllowedOrigins,
)
