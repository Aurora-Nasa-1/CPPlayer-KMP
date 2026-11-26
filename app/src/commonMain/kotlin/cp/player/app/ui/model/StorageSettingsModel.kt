package cp.player.app.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cp.player.app.presentation.storage.StorageSettingsContract
import cp.player.app.presentation.storage.StorageSettingsPresenter
import cp.player.app.ui.util.UiEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

// 兼容现有 UI 引用的类型别名
typealias StorageUiState = cp.player.app.presentation.storage.StorageUiState

/** 存储管理页的 Voyager 适配层（Thin Adapter）。 */
class StorageSettingsModel : ViewModel(), StorageSettingsContract {

    private val presenter = StorageSettingsPresenter(
        scope = viewModelScope,
        notify = UiEvents::notify,
    )

    override val state: StateFlow<StorageUiState> get() = presenter.state
    override val scope: CoroutineScope get() = presenter.scope

    override fun refreshImageCacheSize() = presenter.refreshImageCacheSize()
    override fun clearImageCache() = presenter.clearImageCache()
    override fun refreshSongCache() = presenter.refreshSongCache()
    override fun clearSongCache() = presenter.clearSongCache()
    override fun clearSongCacheOlderThan(days: Int) = presenter.clearSongCacheOlderThan(days)
    override fun setSongCacheCapacity(bytes: Long) = presenter.setSongCacheCapacity(bytes)
    override fun migrateCacheDir(targetPath: String) = presenter.migrateCacheDir(targetPath)
}

/** 兼容旧代码引用，统一委托至框架无关实现。 */
fun formatBytes(bytes: Long): String = cp.player.app.presentation.util.formatBytes(bytes)
