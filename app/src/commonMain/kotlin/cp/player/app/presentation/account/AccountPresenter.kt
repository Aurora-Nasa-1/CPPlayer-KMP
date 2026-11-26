package cp.player.app.presentation.account

import cp.player.app.AppModel
import cp.player.app.auth.AccountStore
import cp.player.app.auth.CookieLogin
import cp.player.app.auth.QrAutoSaveTracker
import cp.player.app.auth.QrLoginSession
import cp.player.app.auth.QrLoginStore
import cp.player.app.i18n.AccountStrings
import cp.player.app.i18n.CpStrings
import cp.player.app.platform.ImageSaveResult
import cp.player.app.platform.isPackageInstalled
import cp.player.app.platform.openTargetApp
import cp.player.app.platform.openWebview
import cp.player.app.platform.saveImageToLocal
import cp.player.app.platform.saveQrCodeToGallery
import cp.player.app.repository.LoginSessionCheck
import cp.player.app.repository.QrStatusKind
import cp.player.core.provider.ProviderCapability
import cp.player.core.util.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64

private fun decodeBase64Image(raw: String): ByteArray? = runCatching {
    Base64.decode(raw.substringAfter("base64,").trim())
}.getOrNull()

/**
 * 账号与登录 ScreenModel。
 */
class AccountPresenter(
    private val account: AccountStrings,
    val scope: CoroutineScope,
    private val qrPngBytes: (String) -> ByteArray? = { null },
) {
    val activeProvider: StateFlow<cp.player.core.provider.BackendProvider?> = AppModel.activeProviderFlow
    val isLoading = MutableStateFlow(false)
    val isSavingQr = MutableStateFlow(false)
    val isLogged = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val qrUrl = MutableStateFlow<String?>(null)
    val qrImgBase64 = MutableStateFlow<String?>(null)
    val qrRestored = MutableStateFlow(false)
    val targetAppName = MutableStateFlow<String?>(null)
    val targetAppInstalled = MutableStateFlow(false)
    val accounts = MutableStateFlow<List<AccountStore.SavedAccount>>(emptyList())
    val method = MutableStateFlow(LoginChannel.QR)
    val showForm = MutableStateFlow(false)

    val loginChannels = MutableStateFlow(LoginChannel.DEFAULT)
    val supportsCaptchaImage = MutableStateFlow(false)
    /** Provider-declared country code; non-null hides the manual input. */
    val defaultCountryCode = MutableStateFlow<String?>(null)
    val captchaImage = MutableStateFlow<String?>(null)
    val imageCaptcha = MutableStateFlow("")
    val verifyUrl = MutableStateFlow<String?>(null)
    private var captchaSessionCookie: String? = null

    private var qrJob: Job? = null

    /**
     * 「跳转前自动保存」的去重账本（同一张二维码只写一次相册）。
     *
     * 与 [qrJob] 同级：随 ScreenModel 生命周期存在，**换音源时**跟着现场一起 reset，
     * 别让上一个音源的「已存过」拦下新音源的第一张二维码。
     * 二维码本身换新（刷新 / 过期重取）不需要 reset —— 账本比的是图片内容，新图自然不同。
     */
    private val qrAutoSave = QrAutoSaveTracker()

    init {
        scope.launch {
            var hasPreviousEmission = false
            var lastProviderId: String? = null
            activeProvider.collectLatest { provider ->
                val currentProviderId = provider?.id
                val isProviderSwitched = hasPreviousEmission && lastProviderId != currentProviderId
                hasPreviousEmission = true
                lastProviderId = currentProviderId

                if (isProviderSwitched || provider == null) {
                    cancelQrPolling()
                    qrAutoSave.reset()
                    // 作废的登录任务不会再清加载态（它已经不"属于"当前音源了），
                    // 这里必须自己收掉，否则新音源没有扫码通道时转圈会一直停在屏上。
                    isLoading.value = false
                    qrUrl.value = null
                    qrImgBase64.value = null
                    qrRestored.value = false
                    captchaImage.value = null
                    captchaSessionCookie = null
                    verifyUrl.value = null
                    message.value = null
                    isLogged.value = false
                    accounts.value = emptyList()
                }

                if (provider == null) {
                    targetAppName.value = null
                    targetAppInstalled.value = false
                    loginChannels.value = emptyList()
                    supportsCaptchaImage.value = false
                    defaultCountryCode.value = null
                    return@collectLatest
                }

                val pkg = provider.targetAppPackage?.takeIf { it.isNotBlank() }
                if (pkg != null) {
                    targetAppName.value = provider.name
                    targetAppInstalled.value = isPackageInstalled(pkg)
                } else {
                    targetAppName.value = null
                    targetAppInstalled.value = false
                }
                val declared = provider.loginMethods
                    ?.map { it.trim().lowercase() }
                    ?.filter { it.isNotEmpty() }
                val explicitlyNoAuth = declared == null &&
                    !provider.capabilities.isNullOrEmpty() &&
                    !provider.isFeatureAvailable(ProviderCapability.QR_LOGIN)
                loginChannels.value = when {
                    !declared.isNullOrEmpty() -> buildList {
                        if ("qr" in declared) add(LoginChannel.QR)
                        if ("sms" in declared || "phone" in declared) add(LoginChannel.PHONE)
                        if ("email" in declared) add(LoginChannel.EMAIL)
                        add(LoginChannel.COOKIE)
                    }
                    explicitlyNoAuth -> listOf(LoginChannel.COOKIE)
                    else -> LoginChannel.DEFAULT
                }
                supportsCaptchaImage.value = declared?.contains("captchaimage") == true
                defaultCountryCode.value = provider.defaultCountryCode
                if (method.value !in loginChannels.value) {
                    method.value = loginChannels.value.first()
                }
                val binding = bindProvider()
                checkLoginStatus(binding)
                if (!binding.isCurrent()) return@collectLatest
                if (!isLogged.value && method.value == LoginChannel.QR && LoginChannel.QR in loginChannels.value) {
                    restoreOrFetchQrCode()
                }
            }
        }
    }

    fun providerId(): String = activeProvider.value?.id ?: AppModel.activeProviderId()

    /**
     * 一次登录动作所属的音源现场（音源 id + 音源代际）。
     *
     * ### 为什么登录链路必须带上它
     *
     * 二维码 / 验证码都是**跨音源一定失效**的长事务：请求发出后用户完全可能
     * 正好切了音源（「登录页 → 切换音源」就在同一屏、同一个栈里）。响应回来时「当前音源」
     * 已经换了人，如果这时按**当前**音源回写，就会：
     * 1. 把 A 音源的二维码存成 B 音源的现场（[QrLoginStore]），下次进登录页还会被
     *    **恢复**出来 —— 看起来就是「切了音源，登录页还是上个音源的二维码」，
     *    而且重启后依然在（现场有 5 分钟寿命）；
     * 2. 把 A 音源的 cookie 写进 B 音源的 cookie 槽位（见 [onLoginSucceeded]）。
     *
     * 所以每个登录动作在**发起时**抓一份现场，所有回写前先核对它还是不是当前音源。
     * 注意音源实例用 `!==` 比较是不够的：切走再切回拿到的是**同一个实例**，
     * 必须靠 [AppModel.sourceGeneration] 区分「中间发生过切换」。
     */
    private data class ProviderBinding(val providerId: String, val generation: Int)

    private fun bindProvider(): ProviderBinding =
        ProviderBinding(providerId(), AppModel.sourceGeneration.value)

    private fun ProviderBinding.isCurrent(): Boolean =
        generation == AppModel.sourceGeneration.value && providerId == providerId()

    /**
     * 调一次登录接口，**不吞协程取消**。
     *
     * `runCatching { … }` 会把 `CancellationException` 一起吞掉：`cancelQrPolling()` 之后
     * 旧任务并不会立刻停，还会继续往下走 —— 把提示改成「获取失败 / 轮询失败」，甚至把
     * 结果写回界面。这里只吞普通异常，取消一律上抛（与取数边界
     * `UnifiedMusicSourceImpl.boundary` 是同一个约定）。
     */
    private suspend fun <T> loginAttempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    fun setChannel(channel: LoginChannel) {
        method.value = channel
        if (channel == LoginChannel.QR) restoreOrFetchQrCode() else cancelQrPolling()
        if (channel == LoginChannel.PHONE && supportsCaptchaImage.value) fetchCaptchaImage()
    }

    fun openVerifyUrl() {
        verifyUrl.value?.let { openWebview(it, account.verifyRequiredTitle) }
    }

    fun clearVerifyUrl() {
        verifyUrl.value = null
    }

    fun fetchCaptchaImage() {
        if (!supportsCaptchaImage.value) return
        scope.launch {
            val binding = bindProvider()
            val res = loginAttempt { AppModel.authRepository.getCaptchaImage() }
            if (!binding.isCurrent()) return@launch
            if (res != null && res.ok && !res.captchaImage.isNullOrBlank()) {
                captchaImage.value = res.captchaImage
                captchaSessionCookie = res.sessionCookie
            } else {
                captchaImage.value = null
                captchaSessionCookie = null
                message.value = account.captchaImageFailed
            }
        }
    }

    fun startAddAccount() {
        showForm.value = true
        restoreOrFetchQrCode()
    }

    fun cancelAddAccount() {
        showForm.value = false
        cancelQrPolling()
    }

    private fun refreshAccounts() {
        val providerId = providerId()
        val stored = AccountStore.list(providerId)
        val profile = AppModel.userProfileFlow.value
        val currentUid = profile?.resourceId
        val merged = if (isLogged.value && profile != null && stored.none { it.uid == currentUid }) {
            stored + AccountStore.SavedAccount(
                uid = currentUid ?: "",
                nickname = profile.nickname,
                avatarUrl = profile.avatarUrl,
                cookie = AppModel.cookieStorage.getCookie(providerId) ?: "",
            )
        } else {
            stored
        }
        accounts.value = merged
    }

    private suspend fun checkLoginStatus(binding: ProviderBinding = bindProvider()) {
        val check = try {
            AppModel.authRepository.checkLoginSession()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            LoginSessionCheck.NetworkError
        }
        // 请求期间切了音源 ⇒ 这份「登录态」属于上一个音源，不能拿来判当前音源已登录。
        if (!binding.isCurrent()) return
        when (check) {
            LoginSessionCheck.LoggedIn -> {
                isLogged.value = true
                runCatching { AppModel.refreshUserProfileAwait() }
                if (!binding.isCurrent()) return
                message.value = account.loginRestored
            }
            LoginSessionCheck.NetworkError -> {
                // 弱网或离线：检查当前音源是否已有已保存的账号或 cookie，避免误判为「未登录」
                val providerId = binding.providerId
                val hasCachedAccount = AccountStore.activeUid(providerId) != null ||
                    !AppModel.cookieStorage.getCookie(providerId).isNullOrEmpty()
                if (hasCachedAccount) {
                    isLogged.value = true
                    runCatching { AppModel.refreshUserProfileAwait() }
                    if (!binding.isCurrent()) return
                } else {
                    isLogged.value = false
                }
            }
            LoginSessionCheck.LoggedOut -> {
                isLogged.value = false
            }
        }
        refreshAccounts()
    }

    fun fetchQrCode() {
        qrRestored.value = false
        startQrJob(initialKey = null, reuseQr = false)
    }

    private fun restoreOrFetchQrCode() {
        if (qrJob?.isActive == true) return
        if (LoginChannel.QR !in loginChannels.value) {
            fetchQrCode()
            return
        }
        val session = QrLoginStore.load(providerId())
        if (session == null) {
            fetchQrCode()
            return
        }
        method.value = LoginChannel.QR
        qrUrl.value = session.url
        qrImgBase64.value = session.imageBase64
        qrRestored.value = true
        startQrJob(initialKey = session.key, reuseQr = true)
    }

    private fun startQrJob(initialKey: String?, reuseQr: Boolean) {
        cancelQrPolling()
        qrJob = scope.launch {
            // 这一整轮扫码只认发起时的音源（见 [ProviderBinding]）：中途换源 ⇒ 直接作废，
            // 既不上屏、也不落盘，更不会把旧音源的 key 拿去轮询。
            val binding = bindProvider()
            isLoading.value = true
            try {
                var pendingKey = initialKey
                var pendingReuse = reuseQr
                repeat(QR_MAX_ROUNDS) { round ->
                    val key = pendingKey
                        ?: loginAttempt { AppModel.authRepository.getQrKey() }?.key
                    val reuse = pendingReuse
                    pendingKey = null
                    pendingReuse = false
                    if (key == null) {
                        if (binding.isCurrent()) message.value = account.qrKeyFailed
                        return@launch
                    }
                    if (!reuse) {
                        val qrResp = loginAttempt { AppModel.authRepository.createQrCode(key) }
                        // 取二维码期间换了音源 ⇒ 这张图是上一个音源的，不能给用户看，
                        // 更不能记成新音源的现场（否则下次进登录页会被原样"恢复"出来）。
                        if (!binding.isCurrent()) return@launch
                        val url = qrResp?.url
                        val img = qrResp?.imageBase64
                        qrUrl.value = url
                        qrImgBase64.value = img
                        if (url == null) {
                            message.value = account.qrLoadFailed
                            return@launch
                        }
                        qrRestored.value = false
                        QrLoginStore.save(
                            binding.providerId,
                            QrLoginSession(
                                key = key,
                                url = url,
                                imageBase64 = img,
                                createdAtMs = currentTimeMillis(),
                            ),
                        )
                    }
                    isLoading.value = false
                    when (pollQrStatus(key, binding)) {
                        QrPollResult.LOGGED_IN -> return@launch
                        QrPollResult.STOPPED -> {
                            if (!isLogged.value && binding.isCurrent()) {
                                QrLoginStore.clear(binding.providerId)
                            }
                            return@launch
                        }
                        QrPollResult.EXPIRED ->
                            if (round == QR_MAX_ROUNDS - 1) {
                                if (binding.isCurrent()) message.value = account.qrExpiredRetry
                            } else {
                                if (binding.isCurrent()) {
                                    message.value = account.qrRefreshing
                                    isLoading.value = true
                                }
                            }
                    }
                }
            } finally {
                // 换源后新的一轮扫码会自己管加载态，别被作废的旧任务清掉。
                if (binding.isCurrent()) isLoading.value = false
            }
        }
    }

    private fun cancelQrPolling() {
        qrJob?.cancel()
        qrJob = null
    }

    private suspend fun pollQrStatus(key: String, binding: ProviderBinding): QrPollResult {
        var consecutiveFailures = 0
        repeat(QR_POLL_MAX) {
            kotlinx.coroutines.delay(QR_POLL_INTERVAL_MS)
            // 换源后这张二维码在这个音源上根本不存在，继续轮询只会拿到 800/错误。
            if (!binding.isCurrent()) return QrPollResult.STOPPED
            val resp = loginAttempt { AppModel.authRepository.checkQrStatus(key) }
            if (!binding.isCurrent()) return QrPollResult.STOPPED
            if (resp == null) {
                consecutiveFailures++
                if (consecutiveFailures >= QR_FAILURE_LIMIT) {
                    message.value = account.qrPollFailed
                    return QrPollResult.STOPPED
                }
            } else {
                consecutiveFailures = 0
                when (resp.status) {
                    QrStatusKind.WAITING -> message.value = account.qrWaiting
                    QrStatusKind.SCANNED -> message.value = account.qrScanned
                    QrStatusKind.SUCCESS -> {
                        onLoginSucceeded(resp.cookie, binding = binding)
                        return QrPollResult.LOGGED_IN
                    }
                    QrStatusKind.EXPIRED -> return QrPollResult.EXPIRED
                    QrStatusKind.UNKNOWN -> Unit
                }
            }
            if (isLogged.value) return QrPollResult.LOGGED_IN
        }
        message.value = account.qrTimedOut
        return QrPollResult.STOPPED
    }

    fun loginEmail(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) return
        cancelQrPolling()
        scope.launch {
            val binding = bindProvider()
            isLoading.value = true
            try {
                val actionResult = AppModel.authRepository.login(email, password)
                // 请求期间换了音源 ⇒ 这是上一个音源发起的登录，cookie 不能记到新音源名下。
                if (!binding.isCurrent()) return@launch
                if (!actionResult.ok) {
                    if (actionResult.verifyUrl != null || actionResult.isVerificationChallenge) {
                        verifyUrl.value = actionResult.verifyUrl
                        message.value = account.verifyRequired
                        return@launch
                    }
                    message.value = account.loginFailed(actionResult.reason ?: account.pleaseRetry)
                    return@launch
                }
                onLoginSucceeded(actionResult.cookie, ok = true, binding = binding)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (binding.isCurrent()) {
                    message.value = account.loginFailed(e.message)
                }
            } finally {
                if (binding.isCurrent()) isLoading.value = false
            }
        }
    }

    fun loginPhone(
        phone: String,
        codeOrPass: String,
        useSmsCode: Boolean,
        countryCode: String? = null,
    ) {
        if (phone.isBlank()) return
        cancelQrPolling()
        scope.launch {
            val binding = bindProvider()
            isLoading.value = true
            try {
                val actionResult = AppModel.authRepository.loginWithPhone(
                    phone = phone,
                    codeOrPass = codeOrPass,
                    useSmsCode = useSmsCode,
                    countryCode = countryCode?.takeIf { it.isNotBlank() },
                    imageCaptcha = imageCaptcha.value.ifBlank { null },
                    captchaCookie = captchaSessionCookie,
                )
                if (!binding.isCurrent()) return@launch
                if (!actionResult.ok) {
                    if (actionResult.verifyUrl != null || actionResult.isVerificationChallenge) {
                        verifyUrl.value = actionResult.verifyUrl
                        message.value = account.verifyRequired
                        return@launch
                    }
                    message.value = account.loginFailed(actionResult.reason ?: account.pleaseRetry)
                    fetchCaptchaImage()
                    return@launch
                }
                onLoginSucceeded(actionResult.cookie, ok = true, binding = binding)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (binding.isCurrent()) {
                    message.value = account.loginFailed(e.message)
                }
            } finally {
                if (binding.isCurrent()) isLoading.value = false
            }
        }
    }

    fun loginWithCookie(raw: String) {
        val cookie = CookieLogin.normalize(raw)
        if (cookie == null) {
            message.value = account.cookieFormatError
            return
        }
        cancelQrPolling()
        scope.launch {
            val binding = bindProvider()
            val providerId = binding.providerId
            isLoading.value = true
            try {
                val previous = AppModel.cookieStorage.getCookie(providerId)
                AppModel.cookieStorage.saveCookie(providerId, cookie)
                val profile = AppModel.refreshUserProfileAwait()
                // 验证期间换了音源：上面的 cookie 属于旧音源，收手即可
                // （切源时 `AppModel.switchOrReport` 会重新拉新音源的资料）。
                if (!binding.isCurrent()) return@launch
                if (profile == null) {
                    if (previous.isNullOrEmpty()) AppModel.cookieStorage.clear(providerId)
                    else AppModel.cookieStorage.saveCookie(providerId, previous)
                    AppModel.refreshUserProfileAwait()
                    isLogged.value = false
                    message.value = account.cookieInvalid
                    return@launch
                }
                QrLoginStore.clear(providerId)
                AccountStore.save(
                    providerId,
                    AccountStore.SavedAccount(
                        uid = profile.resourceId,
                        nickname = profile.nickname,
                        avatarUrl = profile.avatarUrl,
                        cookie = cookie,
                    ),
                )
                isLogged.value = true
                showForm.value = false
                message.value = account.loginWelcome(profile.nickname)
                refreshAccounts()
            } finally {
                if (binding.isCurrent()) isLoading.value = false
            }
        }
    }

    fun sendCaptcha(phone: String, countryCode: String? = null) {
        scope.launch {
            val binding = bindProvider()
            val actionResult = try {
                AppModel.authRepository.sendCaptcha(
                    phone = phone,
                    countryCode = countryCode?.takeIf { it.isNotBlank() },
                    imageCaptcha = imageCaptcha.value.ifBlank { null },
                    captchaCookie = captchaSessionCookie,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (binding.isCurrent()) message.value = account.captchaSendFailed(e.message)
                return@launch
            }
            if (!binding.isCurrent()) return@launch
            if (actionResult.ok) {
                message.value = account.captchaSent
            } else {
                if (actionResult.verifyUrl != null || actionResult.isVerificationChallenge) {
                    verifyUrl.value = actionResult.verifyUrl
                    message.value = account.verifyRequired
                    return@launch
                }
                message.value = account.captchaSendFailed(actionResult.reason ?: account.pleaseRetry)
                fetchCaptchaImage()
            }
        }
    }


    fun loginAnonymous() {
        cancelQrPolling()
        scope.launch {
            val binding = bindProvider()
            isLoading.value = true
            try {
                val actionResult = AppModel.authRepository.loginAnonymous()
                if (!binding.isCurrent()) return@launch
                onLoginSucceeded(actionResult.cookie, actionResult.ok, binding = binding)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (binding.isCurrent()) message.value = account.guestLoginFailed(e.message)
            } finally {
                if (binding.isCurrent()) isLoading.value = false
            }
        }
    }

    /**
     * 登录成功后的收尾（写 cookie → 拉资料 → 记账号）。
     *
     * @param binding 这次登录**发起时**所属的音源；不传则按当前音源现场处理
     *   （邮箱 / 手机 / Cookie 这类短事务走默认即可）。扫码这种长事务必须传入
     *   发起时的现场 —— 否则一次在途的 803 会把**上一个音源的 cookie 写进当前音源的
     *   cookie 槽位**，用户看到的是「切了音源，登进去还是上个音源的账号」。
     */
    private suspend fun onLoginSucceeded(
        cookie: String?,
        ok: Boolean = true,
        binding: ProviderBinding? = null,
    ) {
        if (!ok) {
            message.value = account.loginFailedRetry
            return
        }
        val owner = binding ?: bindProvider()
        // ⚠️ 先判归属再落 cookie：cookie 一旦写错槽位，后续 refreshUserProfileAwait
        // 读的是**当前**音源的 cookie，两者会互相打脸。
        if (!owner.isCurrent()) return
        if (!cookie.isNullOrEmpty()) AppModel.cookieStorage.saveCookie(owner.providerId, cookie)
        val profile = AppModel.refreshUserProfileAwait()
        if (!owner.isCurrent()) return
        isLogged.value = profile != null
        if (profile != null) {
            QrLoginStore.clear(owner.providerId)
            if (!cookie.isNullOrEmpty()) {
                AccountStore.save(
                    owner.providerId,
                    AccountStore.SavedAccount(
                        uid = profile.resourceId,
                        nickname = profile.nickname,
                        avatarUrl = profile.avatarUrl,
                        cookie = cookie,
                    ),
                )
            }
            showForm.value = false
            message.value = account.loginWelcome(profile.nickname)
        } else {
            message.value = account.loginCheckFailed
        }
        refreshAccounts()
    }

    fun switchAccount(account: AccountStore.SavedAccount) {
        cancelQrPolling()
        scope.launch {
            val binding = bindProvider()
            val providerId = binding.providerId
            val previousCookie = AppModel.cookieStorage.getCookie(providerId)
            val previousUid = AccountStore.activeUid(providerId)
            QrLoginStore.clear(providerId)
            if (account.cookie.isNotBlank()) {
                AppModel.cookieStorage.saveCookie(providerId, account.cookie)
            }
            AccountStore.setActive(providerId, account.uid)
            val profile = AppModel.refreshUserProfileAwait()
            if (!binding.isCurrent()) return@launch
            if (profile != null) {
                isLogged.value = true
                message.value = this@AccountPresenter.account.switchedTo(profile.nickname)
            } else {
                if (previousCookie.isNullOrEmpty()) AppModel.cookieStorage.clear(providerId)
                else AppModel.cookieStorage.saveCookie(providerId, previousCookie)
                AccountStore.setActive(providerId, previousUid)
                val restored = AppModel.refreshUserProfileAwait()
                if (!binding.isCurrent()) return@launch
                isLogged.value = restored != null
                message.value = this@AccountPresenter.account.accountExpired(account.nickname)
            }
            refreshAccounts()
        }
    }

    fun removeAccount(account: AccountStore.SavedAccount) {
        cancelQrPolling()
        scope.launch {
            val binding = bindProvider()
            val providerId = binding.providerId
            val wasActive = AccountStore.activeUid(providerId) == account.uid
            QrLoginStore.clear(providerId)
            AccountStore.remove(providerId, account.uid)
            if (wasActive) {
                isLogged.value = false
                // ⚠️ 移除**当前**账号也必须走一次服务端 logout：
                //   AccountStore.remove 只清 active 标记与 cookie，不碰接口缓存；
                //   而缓存键只含 providerId + cookie 哈希，同一音源再登录会命中
                //   上一个账号的 user/playlist、user/like、user/cloud 响应。
                //   失败无所谓（cookie 已经没了），但它必须在缓存能清之前被**调用过**。
                runCatching { AppModel.authRepository.logout() }
                if (!binding.isCurrent()) return@launch
                AppModel.clearUserProfile()
                if (!binding.isCurrent()) return@launch
                message.value = this@AccountPresenter.account.removedSession(account.nickname)
            } else {
                message.value = this@AccountPresenter.account.removedAccount(account.nickname)
            }
            refreshAccounts()
        }
    }

    fun logout() {
        cancelQrPolling()
        scope.launch {
            val binding = bindProvider()
            val providerId = binding.providerId
            runCatching { AppModel.authRepository.logout() }
            AppModel.cookieStorage.clear(providerId)
            AccountStore.setActive(providerId, null)
            QrLoginStore.clear(providerId)
            if (!binding.isCurrent()) return@launch
            AppModel.clearUserProfile()
            if (!binding.isCurrent()) return@launch
            isLogged.value = false
            message.value = account.loggedOut
            refreshAccounts()
        }
    }

    fun saveQrCode() {
        if (isSavingQr.value) return
        val url = qrUrl.value
        val base64 = qrImgBase64.value
        if (url == null && base64 == null) return
        val providerName = activeProvider.value?.name ?: "qr"
        isSavingQr.value = true
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.Default) {
                    url?.let(qrPngBytes) ?: base64?.let(::decodeBase64Image)
                }
                val result = if (bytes == null || bytes.isEmpty()) {
                    ImageSaveResult.Failed
                } else {
                    saveImageToLocal(
                        imageBytes = bytes,
                        fileName = "CPPlayer-QR-$providerName",
                        extension = "png",
                        dialogTitle = account.saveQr,
                    )
                }
                message.value = when (result) {
                    is ImageSaveResult.Saved ->
                        result.location?.let { account.qrSavedTo(it) } ?: account.qrSavedToGallery
                    ImageSaveResult.Cancelled -> null
                    ImageSaveResult.Failed -> account.qrSaveFailed
                }
                // 手动保存成功同样记账：用户先点了「保存二维码」再点跳转，不该在相册里再出现一张。
                if (result is ImageSaveResult.Saved && base64 != null) {
                    qrAutoSave.markSaved(base64)
                }
            } finally {
                isSavingQr.value = false
            }
        }
    }

    /**
     * 「打开目标 App」——跳转前**先把当前二维码存进相册**。
     *
     * ### 为什么要在跳转时自动存
     *
     * 用户点这个按钮的本意是「去手机上扫」，跳过去后人就在另一个应用里了，
     * 本应用的登录页看不到；而扫码的通常是**另一台设备**（或者同一台手机的相机 / 系统扫码入口）
     * —— 现场这张二维码一离开登录页就没了。相册是它能落地的、用户回头找得到的地方。
     * 于是这里做两件事：存图（同一张图只存一次，见 [QrAutoSaveTracker]）→ 再跳转。
     *
     * ### 顺序与失败处理
     *
     * 先存后跳：跳转一旦成功，本应用就被切到后台，之后再想写相册会撞上后台限制。
     * 存图失败（桌面端恒失败、Android 7/8/9 缺写权限）**不阻断跳转** ——
     * 跳过去扫码才是用户真正要做的事，相册里没有图只是少了个备份。
     *
     * 成功后的用户提示**不在这里发**：`saveQrCodeToGallery` 的实现自己弹 toast
     * （与手动「保存二维码」同一个文案）。这里再写一条 `message` 只会被 2 秒一次的
     * 轮询状态（「等待扫码…」）立刻盖掉，等于白发。
     */
    fun openTargetApp() {
        val pkg = activeProvider.value?.targetAppPackage ?: return
        val image = qrImgBase64.value?.takeIf { it.isNotBlank() }
        if (image != null && qrAutoSave.needsSave(image)) {
            val providerName = activeProvider.value?.name ?: "qr"
            if (saveQrCodeToGallery(image, "QR_$providerName")) {
                qrAutoSave.markSaved(image)
            }
        }
        openTargetApp(pkg)
    }
}

private enum class QrPollResult {
    LOGGED_IN,
    EXPIRED,
    STOPPED,
}

enum class LoginChannel(val labelOf: (CpStrings) -> String) {
    QR({ it.account.channelQr }),
    PHONE({ it.account.channelPhone }),
    EMAIL({ it.account.channelEmail }),
    COOKIE({ it.account.channelCookie }),
    ;

    companion object {
        val DEFAULT = listOf(QR, PHONE, EMAIL, COOKIE)
    }
}


private const val QR_POLL_INTERVAL_MS = 2000L
private const val QR_POLL_MAX = 120
private const val QR_FAILURE_LIMIT = 5
private const val QR_MAX_ROUNDS = 3
