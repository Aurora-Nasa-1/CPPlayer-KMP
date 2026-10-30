package cp.player.app.i18n

interface PluginRepositoryStrings {
    val title: String
    val subtitle: String
    val browse: String
    val installed: String
    val updates: String
    val repositories: String
    val all: String
    val providers: String
    val lyrics: String
    val search: String
    val refresh: String
    val refreshing: String
    val network: String
    val networkNote: String
    val autoCheck: String
    val autoCheckNote: String
    val offlineNote: String
    val cachedNote: String
    val trustNote: String
    val addSource: String
    val sourceUrl: String
    val invalidUrl: String
    val noSources: String
    val emptyCatalog: String
    val noMatches: String
    val noInstalled: String
    val noUpdates: String
    val install: String
    val update: String
    val replaceLocal: String
    val upToDate: String
    val bundled: String
    val local: String
    val otherSource: String
    val pendingRestart: String
    val details: String
    val changelog: String
    val downloading: String
    val verifying: String
    val installing: String
    val complete: String
    val failed: String
    val cancel: String
    val close: String
    val removeSource: String
    val providerActivationNote: String
    val lyricsActivationNote: String
    fun installTitle(name: String): String
    fun identity(id: String, version: String, source: String): String
    fun removeSourceMessage(url: String): String
    fun sourceFailed(name: String, error: String): String
    fun pendingFailed(error: String): String
    fun packageInfo(platform: String, arch: String, runtime: String, bytes: Long): String
}

object PluginRepositoryStringsZh : PluginRepositoryStrings {
    override val title = "插件中心"
    override val subtitle = "浏览仓库，一键安装和更新音源与歌词插件"
    override val browse = "浏览"
    override val installed = "已安装"
    override val updates = "更新"
    override val repositories = "仓库"
    override val all = "全部"
    override val providers = "音源"
    override val lyrics = "歌词"
    override val search = "搜索名称、作者或插件 ID"
    override val refresh = "检查更新"
    override val refreshing = "正在刷新目录"
    override val network = "允许仓库联网"
    override val networkNote = "仅访问启用的仓库和所选安装包，不上传账号、Cookie 或曲库。"
    override val autoCheck = "自动检查插件更新"
    override val autoCheckNote = "应用运行时每天最多检查一次目录；不自动下载或安装。"
    override val offlineNote = "仓库联网已关闭，仍可浏览缓存和管理已安装插件。"
    override val cachedNote = "显示上次获取的目录；未刷新或请求失败时，不能据此确认没有更新。"
    override val trustNote = "只安装你信任的作者和仓库。插件可能以应用权限运行或联网；SHA-256 仅校验与仓库声明一致，不代表安全审核。"
    override val addSource = "添加仓库"
    override val sourceUrl = "HTTPS 目录地址"
    override val invalidUrl = "请输入不含账号密码和片段的 HTTPS 地址"
    override val noSources = "还没有启用的仓库，请到「仓库」添加或启用。"
    override val emptyCatalog = "目录中暂无插件。允许联网后点击「检查更新」获取目录。"
    override val noMatches = "没有符合筛选条件的插件"
    override val noInstalled = "还没有安装插件"
    override val noUpdates = "当前缓存目录中没有可更新的插件"
    override val install = "安装"
    override val update = "更新"
    override val replaceLocal = "替换本地导入版本"
    override val upToDate = "已安装此版本或更高版本"
    override val bundled = "内置，随应用更新"
    override val local = "本地导入"
    override val otherSource = "来自其他仓库，不能直接覆盖"
    override val pendingRestart = "更新已暂存，重启应用后生效"
    override val details = "插件详情"
    override val changelog = "更新说明"
    override val downloading = "正在下载"
    override val verifying = "正在校验安装包"
    override val installing = "正在安装，请稍候"
    override val complete = "安装完成，可在音源或歌词来源中管理"
    override val failed = "操作失败"
    override val cancel = "取消下载"
    override val close = "关闭"
    override val removeSource = "移除仓库"
    override val providerActivationNote = "没有当前音源时会自动选择此音源；更新当前音源前请暂停播放。原生插件更新需重启应用。"
    override val lyricsActivationNote = "新安装的歌词插件默认停用，请到「歌词来源」启用和排序；更新保留原开关。"
    override fun installTitle(name: String) = "安装或更新「$name」？"
    override fun identity(id: String, version: String, source: String) = "插件 ID：$id\n版本：$version\n仓库：$source"
    override fun removeSourceMessage(url: String) = "停止访问 $url，不会卸载已安装插件；该仓库的插件将不再检查更新。"
    override fun sourceFailed(name: String, error: String) = "$name 刷新失败，保留缓存：$error"
    override fun pendingFailed(error: String) = "重启更新未完成，已尝试保留旧版本：$error"
    override fun packageInfo(platform: String, arch: String, runtime: String, bytes: Long) =
        "$platform / $arch · $runtime · $bytes 字节"
}

object PluginRepositoryStringsEn : PluginRepositoryStrings {
    override val title = "Plugin center"
    override val subtitle = "Browse, install and update provider and lyric plugins"
    override val browse = "Browse"
    override val installed = "Installed"
    override val updates = "Updates"
    override val repositories = "Catalogs"
    override val all = "All"
    override val providers = "Providers"
    override val lyrics = "Lyrics"
    override val search = "Search name, author or plugin ID"
    override val refresh = "Check for updates"
    override val refreshing = "Refreshing catalogs"
    override val network = "Allow repository networking"
    override val networkNote = "Contact enabled catalogs and selected packages only. No account, cookies or library are uploaded."
    override val autoCheck = "Check for plugin updates automatically"
    override val autoCheckNote = "Check catalogs at most daily while the app runs. Never download or install automatically."
    override val offlineNote = "Repository networking is off. Cached catalogs and installed plugins remain available."
    override val cachedNote = "Showing the last fetched catalogs. Stale or failed checks cannot confirm that no updates exist."
    override val trustNote = "Install only from authors and repositories you trust. Plugins may run with app privileges or access the network. SHA-256 checks repository integrity, not safety."
    override val addSource = "Add repository"
    override val sourceUrl = "HTTPS catalog URL"
    override val invalidUrl = "Enter an HTTPS URL without credentials or a fragment"
    override val noSources = "No enabled repositories. Add or enable one in Repositories."
    override val emptyCatalog = "No plugins in the catalog. Enable networking and check for updates to fetch catalogs."
    override val noMatches = "No matching plugins"
    override val noInstalled = "No installed plugins"
    override val noUpdates = "No updates in the cached catalogs"
    override val install = "Install"
    override val update = "Update"
    override val replaceLocal = "Replace local import"
    override val upToDate = "This version or a newer one is installed"
    override val bundled = "Built-in; updated with the app"
    override val local = "Local import"
    override val otherSource = "Managed by another repository; cannot overwrite"
    override val pendingRestart = "Update staged; restart the app to apply"
    override val details = "Plugin details"
    override val changelog = "Release notes"
    override val downloading = "Downloading"
    override val verifying = "Verifying package"
    override val installing = "Installing; please wait"
    override val complete = "Installed; manage it in Providers or Lyric sources"
    override val failed = "Operation failed"
    override val cancel = "Cancel download"
    override val close = "Close"
    override val removeSource = "Remove repository"
    override val providerActivationNote = "Selected automatically when no provider is active. Pause playback before updating the active provider. Native updates require an app restart."
    override val lyricsActivationNote = "New lyric plugins are disabled until enabled and ordered in Lyric sources. Updates retain the existing toggle."
    override fun installTitle(name: String) = "Install or update \"$name\"?"
    override fun identity(id: String, version: String, source: String) = "Plugin ID: $id\nVersion: $version\nRepository: $source"
    override fun removeSourceMessage(url: String) = "Stop contacting $url. Installed plugins remain, but updates from this repository will no longer be checked."
    override fun sourceFailed(name: String, error: String) = "$name could not refresh; cache retained: $error"
    override fun pendingFailed(error: String) = "A staged update failed at restart; attempted to retain the previous version: $error"
    override fun packageInfo(platform: String, arch: String, runtime: String, bytes: Long) =
        "$platform / $arch · $runtime · $bytes bytes"
}
