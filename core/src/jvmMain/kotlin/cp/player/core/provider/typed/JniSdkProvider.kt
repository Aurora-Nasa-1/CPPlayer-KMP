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
    // 每次 dispatch 都跑在单会话的串行队列上；首页会并发拉多个接口，
    // 单会话排队会把上游往返叠加成「平均 1s+」。池化只放宽会话数，单会话仍串行。
    factory = { context -> JniMusicProvider.pooled { openSession(context) } },
    capabilities = capabilities, settingsSchema = settingsSchema, sdkAllowedOrigins = sdkAllowedOrigins,
)
