package cp.player.core.provider

import cp.player.sdk.MusicProvider
import cp.player.sdk.ProviderContext

/**
 * Metadata/lifecycle bridge for the existing module registry. New providers only
 * implement MusicProvider; no server, method map, or CPPlayer response envelope.
 */
class SdkBackendProvider(
    override val id: String,
    override val name: String,
    override val version: String,
    override val type: ProviderType,
    private val factory: (ProviderContext) -> MusicProvider,
    override val settingsSchema: List<ProviderSettingItemSchema>? = null,
    override val sdkAllowedOrigins: Set<String> = emptySet(),
    override val capabilities: List<String>? = emptyList(),
) : BackendProvider {
    private val listeners = mutableSetOf<() -> Unit>()
    private var disposed = false
    override val updateUrl: String? = null
    override val targetAppPackage: String? = null
    override val contract = "kmp-sdk-v1"
    override fun createSdkProvider(context: ProviderContext): MusicProvider {
        check(isReady())
        return factory(context)
    }
    override fun retireSessions() = retire()
    private fun retire() {
        val snapshot = synchronized(listeners) { listeners.toList().also { listeners.clear() } }
        snapshot.forEach { it() }
    }
    override fun isReady() = synchronized(listeners) { !disposed }
    override fun onSdkSessionRetired(listener: () -> Unit): () -> Unit {
        val notify = synchronized(listeners) {
            if (disposed) true else { listeners.add(listener); false }
        }
        if (notify) listener()
        return { synchronized(listeners) { listeners.remove(listener) }; Unit }
    }
    override fun close() {
        synchronized(listeners) { disposed = true }
        retire()
    }
    override fun updateCustomSetting(key: String, value: String) {
        super.updateCustomSetting(key, value)
        retire()
    }
    override fun updateCustomSettings(settings: Map<String, String>) {
        super.updateCustomSettings(settings)
        retire()
    }
    override fun resetCustomSettings() {
        super.resetCustomSettings()
        retire()
    }
}
