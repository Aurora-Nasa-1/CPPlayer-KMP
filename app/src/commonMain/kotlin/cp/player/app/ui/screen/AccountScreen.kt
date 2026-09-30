package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import cp.player.app.AppModel
import cp.player.app.auth.AccountStore
import cp.player.app.platform.isPackageInstalled
import cp.player.app.platform.openTargetApp
import cp.player.app.platform.saveQrCodeToGallery
import cp.player.app.ui.component.LegacyPageScaffold
import cp.player.app.ui.component.SettingsButtonItem
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsDropdownItem
import cp.player.app.ui.component.SettingsFieldGroup
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 登录入口 + 账号管理（旧版 `UserAccountDialog` 的新版实现）。
 *
 * 旧版把这一整套塞在一个底部弹层里，移动端够用，但桌面端没有弹层宿主。
 * 表单一高就被裁掉。这里改成整页，但**版式逐段对照旧版**：
 *
 * 1. Hero（80dp 头像）+ 昵称 / ID（未登录时是引导文案）
 * 2. 当前音源 + 「切换音乐源」
 * 3. `<音源> 的账号`：已保存账号快速切换 / 移除 / 添加账号
 * 4. 登录方式（扫码 / 邮箱 / 手机） 表单
 * 5. 底部：退出登录（`errorContainer`）
 *
 * 多账号数据存放在 [AccountStore]，**按音源隔离**：切换音源后看到的是那一个音源
 * 自己的账号列表，互不串台。
 */
class AccountScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val expanded = cp.player.app.ui.component.LocalIsExpanded.current
        val model = rememberScreenModel { AccountScreenModel() }
        val provider by model.activeProvider.collectAsState()
        val profile by AppModel.userProfileFlow.collectAsState()
        val isLogged by model.isLogged.collectAsState()
        val isLoading by model.isLoading.collectAsState()
        val message by model.message.collectAsState()
        val qrUrl by model.qrUrl.collectAsState()
        val qrImgBase64 by model.qrImgBase64.collectAsState()
        val targetAppName by model.targetAppName.collectAsState()
        val targetAppInstalled by model.targetAppInstalled.collectAsState()
        val accounts by model.accounts.collectAsState()
        val method by model.method.collectAsState()
        val showForm by model.showForm.collectAsState()
        // 原「音源隔离」是一个独立的一级设置入口，但它唯一的真设置项就是这个开关，
        // 其余全是跳转链接。并进本页，设置根页少一个入口、少一圈循环跳转。
        val switchAccount by AppModel.isolationSwitchAccountFlow.collectAsState()
        // 表单值放在页面层：提交按钮在表单的**下一个分组**里，只能靠这层状态串起来。
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var phone by remember { mutableStateOf("") }
        var captcha by remember { mutableStateOf("") }

        val body: @Composable (Modifier) -> Unit = { pageModifier ->
            SettingsPage(pageModifier) {
                AccountHero(
                    nickname = profile?.nickname,
                    avatarUrl = profile?.avatarUrl,
                    uid = profile?.uid,
                    isLogged = isLogged,
                )

                // 状态反馈（切号、清除、扫码轮询…）放页面顶部，登录与否都能看见。
                message?.let { SettingsNote(it, color = MaterialTheme.colorScheme.primary) }

                SettingsSection("当前音源") {
                    SettingsClickItem(
                        title = provider?.name ?: "尚未加载音源",
                        subtitle = provider?.let { "${it.type.name} · v${it.version}" }
                            ?: "先在音源管理里导入一个 Provider 模块",
                        icon = Icons.Filled.Extension,
                        index = 0,
                        total = 1,
                        onClick = null,
                        trailingContent = {
                            TextButton(
                                onClick = { navigator.push(ProviderManagementScreen()) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            ) {
                                Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("切换音源", style = MaterialTheme.typography.labelSmall)
                            }
                        },
                    )
                }

                SettingsSection("音源隔离") {
                    cp.player.app.ui.component.SettingsSwitchItem(
                        title = "切音源时同步刷新账号资料",
                        subtitle = "关闭后仍会切换登录态（登录态本来就按音源分开存），" +
                            "只是不立即重新拉取昵称与头像",
                        checked = switchAccount,
                        onCheckedChange = AppModel::setIsolationSwitchAccount,
                        index = 0,
                        total = 1,
                    )
                }
                SettingsNote("每个音源都有自己的登录态、缓存与账号列表，互不共享。")

                if (isLogged) {
                    SettingsSection("${provider?.name ?: "当前音源"} 的账号") {
                        accounts.forEachIndexed { index, account ->
                            val activeUid = AccountStore.activeUid(model.providerId())
                                ?: profile?.uid?.toString()
                            val isActive = account.uid == activeUid
                            SettingsClickItem(
                                title = account.nickname.ifBlank { "未知账号" },
                                subtitle = buildString {
                                    append("ID: ${account.uid}")
                                    if (isActive) append(" · 当前登录")
                                },
                                index = index,
                                total = accounts.size + 1,
                                containerColor = if (isActive) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                } else {
                                    Color.Unspecified
                                },
                                leadingContent = { AccountAvatar(url = account.avatarUrl, size = 36.dp) },
                                onClick = { model.switchAccount(account) },
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isActive) {
                                            Icon(
                                                Icons.Filled.CheckCircle,
                                                contentDescription = "当前登录",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                        IconButton(onClick = { model.removeAccount(account) }) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = "移除账号",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                },
                            )
                        }
                        SettingsButtonItem(
                            text = "添加账号",
                            subtitle = "再登录一个账号，之后可在这里一键切换",
                            index = accounts.size,
                            total = accounts.size + 1,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            onClick = { model.startAddAccount() },
                        )
                    }
                } else {
                    SettingsNote("登录后可同步歌单、红心与播放记录；登录态只保存在当前音源内。")
                }

                if (!isLogged || showForm) {
                    SettingsSection(if (isLogged) "添加账号" else "登录方式") {
                        SettingsDropdownItem(
                            title = "登录方式",
                            options = listOf("扫码登录", "邮箱登录", "手机号登录"),
                            selectedIndex = method,
                            onSelect = { model.setMethod(it) },
                            index = 0,
                            total = 1,
                        )
                    }

                    SettingsFieldGroup {
                        when (method) {
                            0 -> QrLoginContent(
                                qrUrl = qrUrl,
                                qrImgBase64 = qrImgBase64,
                                isLoading = isLoading,
                                targetAppName = targetAppName,
                                targetAppInstalled = targetAppInstalled,
                                onSaveQr = { model.saveQrCode() },
                                onOpenTargetApp = { model.openTargetApp() },
                                onRefresh = { model.fetchQrCode() },
                            )
                            1 -> EmailLoginForm(
                                email = email,
                                onEmailChange = { email = it },
                                password = password,
                                onPasswordChange = { password = it },
                            )
                            else -> PhoneLoginForm(
                                phone = phone,
                                onPhoneChange = { phone = it },
                                captcha = captcha,
                                onCaptchaChange = { captcha = it },
                                isLoading = isLoading,
                                onSendCaptcha = { model.sendCaptcha(phone) },
                            )
                        }
                    }

                    when (method) {
                        1 -> SettingsButtonItem(
                            text = if (isLoading) "登录中…" else "邮箱登录",
                            index = 0,
                            total = 2,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
                            onClick = { model.loginEmail(email, password) },
                        )
                        2 -> SettingsButtonItem(
                            text = if (isLoading) "登录中…" else "手机登录",
                            index = 0,
                            total = 2,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            enabled = !isLoading && phone.isNotBlank(),
                            onClick = { model.loginPhone(phone, captcha) },
                        )
                        else -> SettingsButtonItem(
                            text = "改用账号密码登录",
                            index = 0,
                            total = 1,
                            onClick = { model.setMethod(1) },
                        )
                    }
                    if (method != 0) {
                        SettingsButtonItem(
                            text = "游客登录 / 跳过",
                            index = 1,
                            total = 2,
                            enabled = !isLoading,
                            onClick = { model.loginAnonymous() },
                        )
                    }
                }

                if (isLogged) {
                    SettingsSection("账号操作") {
                        SettingsButtonItem(
                            text = "退出登录",
                            subtitle = "清除当前音源的登录态；已保存的账号会保留，方便一键切回",
                            index = 0,
                            total = 1,
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            onClick = { model.logout() },
                        )
                    }
                }
            }
        }

        if (expanded) body(Modifier.fillMaxWidth()) else LegacyPageScaffold(
            title = "账号与登录",
            navigationIcon = {
                IconButton(onClick = { navigator.pop() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        ) { pageModifier -> body(pageModifier) }
    }
}

/** 旧版 Hero（80dp 头像）+ 昵称 + ID / 未登录引导。 */
@Composable
private fun AccountHero(
    nickname: String?,
    avatarUrl: String?,
    uid: Long?,
    isLogged: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.size(88.dp), contentAlignment = Alignment.Center) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxSize(),
            ) {}
            AccountAvatar(
                url = avatarUrl,
                size = 76.dp,
                fallbackTint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (isLogged) nickname.orEmpty().ifBlank { "已登录" } else "未登录",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (isLogged && uid != null) "ID: $uid" else "登录后可同步歌单、红心与播放记录",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 头像：有 URL 走图片，否则回退到 `Person` 图标。 */
@Composable
private fun AccountAvatar(url: String?, size: androidx.compose.ui.unit.Dp, fallbackTint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.size(size).clip(CircleShape),
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    tint = fallbackTint,
                    modifier = Modifier.size(size * 0.55f),
                )
            }
        }
    }
}

@Composable
private fun QrLoginContent(
    qrUrl: String?,
    qrImgBase64: String?,
    isLoading: Boolean,
    targetAppName: String?,
    targetAppInstalled: Boolean,
    onSaveQr: () -> Unit,
    onOpenTargetApp: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "请使用音源对应的 App 扫描二维码登录",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.size(220.dp),
        ) {
            Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
                when {
                    isLoading -> cp.player.app.ui.component.CpLoadingIndicator(Modifier.size(40.dp))
                    qrUrl != null -> QrCodeImage(qrUrl, Modifier.size(200.dp))
                    else -> Text("二维码加载失败", color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onRefresh, enabled = !isLoading) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("刷新二维码")
            }
            if (qrImgBase64 != null) {
                TextButton(onClick = onSaveQr, enabled = !isLoading) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("保存二维码")
                }
            }
        }
        if (targetAppName != null) {
            OutlinedButton(onClick = onOpenTargetApp) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (targetAppInstalled) "打开 $targetAppName" else "安装 $targetAppName")
            }
        }
    }
}

@Composable
private fun EmailLoginForm(
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            label = { Text("邮箱") },
            leadingIcon = { Icon(Icons.Filled.Email, null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("密码") },
            leadingIcon = { Icon(Icons.Filled.Lock, null) },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        Text(
            text = "密码只发给当前音源，不会离开本机。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PhoneLoginForm(
    phone: String,
    onPhoneChange: (String) -> Unit,
    captcha: String,
    onCaptchaChange: (String) -> Unit,
    isLoading: Boolean,
    onSendCaptcha: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = phone,
            onValueChange = onPhoneChange,
            label = { Text("手机号") },
            leadingIcon = { Icon(Icons.Filled.Phone, null) },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = captcha,
                onValueChange = onCaptchaChange,
                label = { Text("验证码 / 密码") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedButton(
                onClick = onSendCaptcha,
                enabled = phone.isNotBlank() && !isLoading,
            ) { Text("发送验证码") }
        }
    }
}

class AccountScreenModel : ScreenModel {
    val activeProvider: StateFlow<cp.player.core.provider.BackendProvider?> = AppModel.activeProviderFlow
    val isLoading = MutableStateFlow(false)
    val isLogged = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val qrUrl = MutableStateFlow<String?>(null)
    val qrImgBase64 = MutableStateFlow<String?>(null)
    val targetAppName = MutableStateFlow<String?>(null)
    val targetAppInstalled = MutableStateFlow(false)
    val accounts = MutableStateFlow<List<AccountStore.SavedAccount>>(emptyList())
    val method = MutableStateFlow(0)
    val showForm = MutableStateFlow(false)

    init {
        screenModelScope.launch {
            activeProvider.collect { provider ->
                val pkg = provider?.targetAppPackage
                if (!pkg.isNullOrEmpty()) {
                    targetAppName.value = provider.name
                    targetAppInstalled.value = isPackageInstalled(pkg)
                } else {
                    targetAppName.value = null
                    targetAppInstalled.value = false
                }
                refreshAccounts()
            }
        }
        screenModelScope.launch {
            checkLoginStatus()
            if (!isLogged.value) fetchQrCode()
        }
    }

    fun providerId(): String = AppModel.activeProviderId()

    fun setMethod(index: Int) {
        method.value = index.coerceIn(0, 2)
        showForm.value = true
        if (index == 0) fetchQrCode()
    }

    fun startAddAccount() {
        showForm.value = true
        fetchQrCode()
    }

    private fun refreshAccounts() {
        val providerId = providerId()
        val stored = AccountStore.list(providerId)
        val profile = AppModel.userProfileFlow.value
        val currentUid = profile?.uid?.toString()
        // 本功能上线前登录的老账号：列表里还没有它，用当前资料 + 当前 cookie 回填展示。
        val merged = if (profile != null && stored.none { it.uid == currentUid }) {
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

    private suspend fun checkLoginStatus() {
        val hasCookie = AppModel.cookieStorage.getCookie(providerId())?.isNotEmpty() == true
        if (!hasCookie) {
            isLogged.value = false
            refreshAccounts()
            return
        }
        val body = runCatching { AppModel.authRepository.getLoginStatus() }.getOrNull()
        val ok = body?.asCodeOk() == true
        isLogged.value = ok
        if (ok) {
            runCatching { AppModel.refreshUserProfileAwait() }
            message.value = "已恢复登录"
        }
        refreshAccounts()
    }

    fun fetchQrCode() {
        screenModelScope.launch {
            isLoading.value = true
            try {
                val keyResp = AppModel.authRepository.getQrKey()
                val key = keyResp.uniCodeKey()
                if (key == null) {
                    message.value = "获取二维码 key 失败"
                    isLoading.value = false
                    return@launch
                }
                val qrResp = AppModel.authRepository.createQrCode(key)
                qrUrl.value = qrResp.uniQrUrl()
                qrImgBase64.value = qrResp.uniQrImage()
                isLoading.value = false
                pollQrStatus(key)
            } catch (e: Exception) {
                message.value = "二维码加载异常: ${e.message}"
                isLoading.value = false
            }
        }
    }

    private suspend fun pollQrStatus(key: String) {
        repeat(120) {
            kotlinx.coroutines.delay(2000L)
            val resp = runCatching { AppModel.authRepository.checkQrStatus(key) }.getOrNull() ?: return@repeat
            when (resp.asCode()) {
                801 -> message.value = "等待扫码…"
                802 -> message.value = "已扫码，请在手机上确认登录"
                803 -> {
                    onLoginSucceeded(resp.uniCookie())
                    return
                }
                800 -> {
                    message.value = "二维码已过期，请重新获取"
                    fetchQrCode()
                    return
                }
            }
            if (isLogged.value) return
        }
    }

    fun loginEmail(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) return
        screenModelScope.launch {
            isLoading.value = true
            try {
                val body = AppModel.authRepository.login(email, password)
                onLoginSucceeded(body.uniCookie(), body.asCodeOk())
            } catch (e: Exception) {
                message.value = "登录失败: ${e.message}"
            } finally {
                isLoading.value = false
            }
        }
    }

    fun loginPhone(phone: String, codeOrPass: String) {
        if (phone.isBlank()) return
        screenModelScope.launch {
            isLoading.value = true
            try {
                val body = AppModel.authRepository.loginWithPhone(phone, codeOrPass)
                onLoginSucceeded(body.uniCookie(), body.asCodeOk())
            } catch (e: Exception) {
                message.value = "登录失败: ${e.message}"
            } finally {
                isLoading.value = false
            }
        }
    }

    fun sendCaptcha(phone: String) {
        screenModelScope.launch {
            runCatching { AppModel.authRepository.sendCaptcha(phone) }
                .onSuccess { message.value = "验证码已发送（如支持）" }
                .onFailure { message.value = "验证码发送失败: ${it.message}" }
        }
    }

    fun loginAnonymous() {
        screenModelScope.launch {
            isLoading.value = true
            try {
                val body = AppModel.authRepository.loginAnonymous()
                onLoginSucceeded(body.uniCookie(), body.asCodeOk())
            } catch (e: Exception) {
                message.value = "游客登录失败: ${e.message}"
            } finally {
                isLoading.value = false
            }
        }
    }

    /** 登录成功：写回当前音源的 cookie → 拉资料 → 存进账号列表（音源隔离）。 */
    private suspend fun onLoginSucceeded(cookie: String?, ok: Boolean = true) {
        if (!ok) {
            message.value = "登录失败，请重试"
            return
        }
        val providerId = providerId()
        if (!cookie.isNullOrEmpty()) AppModel.cookieStorage.saveCookie(providerId, cookie)
        val profile = AppModel.refreshUserProfileAwait()
        isLogged.value = profile != null
        if (profile != null) {
            if (!cookie.isNullOrEmpty()) {
                AccountStore.save(
                    providerId,
                    AccountStore.SavedAccount(
                        uid = profile.uid.toString(),
                        nickname = profile.nickname,
                        avatarUrl = profile.avatarUrl,
                        cookie = cookie,
                    ),
                )
            }
            showForm.value = false
            message.value = "登录成功，欢迎 ${profile.nickname}"
        } else {
            message.value = "登录态校验失败，请重试"
        }
        refreshAccounts()
    }

    fun switchAccount(account: AccountStore.SavedAccount) {
        screenModelScope.launch {
            val providerId = providerId()
            if (account.cookie.isNotBlank()) {
                AppModel.cookieStorage.saveCookie(providerId, account.cookie)
            }
            AccountStore.setActive(providerId, account.uid)
            val profile = AppModel.refreshUserProfileAwait()
            if (profile == null) {
                isLogged.value = false
                message.value = "「${account.nickname}」的登录态已失效，请重新登录"
            } else {
                isLogged.value = true
                message.value = "已切换到 ${profile.nickname}"
            }
            refreshAccounts()
        }
    }

    fun removeAccount(account: AccountStore.SavedAccount) {
        screenModelScope.launch {
            val providerId = providerId()
            val wasActive = AccountStore.activeUid(providerId) == account.uid
            AccountStore.remove(providerId, account.uid)
            if (wasActive) {
                isLogged.value = false
                AppModel.clearUserProfile()
                message.value = "已移除「${account.nickname}」的登录态"
            } else {
                message.value = "已移除「${account.nickname}」"
            }
            refreshAccounts()
        }
    }

    fun logout() {
        screenModelScope.launch {
            val providerId = providerId()
            runCatching { AppModel.authRepository.logout() }
            AppModel.cookieStorage.clear(providerId)
            AccountStore.setActive(providerId, null)
            AppModel.clearUserProfile()
            isLogged.value = false
            message.value = "已退出登录"
            refreshAccounts()
        }
    }

    fun saveQrCode() {
        val base64 = qrImgBase64.value ?: return
        val providerName = activeProvider.value?.name ?: "qr"
        saveQrCodeToGallery(base64, "QR_$providerName")
    }

    fun openTargetApp() {
        val pkg = activeProvider.value?.targetAppPackage ?: return
        openTargetApp(pkg)
    }
}

// ============ JSON 工具：跨 Provider 字段兼容提取 ============

private fun JsonElement.asObject(): JsonObject? = this as? JsonObject

private fun JsonElement.asCode(): Int? =
    (asObject()?.get("code") as? JsonPrimitive)?.intOrNull

private fun JsonElement.asCodeOk(): Boolean {
    val c = asCode() ?: return false
    return c == 200 || c == 0 || c == 201 || c == 301 || c == 803
}

private fun JsonElement.uniCodeKey(): String? {
    val obj = asObject() ?: return null
    (obj["unikey"] as? JsonPrimitive)?.contentOrNull?.let { return it }
    (obj["key"] as? JsonPrimitive)?.contentOrNull?.let { return it }
    val data = obj["data"] as? JsonObject ?: return null
    (data["unikey"] as? JsonPrimitive)?.contentOrNull?.let { return it }
    (data["key"] as? JsonPrimitive)?.contentOrNull?.let { return it }
    return null
}

private fun JsonElement.uniQrUrl(): String? {
    val obj = asObject() ?: return null
    (obj["qrurl"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) return it }
    (obj["qrUrl"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) return it }
    (obj["url"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) return it }
    val data = obj["data"] as? JsonObject ?: return null
    (data["qrurl"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) return it }
    (data["qrUrl"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) return it }
    (data["url"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) return it }
    return null
}

private fun JsonElement.uniQrImage(): String? {
    val obj = asObject() ?: return null
    (obj["qrimg"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) return it }
    (obj["qrcode"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) return it }
    (obj["base64"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) return it }
    val data = obj["data"] as? JsonObject ?: return null
    (data["qrimg"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) return it }
    (data["qrcode"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) return it }
    (data["base64"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) return it }
    return null
}

private fun JsonElement.uniCookie(): String? {
    val obj = asObject() ?: return null
    (obj["cookie"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) return it }
    val data = obj["data"] as? JsonObject ?: return null
    (data["cookie"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotBlank()) return it }
    return null
}
