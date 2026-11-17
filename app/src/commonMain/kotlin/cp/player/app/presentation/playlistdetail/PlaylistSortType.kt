package cp.player.app.presentation.playlistdetail

/** 歌单详情与排序弹层共用的排序方式；已从 UI 组件层下沉，避免状态机反向依赖 UI。 */
enum class PlaylistSortType { DEFAULT, NAME, ARTIST }
