package cp.player.kmp.api

import cp.player.kmp.MusicBackend
import cp.player.kmp.cache.ApiCache
import cp.player.kmp.cache.CacheConfig
import cp.player.kmp.cache.CachedMusicApiService
import cp.player.kmp.cache.InMemoryApiCache
import cp.player.kmp.provider.ModuleManager
import cp.player.kmp.provider.ProviderManager
import cp.player.kmp.util.PlatformContext

/**
 * [MusicApiService] 的工厂与单例持有者（KMP 版，向后兼容适配层）。
 *
 * ⚠️ **新代码应直接使用 [MusicBackend]**——它统一封装了状态机、Provider 管理、
 * 本地音乐与播放引擎入口。本对象保留以兼容已有调用方，内部委托 [MusicBackend]。
 *
 * 在平台 Application 入口调用 [init] 后，全局可通过 [instance] /
 * [cachedInstance] 获取唯一实例。
 */
object MusicApiServiceFactory {

    /** 当前后端（init 后非空）。 */
    val backend: MusicBackend?
        get() = runCatching { MusicBackend.instance }.getOrNull()

    val providerManager: ProviderManager
        get() = MusicBackend.instance.providerManagerInternal

    val moduleManager: ModuleManager
        get() = MusicBackend.instance.moduleManagerInternal

    val instance: MusicApiServiceImpl
        get() = MusicBackend.instance.apiImplInternal

    val cachedInstance: CachedMusicApiService
        get() = MusicBackend.instance.cachedApiInternal

    /**
     * 初始化全局 API / Provider / 模块管理栈。
     *
     * 委托给 [MusicBackend.init]；统一管理状态机与自动激活。
     */
    fun init(
        context: PlatformContext,
        settings: cp.player.kmp.util.SettingsStorage,
        cache: ApiCache = InMemoryApiCache(),
        cacheConfig: CacheConfig = CacheConfig()
    ) {
        MusicBackend.init(context, settings, cache, cacheConfig)
    }

    /** 释放单例（主要用于测试/重置）。 */
    fun reset() {
        backend?.reset()
    }
}