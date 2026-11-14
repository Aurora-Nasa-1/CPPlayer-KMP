package cp.player.app.i18n

/**
 * 音源模块管理（导入 / 切换 / 更新 / 导出 / 删除）的文案。
 *
 * 与 [PluginRepositoryStrings] 分开：那一组管「从仓库发现和安装插件」，这一组管
 * 「已安装模块的日常维护」。两者都出现在插件中心，但动作与状态完全不同。
 */
interface ProviderManagementStrings {
    val screenTitle: String
    val checkUpdatesContentDesc: String
    val importProviderContentDesc: String
    val importLyricsContentDesc: String
    val tabProviders: String
    val tabLyrics: String

    val noProvidersTitle: String
    val noProvidersMessage: String
    val importProvider: String
    val importProviderNote: String
    val autoCheck: String
    val autoCheckNote: String

    val deleteTitle: String
    fun deleteMessage(name: String, id: String, isActive: Boolean): String
    val deleteConfirm: String
    val pendingRestartTag: String
    val activeProviderContentDesc: String
    fun updateAvailableBadge(version: String): String
    fun moreActions(name: String): String
    fun updateDetails(version: String): String
    val checkingUpdate: String
    val checkUpdate: String
    val configureUpdateSource: String
    val providerSettings: String
    val exportModule: String
    val updateModuleLocal: String
    val deleteModule: String

    fun updateAvailable(name: String, version: String): String
    fun upToDate(name: String): String
    fun checkFailed(name: String, error: String): String
    val checkingAll: String
    fun checkAllDoneAvailable(count: Int): String
    val checkAllDoneUpToDate: String
    fun downloading(name: String, version: String): String
    fun downloadingPercent(name: String, version: String, percent: Int): String
    val downloadFailed: String
    fun updatedAndActivatedVersioned(name: String, version: String): String
    fun updatedVersioned(name: String, version: String): String
    val noFileSelected: String
    val importing: String
    fun importedAndActivated(name: String): String
    fun updatedAndActivated(name: String): String
    fun imported(name: String, active: String): String
    fun updated(name: String, active: String): String
    fun updating(name: String): String
    fun exporting(name: String): String
    fun exported(name: String, fileName: String): String
    fun activated(name: String): String
    val switchFailed: String
    fun deleted(id: String): String
    val noActiveProvider: String

    // —— 音源自定义设置页 ——
    fun customSettingsTitle(providerName: String): String
    val customSettingsNote: String
    val customSettingsSection: String
    val settingUnset: String
    fun settingCurrent(value: String): String
    fun editSetting(label: String): String

    // —— 音源更新详情 / 更新源设置弹窗 ——
    val updateDialogTitle: String
    val currentVersion: String
    val latestVersion: String
    fun publishedAt(date: String): String
    val changelogTitle: String
    val changelogMissing: String
    val downloadUrl: String
    val linkCopied: String
    val copyLink: String
    val openInBrowser: String
    val updateNow: String
    val later: String
    val updateSourceTitle: String
    val updateSourceNote: String
    fun manifestDefaultSource(url: String?): String
    val customSourceApplied: String
    val updateCheckUrl: String
    val saveAndCheck: String
    val restoreDefault: String
    val undeclared: String

    // —— 后端初始化失败页 ——
    val backendErrorTitle: String
    val retry: String
    val importNewProvider: String
    val installedProviders: String
    val backendErrorHint: String
    fun deleteProviderContentDesc(name: String): String
    fun deleting(id: String): String
    fun backendDeleteMessage(name: String, isActive: Boolean): String
}

object ProviderManagementStringsZh : ProviderManagementStrings {
    override val screenTitle = "音源与歌词管理"
    override val checkUpdatesContentDesc = "检查音源更新"
    override val importProviderContentDesc = "导入音源模块"
    override val importLyricsContentDesc = "导入歌词插件"
    override val tabProviders = "音源模块"
    override val tabLyrics = "歌词来源"

    override val noProvidersTitle = "尚未加载任何音源模块"
    override val noProvidersMessage = "导入 .zip 模块包即可开始使用，模块包需含 manifest.json"
    override val importProvider = "导入音源模块"
    override val importProviderNote = "选择 .zip 模块包（需含 manifest.json）"
    override val autoCheck = "自动检查音源更新"
    override val autoCheckNote = "进入本页时自动检测音源最新版本，支持 manifest 及自定义更新源"

    override val deleteTitle = "删除音源模块"
    override fun deleteMessage(name: String, id: String, isActive: Boolean) = buildString {
        append("确定删除「").append(name).append("」（").append(id).append("）吗？\n")
        append("模块文件与该音源下的登录态会被移除，需要重新导入才能使用。")
        if (isActive) append("\n它正是当前音源，删除后会自动切换到其他可用音源。")
    }
    override val deleteConfirm = "删除"
    override val pendingRestartTag = "待重启生效"
    override val activeProviderContentDesc = "当前音源"
    override fun updateAvailableBadge(version: String) = "可更新 v$version"
    override fun moreActions(name: String) = "更多操作 $name"
    override fun updateDetails(version: String) = "更新详情 (v$version)"
    override val checkingUpdate = "正在检查更新…"
    override val checkUpdate = "检查更新"
    override val configureUpdateSource = "设置更新源"
    override val providerSettings = "音源配置"
    override val exportModule = "导出模块"
    override val updateModuleLocal = "更新模块 (本地导入)"
    override val deleteModule = "删除模块"

    override fun updateAvailable(name: String, version: String) = "$name 发现新版本 v$version"
    override fun upToDate(name: String) = "$name 已是最新版本"
    override fun checkFailed(name: String, error: String) = "$name 检查更新失败: $error"
    override val checkingAll = "正在检查音源更新…"
    override fun checkAllDoneAvailable(count: Int) = "检查完成，发现 $count 个音源有新版本"
    override val checkAllDoneUpToDate = "检查完成，所有音源均为最新版本"
    override fun downloading(name: String, version: String) = "正在下载 $name v$version…"
    override fun downloadingPercent(name: String, version: String, percent: Int) =
        "正在下载 $name v$version ($percent%)…"
    override val downloadFailed = "下载更新失败"
    override fun updatedAndActivatedVersioned(name: String, version: String) = "已更新并激活 $name (v$version)"
    override fun updatedVersioned(name: String, version: String) = "已更新 $name (v$version)"
    override val noFileSelected = "未选择文件"
    override val importing = "正在导入…"
    override fun importedAndActivated(name: String) = "已导入并自动激活 $name"
    override fun updatedAndActivated(name: String) = "已自动更新并激活 $name"
    override fun imported(name: String, active: String) = "已导入 $name（当前仍使用 $active）"
    override fun updated(name: String, active: String) = "已自动更新 $name（当前仍使用 $active）"
    override fun updating(name: String) = "正在更新 $name…"
    override fun exporting(name: String) = "正在导出 $name…"
    override fun exported(name: String, fileName: String) = "已导出 $name → $fileName"
    override fun activated(name: String) = "已切换到 $name"
    override val switchFailed = "切换失败"
    override fun deleted(id: String) = "已删除模块 $id"
    override val noActiveProvider = "无"

    override fun customSettingsTitle(providerName: String) = "$providerName 配置"
    override val customSettingsNote = "在此配置该音源专属参数。保存后将在发起 API 调用时自动注入。"
    override val customSettingsSection = "参数设置"
    override val settingUnset = "未设置"
    override fun settingCurrent(value: String) = "当前: $value"
    override fun editSetting(label: String) = "编辑 $label"

    override val updateDialogTitle = "音源更新"
    override val currentVersion = "当前版本"
    override val latestVersion = "最新版本"
    override fun publishedAt(date: String) = "发布日期: $date"
    override val changelogTitle = "更新说明"
    override val changelogMissing = "作者未提供更新日志说明"
    override val downloadUrl = "下载地址"
    override val linkCopied = "下载链接已复制到剪贴板"
    override val copyLink = "复制链接"
    override val openInBrowser = "在浏览器打开"
    override val updateNow = "立即更新"
    override val later = "稍后再说"
    override val updateSourceTitle = "设置更新源"
    override val updateSourceNote =
        "说明：支持标准更新 JSON 端点或 GitHub Releases API（如 https://api.github.com/repos/owner/repo/releases/latest）。"
    override fun manifestDefaultSource(url: String?) = "Manifest 默认源: ${url ?: undeclared}"
    override val customSourceApplied = "当前已应用自定义更新源"
    override val updateCheckUrl = "更新检测 URL"
    override val saveAndCheck = "保存并检测"
    override val restoreDefault = "恢复默认"
    override val undeclared = "（未声明）"

    override val backendErrorTitle = "后端初始化失败"
    override val retry = "重试"
    override val importNewProvider = "导入新音源"
    override val installedProviders = "已安装的音源"
    override val backendErrorHint = "若某个音源反复导致初始化失败，可在此删除后再重试"
    override fun deleteProviderContentDesc(name: String) = "删除音源 $name"
    override fun deleting(id: String) = "正在删除 $id…"
    override fun backendDeleteMessage(name: String, isActive: Boolean) = buildString {
        append("确定删除「").append(name).append("」吗？\n")
        append("模块文件与该音源下的登录态会被移除，需要重新导入才能使用。")
        if (isActive) append("\n它正是当前音源，删除后不会再有可用音源，需要重新导入。")
    }
}

object ProviderManagementStringsEn : ProviderManagementStrings {
    override val screenTitle = "Providers and lyrics"
    override val checkUpdatesContentDesc = "Check provider updates"
    override val importProviderContentDesc = "Import provider module"
    override val importLyricsContentDesc = "Import lyric plugin"
    override val tabProviders = "Provider modules"
    override val tabLyrics = "Lyric sources"

    override val noProvidersTitle = "No provider modules loaded"
    override val noProvidersMessage = "Import a .zip module to get started; the package must contain manifest.json"
    override val importProvider = "Import provider module"
    override val importProviderNote = "Choose a .zip module (manifest.json required)"
    override val autoCheck = "Check for provider updates automatically"
    override val autoCheckNote = "Check the latest provider versions when opening this page; supports manifest and custom update sources"

    override val deleteTitle = "Delete provider module"
    override fun deleteMessage(name: String, id: String, isActive: Boolean) = buildString {
        append("Delete \"").append(name).append("\" (").append(id).append(")?\n")
        append("The module files and sign-in state for this provider will be removed; import it again to use it.")
        if (isActive) append("\nThis is the active provider; another available provider will be selected automatically.")
    }
    override val deleteConfirm = "Delete"
    override val pendingRestartTag = "Restart required"
    override val activeProviderContentDesc = "Active provider"
    override fun updateAvailableBadge(version: String) = "Update v$version"
    override fun moreActions(name: String) = "More actions for $name"
    override fun updateDetails(version: String) = "Update details (v$version)"
    override val checkingUpdate = "Checking for updates…"
    override val checkUpdate = "Check for updates"
    override val configureUpdateSource = "Configure update source"
    override val providerSettings = "Provider settings"
    override val exportModule = "Export module"
    override val updateModuleLocal = "Update module (local import)"
    override val deleteModule = "Delete module"

    override fun updateAvailable(name: String, version: String) = "$name has a new version: v$version"
    override fun upToDate(name: String) = "$name is up to date"
    override fun checkFailed(name: String, error: String) = "Couldn't check updates for $name: $error"
    override val checkingAll = "Checking provider updates…"
    override fun checkAllDoneAvailable(count: Int) = "Check complete; $count providers have updates"
    override val checkAllDoneUpToDate = "Check complete; all providers are up to date"
    override fun downloading(name: String, version: String) = "Downloading $name v$version…"
    override fun downloadingPercent(name: String, version: String, percent: Int) =
        "Downloading $name v$version ($percent%)…"
    override val downloadFailed = "Update download failed"
    override fun updatedAndActivatedVersioned(name: String, version: String) = "Updated and activated $name (v$version)"
    override fun updatedVersioned(name: String, version: String) = "Updated $name (v$version)"
    override val noFileSelected = "No file selected"
    override val importing = "Importing…"
    override fun importedAndActivated(name: String) = "Imported and activated $name"
    override fun updatedAndActivated(name: String) = "Updated and activated $name"
    override fun imported(name: String, active: String) = "Imported $name (still using $active)"
    override fun updated(name: String, active: String) = "Updated $name (still using $active)"
    override fun updating(name: String) = "Updating $name…"
    override fun exporting(name: String) = "Exporting $name…"
    override fun exported(name: String, fileName: String) = "Exported $name → $fileName"
    override fun activated(name: String) = "Switched to $name"
    override val switchFailed = "Couldn't switch providers"
    override fun deleted(id: String) = "Deleted module $id"
    override val noActiveProvider = "none"

    override fun customSettingsTitle(providerName: String) = "$providerName settings"
    override val customSettingsNote = "Configure provider-specific parameters here. Saved values are applied automatically to API calls."
    override val customSettingsSection = "Parameters"
    override val settingUnset = "Not set"
    override fun settingCurrent(value: String) = "Current: $value"
    override fun editSetting(label: String) = "Edit $label"

    override val updateDialogTitle = "Provider update"
    override val currentVersion = "Current version"
    override val latestVersion = "Latest version"
    override fun publishedAt(date: String) = "Published: $date"
    override val changelogTitle = "Release notes"
    override val changelogMissing = "The author did not provide release notes"
    override val downloadUrl = "Download URL"
    override val linkCopied = "Download link copied to the clipboard"
    override val copyLink = "Copy link"
    override val openInBrowser = "Open in browser"
    override val updateNow = "Update now"
    override val later = "Later"
    override val updateSourceTitle = "Configure update source"
    override val updateSourceNote = "Supports a standard update JSON endpoint or the GitHub Releases API " +
        "(e.g. https://api.github.com/repos/owner/repo/releases/latest)."
    override fun manifestDefaultSource(url: String?) = "Manifest default: ${url ?: undeclared}"
    override val customSourceApplied = "A custom update source is currently applied"
    override val updateCheckUrl = "Update check URL"
    override val saveAndCheck = "Save and check"
    override val restoreDefault = "Restore default"
    override val undeclared = "(not declared)"

    override val backendErrorTitle = "Backend initialization failed"
    override val retry = "Retry"
    override val importNewProvider = "Import a new provider"
    override val installedProviders = "Installed providers"
    override val backendErrorHint = "If a provider repeatedly breaks initialization, delete it here and retry"
    override fun deleteProviderContentDesc(name: String) = "Delete provider $name"
    override fun deleting(id: String) = "Deleting $id…"
    override fun backendDeleteMessage(name: String, isActive: Boolean) = buildString {
        append("Delete \"").append(name).append("\"?\n")
        append("The module files and sign-in state for this provider will be removed; import it again to use it.")
        if (isActive) append("\nThis is the active provider; deleting it leaves no provider available, so you will need to import one again.")
    }
}
