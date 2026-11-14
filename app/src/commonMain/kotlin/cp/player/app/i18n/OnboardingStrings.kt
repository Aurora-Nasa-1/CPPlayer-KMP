package cp.player.app.i18n

/**
 * 首次使用引导（欢迎 / 音源 / 登录 / 完成）的文案。
 *
 * 单独成组而不是塞进 `SettingsStrings`：引导是一条独立流程，四页共享同一套
 * 步骤名与导航按钮；散到各页会出现「上一步」与「返回」两种说法。
 */
interface OnboardingStrings {
    fun stepProgress(current: Int, total: Int): String

    val skip: String
    val back: String
    val previous: String
    val next: String
    val done: String
    val getStarted: String

    val stepWelcome: String
    val stepProvider: String
    val stepLogin: String
    val stepDone: String

    val welcomeTitle: String
    val welcomeBody: String
    val providerTitle: String
    val providerBody: String
    val loginTitle: String
    val loginBody: String
    val doneTitle: String
    val doneBody: String

    val doneProviderLabel: String
    val doneAccountLabel: String
    val providerUnconfigured: String
    val loggedIn: String
    val guestMode: String

    val loginStepLabel: String
    val loginBenefits: String
    val loginLater: String
    val loginLaterNote: String
    val noProviderTitle: String
    val noProviderBody: String
    val goConfigureProvider: String
    val guestContinue: String
    fun loginSuccess(provider: String): String
    val continueLabel: String
    val accountPlaceholderTitle: String
    val accountPlaceholderBody: String

    val cloudSection: String
    val refreshCatalog: String
    val cloudDisabledNote: String
    val enableCloudAndFetch: String
    val fetchingCatalog: String
    val installing: String
    val installed: String
    val incompatible: String
    val fetch: String
    val noRecommended: String
    val retry: String
    val orUseLocal: String
    val importLocal: String
    val processingModule: String
    val loadedProviders: String
    val activeProvider: String
    val localModeTitle: String
    val localModeBody: String
    val manageProviders: String
    val enableCloudTitle: String
    val enableCloudMessage: String
    val enableCloudConfirm: String

    fun catalogFetchFailed(error: String): String
    fun refreshFailed(error: String): String
    fun installedAndActivated(name: String): String
    fun installFailed(error: String): String
    fun importedAndActivated(name: String): String
    fun updatedAndActivated(name: String): String
    fun imported(name: String, active: String): String
    fun updated(name: String, active: String): String
    val noActiveProvider: String
}

object OnboardingStringsZh : OnboardingStrings {
    override fun stepProgress(current: Int, total: Int) = "第 $current 步 / 共 $total 步"

    override val skip = "跳过"
    override val back = "返回"
    override val previous = "上一步"
    override val next = "下一步"
    override val done = "完成"
    override val getStarted = "开始使用"

    override val stepWelcome = "欢迎"
    override val stepProvider = "音源"
    override val stepLogin = "登录"
    override val stepDone = "完成"

    override val welcomeTitle = "欢迎使用 CPPlayer"
    override val welcomeBody = "本地优先的播放器：歌单、红心、下载都在本机，音乐源只是「从哪台服务器拿歌」。" +
        "接下来会带你配置音源、登录账号，几步就能开始听歌。"
    override val providerTitle = "配置你的音乐源"
    override val providerBody = "CPPlayer 通过可插拔的 Provider 模块连接在线音乐服务。" +
        "您可以从云端仓库一键获取推荐音源，也可以导入本地模块 (.zip)，或者跳过此步直接使用纯本地播放；" +
        "之后随时可以在「音源管理」里增删、切换。"
    override val loginTitle = "登录你的账号"
    override val loginBody = "扫码、邮箱或手机号登录后可同步歌单、红心、评论与消息；" +
        "暂时不想登录也可以先以游客模式继续。登录态按音源分开保存，换音源不会串号。"
    override val doneTitle = "都准备好了"
    override val doneBody = "音质、睡眠定时、外观等都在「设置」里，想改随时回来。"

    override val doneProviderLabel = "音源"
    override val doneAccountLabel = "账号"
    override val providerUnconfigured = "未配置（仅本地播放）"
    override val loggedIn = "已登录"
    override val guestMode = "游客模式"

    override val loginStepLabel = "第 3 步 · 登录"
    override val loginBenefits = "登录后可同步歌单、红心、评论与消息；登录态按音源隔离。"
    override val loginLater = "暂不登录，先体验"
    override val loginLaterNote = "稍后可在「设置 → 账号」登录"
    override val noProviderTitle = "还没有可登录的音源"
    override val noProviderBody = "登录需要音源账号。先回到第 2 步安装或选择一个音源；也可以先以游客模式继续。"
    override val goConfigureProvider = "去配置音源"
    override val guestContinue = "先游客体验"
    override fun loginSuccess(provider: String) = "$provider · 登录成功"
    override val continueLabel = "继续"
    override val accountPlaceholderTitle = "登录你的账号"
    override val accountPlaceholderBody = "登录界面会在正式流程中内嵌在这里。"

    override val cloudSection = "云端音源仓库（推荐）"
    override val refreshCatalog = "刷新云端音源"
    override val cloudDisabledNote = "开启云端连接，即可浏览并一键安装官方推荐的音乐源，开箱即用。"
    override val enableCloudAndFetch = "开启云端并获取推荐音源"
    override val fetchingCatalog = "正在获取云端音源目录…"
    override val installing = "安装中"
    override val installed = "已安装"
    override val incompatible = "不兼容"
    override val fetch = "获取"
    override val noRecommended = "暂无可用的推荐音源"
    override val retry = "重试获取"
    override val orUseLocal = "或者使用本地模块"
    override val importLocal = "导入本地音源模块 (.zip)"
    override val processingModule = "正在处理模块…"
    override val loadedProviders = "已加载的音源"
    override val activeProvider = "当前活跃"
    override val localModeTitle = "未导入音源时将以本地音乐模式运行"
    override val localModeBody = "您可以直接管理与播放本机音频，随时可在设置中导入音源模块解锁在线曲库。"
    override val manageProviders = "前往音源管理（增删、切换、更新）"
    override val enableCloudTitle = "开启云端音源仓库"
    override val enableCloudMessage =
        "CPPlayer 将请求官方音源仓库目录以获取可用音源列表。音源模块在本地运行并拥有与应用相同的权限，请确认信任来源。"
    override val enableCloudConfirm = "开启并获取"

    override fun catalogFetchFailed(error: String) = "获取云端列表失败: $error"
    override fun refreshFailed(error: String) = "刷新失败: $error"
    override fun installedAndActivated(name: String) = "已安装并激活 $name"
    override fun installFailed(error: String) = "安装失败: $error"
    override fun importedAndActivated(name: String) = "已导入并自动激活 $name"
    override fun updatedAndActivated(name: String) = "已自动更新并激活 $name"
    override fun imported(name: String, active: String) = "已导入 $name（当前仍使用 $active）"
    override fun updated(name: String, active: String) = "已自动更新 $name（当前仍使用 $active）"
    override val noActiveProvider = "无"
}

object OnboardingStringsEn : OnboardingStrings {
    override fun stepProgress(current: Int, total: Int) = "Step $current of $total"

    override val skip = "Skip"
    override val back = "Back"
    override val previous = "Previous"
    override val next = "Next"
    override val done = "Done"
    override val getStarted = "Get started"

    override val stepWelcome = "Welcome"
    override val stepProvider = "Providers"
    override val stepLogin = "Sign in"
    override val stepDone = "Done"

    override val welcomeTitle = "Welcome to CPPlayer"
    override val welcomeBody = "A local-first player: playlists, favorites and downloads stay on this device. " +
        "A provider only decides where songs are fetched from. " +
        "Next you will set up a provider and sign in; it only takes a few steps."
    override val providerTitle = "Set up your music provider"
    override val providerBody = "CPPlayer connects to online music services through pluggable provider modules. " +
        "Install a recommended provider from the cloud catalog, import a local module (.zip), " +
        "or skip this step and keep using local playback; you can add, remove or switch providers later."
    override val loginTitle = "Sign in to your account"
    override val loginBody = "Sign in with a QR code, email or phone number to sync playlists, favorites, " +
        "comments and messages. You can also continue as a guest; sign-in state is stored per provider."
    override val doneTitle = "You are all set"
    override val doneBody = "Audio quality, sleep timer and appearance live in Settings whenever you want to change them."

    override val doneProviderLabel = "Provider"
    override val doneAccountLabel = "Account"
    override val providerUnconfigured = "Not configured (local playback only)"
    override val loggedIn = "Signed in"
    override val guestMode = "Guest mode"

    override val loginStepLabel = "Step 3 · Sign in"
    override val loginBenefits = "Sign in to sync playlists, favorites, comments and messages; sign-in state is isolated per provider."
    override val loginLater = "Skip sign-in for now"
    override val loginLaterNote = "You can sign in later in Settings → Account"
    override val noProviderTitle = "No provider available to sign in"
    override val noProviderBody = "Signing in needs a provider account. Go back to step 2 to install or select a provider, " +
        "or continue as a guest first."
    override val goConfigureProvider = "Set up a provider"
    override val guestContinue = "Continue as guest"
    override fun loginSuccess(provider: String) = "$provider · Signed in"
    override val continueLabel = "Continue"
    override val accountPlaceholderTitle = "Sign in to your account"
    override val accountPlaceholderBody = "The sign-in form will be embedded here in the real flow."

    override val cloudSection = "Cloud provider catalog (recommended)"
    override val refreshCatalog = "Refresh cloud providers"
    override val cloudDisabledNote = "Turn on cloud access to browse and install recommended providers in one tap."
    override val enableCloudAndFetch = "Enable cloud and fetch providers"
    override val fetchingCatalog = "Fetching the cloud provider catalog…"
    override val installing = "Installing"
    override val installed = "Installed"
    override val incompatible = "Incompatible"
    override val fetch = "Get"
    override val noRecommended = "No recommended providers available"
    override val retry = "Try again"
    override val orUseLocal = "Or use a local module"
    override val importLocal = "Import a local provider module (.zip)"
    override val processingModule = "Processing module…"
    override val loadedProviders = "Loaded providers"
    override val activeProvider = "Active"
    override val localModeTitle = "Without a provider, CPPlayer runs in local music mode"
    override val localModeBody = "You can manage and play audio on this device; import a provider module in Settings " +
        "at any time to unlock online catalogs."
    override val manageProviders = "Open provider management (add, remove, switch, update)"
    override val enableCloudTitle = "Enable the cloud provider catalog"
    override val enableCloudMessage = "CPPlayer will request the official provider catalog to list available providers. " +
        "Provider modules run locally with the same permissions as the app; only continue if you trust the source."
    override val enableCloudConfirm = "Enable and fetch"

    override fun catalogFetchFailed(error: String) = "Couldn't fetch the cloud catalog: $error"
    override fun refreshFailed(error: String) = "Refresh failed: $error"
    override fun installedAndActivated(name: String) = "Installed and activated $name"
    override fun installFailed(error: String) = "Installation failed: $error"
    override fun importedAndActivated(name: String) = "Imported and activated $name"
    override fun updatedAndActivated(name: String) = "Updated and activated $name"
    override fun imported(name: String, active: String) = "Imported $name (still using $active)"
    override fun updated(name: String, active: String) = "Updated $name (still using $active)"
    override val noActiveProvider = "none"
}
