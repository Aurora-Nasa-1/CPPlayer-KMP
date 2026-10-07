package cp.player.app.i18n

/**
 * 账号与登录页、用户 / 歌手主页、云盘页的文案。
 *
 * ### 复用优先
 *
 * 下面**故意不重复**已经在别的分组里的词，调用点直接读那一处，避免同一条文案两种译法：
 *
 * | 调用点 | 复用 |
 * | --- | --- |
 * | 云盘的页标题与卡片标题「云盘」 | `library.cloudDrive` |
 * | 「重试」 | `library.retry` |
 * | 「已加入播放队列」/「将在下一首播放」 | `library.queuedToPlay` / `library.playNext` |
 * | 歌曲收藏提示「已收藏」/「已取消收藏」 | `album.liked` / `album.unliked` |
 * | 「删除歌单」/「取消收藏」及两条确认正文 | `library.deletePlaylist*` / `library.unfavoritePlaylist*` |
 * | 「从云盘删除」 | `library.removeFromCloud` |
 * | 「收起」 | `player.collapse` |
 *
 * 只有**字面量与现成成员不一致**的（如云盘的「全部播放」≠「播放全部」）才在这里新开
 * 成员 —— 中文一律照搬界面上现有的字面量。
 *
 * ### 带数字 / 名字的句子
 *
 * `accountId` / `providerMeta` / `songCount` / `loginWelcome` / `switchedTo` … 都是**函数**：
 * 中英语序不同（`128 首` vs `128 songs`、`已切换到 小明` vs `Switched to Xiaoming`），
 * 在调用方拼就永远译不了。
 *
 * ### 登录方式枚举
 *
 * `LoginChannel` 的显示名存的是 `(CpStrings) -> String`（见 I18N.md §5.3）：枚举构造参数
 * 在类加载时求值，那一刻还没有语言状态。
 */
interface AccountStrings {
    // —— 页面与 Hero ——
    val screenTitle: String
    val loggedIn: String
    val notLoggedIn: String

    /** Hero 下方未登录时的引导语（与 [loginBenefitsNote] 差一个分句，别合并）。 */
    val loggedInHint: String

    /** @param uid 账号 ID（字符串形态：`Long` 与音源侧存的字符串都要走这一条）。 */
    fun accountId(uid: String): String

    // —— 登录区 ——
    val addAccount: String
    val loginMethod: String
    val loggingIn: String
    val channelQr: String
    val channelEmail: String
    val channelPhone: String

    /**
     * 「Cookie 登录」。
     *
     * ⚠️ `Cookie` **刻意保留英文**：它是 HTTP 请求头的字段名，用户是在浏览器里按这个名字
     * 找到它的 —— 译成中文反而对不上。别当漏译改掉。
     */
    val channelCookie: String

    /** @param method 目标登录方式的显示名（[channelEmail] 之类）。 */
    fun switchToMethod(method: String): String

    val guestLogin: String
    val loginByPhone: String

    // —— 「我的」两个入口 ——
    val sectionMine: String
    val myProfile: String
    val myProfileNote: String
    val messages: String
    val messagesNote: String

    // —— 当前音源 / 音源隔离 ——
    val currentProvider: String
    val noProvider: String
    val noProviderNote: String

    /** @param type 音源类型（枚举名）；@param version 音源版本。 */
    fun providerMeta(type: String, version: String): String

    val switchProvider: String
    val sectionIsolation: String
    val isolationTitle: String
    val isolationSubtitle: String
    val isolationHint: String

    // —— 已保存的账号列表 ——
    /** @param name 音源名。 */
    fun accountsOf(name: String): String

    val unknownAccount: String
    val currentlyLoggedIn: String

    /** 账号行副标题里跟在 ID 后面的「· 当前登录」。 */
    val activeSuffix: String

    val removeAccount: String

    /** @param name 账号昵称。 */
    fun removeAccountMessage(name: String): String

    /** 移除的正是当前账号时的追加说明（自带换行前缀）。 */
    val removeAccountActiveNote: String

    /** 移除的不是当前账号时的追加说明（自带换行前缀）。 */
    val removeAccountSavedNote: String

    val removeLabel: String
    val addAccountNote: String
    val loginBenefitsNote: String

    // —— 账号操作 ——
    val sectionAccountActions: String
    val logout: String
    val logoutNote: String

    // —— 扫码登录 ——
    val qrHint: String
    val qrLoadFailed: String
    val refreshQr: String
    val saveQr: String

    /**
     * 「这张二维码是上次没扫完、这次恢复出来的」常显提示（登录页顶部）。
     *
     * 与上面那组 ScreenModel 提示语分开：那些走 `message`，2 秒一次轮询就会把它们覆盖掉，
     * 而这条要一直挂到用户扫完或刷新为止。
     */
    val qrRestored: String

    /** 进程被系统回收后重新打开应用时的启动提示（全局 Snackbar，一进程一次）。 */
    val qrResumePrompt: String

    /** @param name 音源对应的目标 App 名。 */
    fun openApp(name: String): String

    /** @param name 音源对应的目标 App 名。 */
    fun installApp(name: String): String

    // —— 邮箱 / 手机登录 ——
    val emailLabel: String
    val passwordLabel: String
    val passwordNote: String
    val phoneLabel: String
    val imageCaptchaLabel: String

    /** @param action 「换一张」按钮的显示名（句子里要点到它，所以做成函数）。 */
    fun imageCaptchaHint(action: String): String

    val refreshCaptcha: String
    val captchaOrPassword: String
    val sendCaptcha: String

    // —— Cookie 登录 ——
    val cookieLabel: String

    /**
     * 输入框占位符里的示例值。
     *
     * ⚠️ `MUSIC_U` / `__csrf` 是真实字段名，**两种语言都原样保留** —— 用户要照着它
     * 在浏览器里认字段，翻译了就对不上。别当漏译改掉。
     */
    val cookiePlaceholder: String

    val cookieHowTo: String
    val cookieHintEmpty: String
    val cookieHintUnparsed: String

    /** @param count 解析出的字段数。 */
    fun cookieNoSessionKey(count: Int): String

    /** @param count 解析出的字段数；后面接脱敏后的前 120 字符。 */
    fun cookieRecognized(count: Int): String

    // —— ScreenModel 提示语（协程里读不到 CompositionLocal，见 I18N.md §6）——
    val captchaImageFailed: String
    val loginRestored: String
    val qrKeyFailed: String
    val qrExpiredRetry: String
    val qrRefreshing: String
    val qrPollFailed: String
    val qrWaiting: String
    val qrScanned: String
    val qrTimedOut: String

    /** @param reason 异常 message，可空（null 时与迁移前一样显示 `null`）。 */
    fun loginFailed(reason: String?): String

    val pleaseRetry: String
    val cookieFormatError: String
    val cookieInvalid: String

    /** @param name 登录成功的账号昵称。 */
    fun loginWelcome(name: String): String

    val captchaSent: String

    /** @param reason 后端给的失败原因，可空。 */
    fun captchaSendFailed(reason: String?): String

    /** @param reason 异常 message，可空。 */
    fun guestLoginFailed(reason: String?): String

    val loginFailedRetry: String
    val loginCheckFailed: String

    /** @param name 切换到的账号昵称。 */
    fun switchedTo(name: String): String

    /** @param name 登录态已失效的账号昵称。 */
    fun accountExpired(name: String): String

    /** @param name 被移除的账号昵称（移除的是当前账号的登录态）。 */
    fun removedSession(name: String): String

    /** @param name 被移除的账号昵称（不是当前账号）。 */
    fun removedAccount(name: String): String

    val loggedOut: String

    // —— 用户 / 歌手主页 ——
    val profileFallbackTitle: String
    val profileLoading: String
    val profileLoadingNote: String
    val profileNotOpened: String
    val profileLoadFailed: String
    val topSongs: String

    /** @param count 热门歌曲条数（分组头右侧的计数）。 */
    fun songCount(count: Int): String

    /** @param count 全部热门歌曲条数（「展开全部」按钮）。 */
    fun expandAllSongs(count: Int): String

    val albumsSection: String

    /** @param count 专辑数。 */
    fun albumCount(count: Int): String

    val playlistsSection: String

    /** @param count 歌单数。 */
    fun playlistCount(count: Int): String

    val artistEmptyTitle: String
    val artistEmptyNote: String
    val noPublicPlaylists: String
    val unknownUser: String
    val statAlbums: String
    val statFollowers: String
    val statPlaylists: String
    val statFollowing: String
    val sendMessage: String

    // —— 云盘 ——
    val cloudSyncing: String
    val cloudUploadHint: String

    /** @param count 已加载条数（还有更多时）。 */
    fun cloudLoadedPartial(count: Int): String

    /** @param count 歌曲总数（全部加载完时）。 */
    fun cloudLoadedTotal(count: Int): String

    val cloudPlayAll: String
    val cloudLoading: String
    val cloudLoadingNote: String
    val cloudLoadFailed: String
    val cloudEmpty: String
    val cloudEmptyNote: String
    val cloudLoadMoreFailed: String
    val cloudLoadingMore: String
    val cloudDelete: String

    /** @param name 歌曲名。 */
    fun cloudDeleteMessage(name: String): String

    /** @param name 歌曲名。 */
    fun cloudDeleted(name: String): String

    val cloudDeleteFailed: String
}

/** 简体中文实现（界面现有字面量照搬）。 */
object AccountStringsZh : AccountStrings {
    override val screenTitle = "账号与登录"
    override val loggedIn = "已登录"
    override val notLoggedIn = "未登录"
    override val loggedInHint = "登录后可同步歌单、红心与播放记录"
    override fun accountId(uid: String) = "ID: $uid"

    override val addAccount = "添加账号"
    override val loginMethod = "登录方式"
    override val loggingIn = "登录中…"
    override val channelQr = "扫码登录"
    override val channelEmail = "邮箱登录"
    override val channelPhone = "手机号登录"
    override val channelCookie = "Cookie 登录"
    override fun switchToMethod(method: String) = "改用$method"
    override val guestLogin = "游客登录 / 跳过"
    override val loginByPhone = "手机登录"

    override val sectionMine = "我的"
    override val myProfile = "我的主页"
    override val myProfileNote = "歌曲、专辑与歌单，和你在别人主页看到的是同一套"
    override val messages = "消息"
    override val messagesNote = "最近联系人与私信"

    override val currentProvider = "当前音源"
    override val noProvider = "尚未加载音源"
    override val noProviderNote = "先在音源管理里导入一个 Provider 模块"
    override fun providerMeta(type: String, version: String) = "$type · v$version"
    override val switchProvider = "切换音源"
    override val sectionIsolation = "音源隔离"
    override val isolationTitle = "切音源时同步刷新账号资料"
    override val isolationSubtitle = "关闭后仍会切换登录态（登录态本来就按音源分开存），" +
        "只是不立即重新拉取昵称与头像"
    override val isolationHint = "每个音源都有自己的登录态、缓存与账号列表，互不共享。"

    override fun accountsOf(name: String) = "$name 的账号"
    override val unknownAccount = "未知账号"
    override val currentlyLoggedIn = "当前登录"
    override val activeSuffix = " · 当前登录"
    override val removeAccount = "移除账号"
    override fun removeAccountMessage(name: String) = "确定移除「$name」吗？"
    override val removeAccountActiveNote = "\n它正是当前账号，移除后将退出登录，需要重新登录。"
    override val removeAccountSavedNote = "\n已保存的登录凭据会被删除，之后需要重新登录。"
    override val removeLabel = "移除"
    override val addAccountNote = "再登录一个账号，之后可在这里一键切换"
    override val loginBenefitsNote = "登录后可同步歌单、红心与播放记录；登录态只保存在当前音源内。"

    override val sectionAccountActions = "账号操作"
    override val logout = "退出登录"
    override val logoutNote = "清除当前音源的登录态；已保存的账号会保留，方便一键切回"

    override val qrHint = "请使用音源对应的 App 扫描二维码登录"
    override val qrLoadFailed = "二维码加载失败"
    override val refreshQr = "刷新二维码"
    override val saveQr = "保存二维码"
    override val qrRestored = "已恢复上次没扫完的二维码 —— 用手机接着扫这一张即可；" +
        "若已经失效，刷新一次就能拿到新的。"
    override val qrResumePrompt = "上次的扫码登录还没完成，二维码已经为你保留；到「账号」页可以接着扫。"
    override fun openApp(name: String) = "打开 $name"
    override fun installApp(name: String) = "安装 $name"

    override val emailLabel = "邮箱"
    override val passwordLabel = "密码"
    override val passwordNote = "密码只发给当前音源，不会离开本机。"
    override val phoneLabel = "手机号"
    override val imageCaptchaLabel = "图形验证码"
    override fun imageCaptchaHint(action: String) = "音源要求人机校验；看不清点「$action」"
    override val refreshCaptcha = "换一张"
    override val captchaOrPassword = "验证码 / 密码"
    override val sendCaptcha = "发送验证码"

    override val cookieLabel = "Cookie"
    override val cookiePlaceholder = "MUSIC_U=…; __csrf=…"
    override val cookieHowTo = "获取方式：浏览器登录后按 F12 → 网络（Network）→ 任选一个请求 → " +
        "复制请求头里的 Cookie 整行。登录态等同于密码，只存在本机、" +
        "并按音源隔离，不会发给当前音源以外的任何一方。"
    override val cookieHintEmpty = "整行粘贴即可：会自动去掉「Cookie:」前缀、换行与多余空格。"
    override val cookieHintUnparsed = "没解析出任何 name=value —— 请确认复制的是 Cookie，而不是网址或整段请求。"
    override fun cookieNoSessionKey(count: Int) = "已识别 $count 个字段，但其中没有常见的会话字段，多半是复制错了。"
    override fun cookieRecognized(count: Int) = "已识别 $count 个字段："

    override val captchaImageFailed = "图形验证码获取失败，可留空直接发送短信验证码"
    override val loginRestored = "已恢复登录"
    override val qrKeyFailed = "获取二维码 key 失败"
    override val qrExpiredRetry = "二维码反复过期，请点「刷新二维码」重试"
    override val qrRefreshing = "二维码已过期，正在重新获取…"
    override val qrPollFailed = "查询扫码状态连续失败，请检查网络后刷新二维码"
    override val qrWaiting = "等待扫码…"
    override val qrScanned = "已扫码，请在手机上确认登录"
    override val qrTimedOut = "二维码已超时，请点「刷新二维码」重试"
    override fun loginFailed(reason: String?) = "登录失败: $reason"
    override val pleaseRetry = "请重试"
    override val cookieFormatError = "Cookie 格式不对：至少要有一个 name=value"
    override val cookieInvalid = "Cookie 无效或已过期，请重新获取"
    override fun loginWelcome(name: String) = "登录成功，欢迎 $name"
    override val captchaSent = "验证码已发送"
    override fun captchaSendFailed(reason: String?) = "验证码发送失败: $reason"
    override fun guestLoginFailed(reason: String?) = "游客登录失败: $reason"
    override val loginFailedRetry = "登录失败，请重试"
    override val loginCheckFailed = "登录态校验失败，请重试"
    override fun switchedTo(name: String) = "已切换到 $name"
    override fun accountExpired(name: String) = "「$name」的登录态已失效，已回到原账号"
    override fun removedSession(name: String) = "已移除「$name」的登录态"
    override fun removedAccount(name: String) = "已移除「$name」"
    override val loggedOut = "已退出登录"

    override val profileFallbackTitle = "主页"
    override val profileLoading = "正在载入主页"
    override val profileLoadingNote = "正在从当前音源读取资料"
    override val profileNotOpened = "没有打开这个主页"
    override val profileLoadFailed = "加载失败"
    override val topSongs = "热门歌曲"
    override fun songCount(count: Int) = "$count 首"
    override fun expandAllSongs(count: Int) = "展开全部 $count 首"
    override val albumsSection = "专辑"
    override fun albumCount(count: Int) = "$count 张"
    override val playlistsSection = "歌单"
    override fun playlistCount(count: Int) = "$count 个"
    override val artistEmptyTitle = "这位歌手还没有可展示的内容"
    override val artistEmptyNote = "换个音源可能能看到更多"
    override val noPublicPlaylists = "TA 还没有公开的歌单"
    override val unknownUser = "未知用户"
    override val statAlbums = "专辑"
    override val statFollowers = "粉丝"
    override val statPlaylists = "歌单"
    override val statFollowing = "关注"
    override val sendMessage = "发私信"

    override val cloudSyncing = "正在同步…"
    override val cloudUploadHint = "上传后可跨设备播放"
    override fun cloudLoadedPartial(count: Int) = "已加载 $count 首 · 下滑继续加载"
    override fun cloudLoadedTotal(count: Int) = "$count 首歌曲 · 上传后可跨设备播放"
    override val cloudPlayAll = "全部播放"
    override val cloudLoading = "正在加载云盘"
    override val cloudLoadingNote = "正在同步云盘歌曲"
    override val cloudLoadFailed = "云盘加载失败"
    override val cloudEmpty = "云盘空空如也"
    override val cloudEmptyNote = "把歌曲上传到云盘后会显示在这里"
    override val cloudLoadMoreFailed = "加载更多失败"
    override val cloudLoadingMore = "正在加载更多"
    override val cloudDelete = "删除"
    override fun cloudDeleteMessage(name: String) = "确定从云盘删除「$name」吗？删除后无法恢复。"
    override fun cloudDeleted(name: String) = "已从云盘删除「$name」"
    override val cloudDeleteFailed = "删除失败"
}

/** 英文实现。 */
object AccountStringsEn : AccountStrings {
    override val screenTitle = "Account & sign-in"
    override val loggedIn = "Signed in"
    override val notLoggedIn = "Not signed in"
    override val loggedInHint = "Sign in to sync playlists, favorites and play history"
    override fun accountId(uid: String) = "ID: $uid"

    override val addAccount = "Add account"
    override val loginMethod = "Sign-in method"
    override val loggingIn = "Signing in…"
    override val channelQr = "Scan a QR code"
    override val channelEmail = "Sign in with email"
    override val channelPhone = "Sign in with a phone number"
    override val channelCookie = "Sign in with a Cookie"
    override fun switchToMethod(method: String) = "Use $method instead"
    override val guestLogin = "Continue as guest / Skip"
    override val loginByPhone = "Sign in with phone"

    override val sectionMine = "Me"
    override val myProfile = "My profile"
    override val myProfileNote = "Songs, albums and playlists — the same view others see on your profile"
    override val messages = "Messages"
    override val messagesNote = "Recent contacts and direct messages"

    override val currentProvider = "Current provider"
    override val noProvider = "No provider loaded"
    override val noProviderNote = "Import a provider module in provider management first"
    override fun providerMeta(type: String, version: String) = "$type · v$version"
    override val switchProvider = "Switch provider"
    override val sectionIsolation = "Provider isolation"
    override val isolationTitle = "Refresh the profile when switching providers"
    override val isolationSubtitle = "Sign-in state still switches (it is kept per provider) — " +
        "this only skips refetching the nickname and avatar right away"
    override val isolationHint = "Each provider has its own sign-in state, cache and account list — nothing is shared."

    override fun accountsOf(name: String) = "$name accounts"
    override val unknownAccount = "Unknown account"
    override val currentlyLoggedIn = "Signed in"
    override val activeSuffix = " · Signed in"
    override val removeAccount = "Remove account"
    override fun removeAccountMessage(name: String) = "Remove \"$name\"?"
    override val removeAccountActiveNote = "\nThis is the account currently signed in — " +
        "removing it signs you out, and you will need to sign in again."
    override val removeAccountSavedNote = "\nIts saved credentials will be deleted, and you will need to sign in again."
    override val removeLabel = "Remove"
    override val addAccountNote = "Sign in to another account, then switch back here in one tap"
    override val loginBenefitsNote = "Sign in to sync playlists, favorites and play history; " +
        "the sign-in state is kept inside the current provider."

    override val sectionAccountActions = "Account actions"
    override val logout = "Sign out"
    override val logoutNote = "Clears this provider's sign-in state; saved accounts stay so you can switch back"

    override val qrHint = "Scan the QR code with the app that matches this provider"
    override val qrLoadFailed = "Couldn't load the QR code"
    override val refreshQr = "Refresh QR code"
    override val saveQr = "Save QR code"
    override val qrRestored = "Restored the QR code you didn't finish scanning — " +
        "keep scanning the same one on your phone; if it has expired, refresh for a new one."
    override val qrResumePrompt = "Your last QR sign-in wasn't finished and the code was kept — " +
        "open the Account page to continue."
    override fun openApp(name: String) = "Open $name"
    override fun installApp(name: String) = "Install $name"

    override val emailLabel = "Email"
    override val passwordLabel = "Password"
    override val passwordNote = "The password is only sent to the current provider and never leaves this device."
    override val phoneLabel = "Phone number"
    override val imageCaptchaLabel = "Image captcha"
    override fun imageCaptchaHint(action: String) = "This provider requires a human check; tap \"$action\" if it's unreadable"
    override val refreshCaptcha = "Get another"
    override val captchaOrPassword = "Verification code / Password"
    override val sendCaptcha = "Send code"

    override val cookieLabel = "Cookie"
    override val cookiePlaceholder = "MUSIC_U=…; __csrf=…"
    override val cookieHowTo = "How to get it: sign in in a browser, press F12 → Network → pick any request → " +
        "copy the whole Cookie line from its request headers. It is as sensitive as a password: " +
        "it stays on this device, is isolated per provider, and is never sent to anyone but the current provider."
    override val cookieHintEmpty = "Just paste the whole line — the \"Cookie:\" prefix, line breaks and extra spaces are stripped automatically."
    override val cookieHintUnparsed = "No name=value was found — make sure you copied a Cookie, not a URL or a whole request."
    override fun cookieNoSessionKey(count: Int) =
        "Recognized $count fields, but none of them looks like a session field — you probably copied the wrong thing."
    override fun cookieRecognized(count: Int) = "Recognized $count fields: "

    override val captchaImageFailed = "Couldn't load the image captcha — leave it blank and request the SMS code anyway"
    override val loginRestored = "Sign-in restored"
    override val qrKeyFailed = "Couldn't get a QR code key"
    override val qrExpiredRetry = "The QR code kept expiring — tap \"Refresh QR code\" to try again"
    override val qrRefreshing = "The QR code expired, fetching a new one…"
    override val qrPollFailed = "Repeatedly failed to check the scan status — check your network, then refresh the QR code"
    override val qrWaiting = "Waiting for the scan…"
    override val qrScanned = "Scanned — confirm the sign-in on your phone"
    override val qrTimedOut = "The QR code timed out — tap \"Refresh QR code\" to try again"
    override fun loginFailed(reason: String?) = "Sign-in failed: $reason"
    override val pleaseRetry = "Please try again"
    override val cookieFormatError = "That Cookie doesn't parse — it needs at least one name=value"
    override val cookieInvalid = "The Cookie is invalid or expired — get a new one"
    override fun loginWelcome(name: String) = "Signed in — welcome, $name"
    override val captchaSent = "Verification code sent"
    override fun captchaSendFailed(reason: String?) = "Couldn't send the code: $reason"
    override fun guestLoginFailed(reason: String?) = "Guest sign-in failed: $reason"
    override val loginFailedRetry = "Sign-in failed — please try again"
    override val loginCheckFailed = "Couldn't verify the sign-in state — please try again"
    override fun switchedTo(name: String) = "Switched to $name"
    override fun accountExpired(name: String) = "\"$name\"'s sign-in has expired — you are back on the previous account"
    override fun removedSession(name: String) = "Removed the sign-in state for \"$name\""
    override fun removedAccount(name: String) = "Removed \"$name\""
    override val loggedOut = "Signed out"

    override val profileFallbackTitle = "Profile"
    override val profileLoading = "Loading profile"
    override val profileLoadingNote = "Reading the profile from the current provider"
    override val profileNotOpened = "Couldn't open this profile"
    override val profileLoadFailed = "Failed to load"
    override val topSongs = "Top songs"
    override fun songCount(count: Int) = if (count == 1) "1 song" else "$count songs"
    override fun expandAllSongs(count: Int) = "Show all $count songs"
    override val albumsSection = "Albums"
    override fun albumCount(count: Int) = if (count == 1) "1 album" else "$count albums"
    override val playlistsSection = "Playlists"
    override fun playlistCount(count: Int) = if (count == 1) "1 playlist" else "$count playlists"
    override val artistEmptyTitle = "This artist has nothing to show yet"
    override val artistEmptyNote = "Another provider may have more"
    override val noPublicPlaylists = "They have no public playlists yet"
    override val unknownUser = "Unknown user"
    override val statAlbums = "Albums"
    override val statFollowers = "Followers"
    override val statPlaylists = "Playlists"
    override val statFollowing = "Following"
    override val sendMessage = "Send message"

    override val cloudSyncing = "Syncing…"
    override val cloudUploadHint = "Upload songs to play them on any device"
    override fun cloudLoadedPartial(count: Int) = "Loaded $count songs · scroll down for more"
    override fun cloudLoadedTotal(count: Int) = "$count songs · upload them to play across devices"
    override val cloudPlayAll = "Play all"
    override val cloudLoading = "Loading your cloud drive"
    override val cloudLoadingNote = "Syncing cloud drive songs"
    override val cloudLoadFailed = "Couldn't load the cloud drive"
    override val cloudEmpty = "Your cloud drive is empty"
    override val cloudEmptyNote = "Songs you upload to the cloud drive will show up here"
    override val cloudLoadMoreFailed = "Couldn't load more"
    override val cloudLoadingMore = "Loading more"
    override val cloudDelete = "Delete"
    override fun cloudDeleteMessage(name: String) = "Delete \"$name\" from the cloud drive? This can't be undone."
    override fun cloudDeleted(name: String) = "Deleted \"$name\" from the cloud drive"
    override val cloudDeleteFailed = "Delete failed"
}
