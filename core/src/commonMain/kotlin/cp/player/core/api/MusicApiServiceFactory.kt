package cp.player.core.api

import cp.player.core.MusicBackend
import cp.player.core.cache.ApiCache
import cp.player.core.cache.CacheConfig
import cp.player.core.cache.CachedMusicApiService
import cp.player.core.provider.ModuleManager
import cp.player.core.provider.ProviderManager
import cp.player.core.util.PlatformContext

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

    /**
     * 裸实现（不带缓存）。
     *
     * 只在确实需要绕过缓存层时用；常规取数请走 [instance] / [cachedInstance] 或
     * `unifiedSource`，否则缓存层形同不存在。
     */
    val rawInstance: MusicApiServiceImpl
        get() = MusicBackend.instance.apiImplInternal

    /**
     * 带读透缓存的装饰器 —— 与 `MusicBackend.musicApi` 交出的是同一个实例。
     *
     * 历史上这里交出裸实现，连装饰器本身都被绕过（见 ARCHITECTURE §5）。
     */
    val instance: MusicApiService
        get() = MusicBackend.instance.cachedApiInternal

    /** 同 [instance]，类型更具体（需要 `callApiCached` 流式入口时用）。 */
    val cachedInstance: CachedMusicApiService
        get() = MusicBackend.instance.cachedApiInternal

    /**
     * 初始化全局 API / Provider / 模块管理栈。
     *
     * 委托给 [MusicBackend.init]；统一管理状态机与自动激活。
     */
    fun init(
        context: PlatformContext,
        settings: cp.player.core.util.SettingsStorage,
        cache: ApiCache? = null,
        cacheConfig: CacheConfig = CacheConfig()
    ) {
        MusicBackend.init(context, settings, cache, cacheConfig)
    }

    /** 释放单例（主要用于测试/重置）。 */
    fun reset() {
        backend?.reset()
    }
}