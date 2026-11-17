package cp.player.app.presentation.userprofile

import cp.player.app.AppModel
import cp.player.app.i18n.CpStrings
import cp.player.app.presentation.UiPresenter
import cp.player.core.BackendResult
import cp.player.core.music.ProfileBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProfileUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val bundle: ProfileBundle? = null,
)

/**
 * 用户 / 歌手主页状态机（框架无关）。
 */
class UserProfilePresenter(
    override val scope: CoroutineScope,
) : UiPresenter<ProfileUiState> {

    private val _state = MutableStateFlow(ProfileUiState())
    override val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    private var loadedUid: String? = null
    private var loadGeneration = 0

    /**
     * @param strings 失败兜底文案按**当前语言**组 —— 协程里读不到 CompositionLocal，
     *   由调用侧传进来（见 I18N.md §5.9）。
     */
    fun load(uid: String, fallbackName: String?, strings: CpStrings) {
        if (loadedUid == uid && _state.value.bundle != null) return
        loadedUid = uid
        val generation = ++loadGeneration
        val previousBundle = _state.value.bundle?.takeIf { it.resourceId == uid }
        scope.launch {
            _state.value = ProfileUiState(loading = previousBundle == null, bundle = previousBundle)
            val result = try {
                AppModel.musicRepository.getProfileBundle(
                    uid,
                    onRefresh = { refreshed ->
                        scope.launch {
                            if (generation != loadGeneration || loadedUid != uid) return@launch
                            val freshBundle = (refreshed as? BackendResult.Success)?.data ?: return@launch
                            if (_state.value.bundle?.resourceId == uid) {
                                _state.value = _state.value.copy(bundle = freshBundle)
                            }
                        }
                    },
                    onPartial = { partial ->
                        scope.launch {
                            if (generation != loadGeneration || loadedUid != uid) return@launch
                            val base = (partial as? BackendResult.Success)?.data ?: return@launch
                            val previous = _state.value.bundle?.takeIf { it.resourceId == uid }
                            val visible = if (previous == null) base else base.copy(
                                albums = previous.albums,
                                songs = previous.songs,
                                playlists = previous.playlists,
                            )
                            _state.value = _state.value.copy(loading = false, error = null, bundle = visible)
                        }
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                BackendResult.Error(error.message ?: strings.account.profileLoadFailed)
            }
            if (generation != loadGeneration || loadedUid != uid) return@launch
            _state.value = when (result) {
                is BackendResult.Success -> ProfileUiState(loading = false, bundle = result.data)
                is BackendResult.Error -> ProfileUiState(
                    loading = false,
                    error = result.message,
                    bundle = previousBundle ?: fallbackName?.let {
                        ProfileBundle(
                            uid = uid.toLongOrNull() ?: 0L, resourceId = uid, isArtist = false, nickname = it,
                            avatarUrl = null, signature = null,
                            primaryCount = 0, follows = 0, followeds = 0,
                        )
                    },
                )
                is BackendResult.Unsupported -> ProfileUiState(
                    loading = false,
                    error = result.message,
                    bundle = previousBundle,
                )
            }
        }
    }

    fun reload(uid: String, fallbackName: String?, strings: CpStrings) {
        loadedUid = null
        load(uid, fallbackName, strings)
    }
}
