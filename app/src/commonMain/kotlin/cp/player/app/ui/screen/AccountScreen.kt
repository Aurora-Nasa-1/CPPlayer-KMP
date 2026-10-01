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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Key
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
import cp.player.app.auth.CookieLogin
import cp.player.app.platform.isPackageInstalled
import cp.player.app.platform.openTargetApp
import cp.player.app.platform.saveQrCodeToGallery
import cp.player.app.ui.component.CpIconSize
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.SettingsButtonItem
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsDropdownItem
import cp.player.app.ui.component.SettingsFieldGroup
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.settingsRowHighlightContent
import cp.player.core.api.isLoggedInStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
        // Cookie 登录：原文可能是一整行请求头，交给 CookieLogin 清洗后再提交。
        var cookieText by remember { mutableStateOf("") }

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
                                Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(CpIconSize.inline))
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
                            // 当前账号那行的底色是 primaryContainer（`SettingsClickItem(selected)`），
                            // 行尾控件必须跟着换成它的前景色 —— 继续用 onSurfaceVariant 会掉对比度。
                            val trailingTint = if (isActive) {
                                settingsRowHighlightContent()
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            SettingsClickItem(
                                title = account.nickname.ifBlank { "未知账号" },
                                subtitle = buildString {
                                    append("ID: ${account.uid}")
                                    if (isActive) append(" · 当前登录")
                                },
                                index = index,
                                total = accounts.size + 1,
                                selected = isActive,
                                // 行尾的「移除账号」是独立动作：合并语义会把它并进整行，读屏就点不到。
                                mergeSemantics = false,
                                leadingContent = { AccountAvatar(url = account.avatarUrl, size = 36.dp) },
                                onClick = { model.switchAccount(account) },
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isActive) {
                                            Icon(
                                                Icons.Filled.CheckCircle,
                                                contentDescription = "当前登录",
                                                tint = trailingTint,
                                                modifier = Modifier.size(CpIconSize.list),
                                            )
                                        }
                                        IconButton(onClick = { model.removeAccount(account) }) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = "移除账号",
                                                tint = trailingTint,
                                                modifier = Modifier.size(CpIconSize.inline),
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
                    // 清洗后的 cookie（null = 粘进来的东西里一个 k=v 都没有）。
                    // 表单与提交按钮都要用它，所以在这一层算一次。
                    val normalizedCookie = CookieLogin.normalize(cookieText)

                    SettingsSection(if (isLogged) "添加账号" else "登录方式") {
                        SettingsDropdownItem(
                            title = "登录方式",
                            options = listOf("扫码登录", "邮箱登录", "手机号登录", "Cookie 登录"),
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
                            2 -> PhoneLoginForm(
                                phone = phone,
                                onPhoneChange = { phone = it },
                                captcha = captcha,
                                onCaptchaChange = { captcha = it },
                                isLoading = isLoading,
                                onSendCaptcha = { model.sendCaptcha(phone) },
                            )
                            else -> CookieLoginForm(
                                raw = cookieText,
                                onRawChange = { cookieText = it },
                                normalized = normalizedCookie,
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
                        3 -> SettingsButtonItem(
                            text = if (isLoading) "登录中…" else "Cookie 登录",
                            index = 0,
                            total = 2,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            enabled = !isLoading && normalizedCookie != null,
                            onClick = { model.loginWithCookie(cookieText) },
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

        CpRouteScaffold(
            title = "账号与登录",
            onBack = { navigator.pop() },
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
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(CpIconSize.inline))
                Spacer(Modifier.width(4.dp))
                Text("刷新二维码")
            }
            if (qrImgBase64 != null) {
                TextButton(onClick = onSaveQr, enabled = !isLoading) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(CpIconSize.inline))
                    Spacer(Modifier.width(4.dp))
                    Text("保存二维码")
                }
            }
        }
        if (targetAppName != null) {
            OutlinedButton(onClick = onOpenTargetApp) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(CpIconSize.inline))
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

/**
 * 粘贴式 Cookie 登录表单。
 *
 * 桌面端扫码很别扭（得把二维码从显示器挪到手机上），Cookie 登录是最省事的兜底：
 * 浏览器登录后 F12 → 网络 → 任选一个请求 → 复制请求头里的 `Cookie` 整行，粘进来即可。
 *
 * 输入框**刻意不做 `singleLine`**：cookie 常有几百字符，单行框只看得到一个尾巴，
 * 用户没法核对粘对了没有；多行 + 折行反而看得清。
 *
 * 表单只负责收集原文，清洗与判定都在 [CookieLogin] 里（纯函数、可单测）。
 */
@Composable
internal fun CookieLoginForm(
    raw: String,
    onRawChange: (String) -> Unit,
    normalized: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = raw,
            onValueChange = onRawChange,
            label = { Text("Cookie") },
            leadingIcon = { Icon(Icons.Filled.Key, null) },
            placeholder = { Text("MUSIC_U=…; __csrf=…") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp),
            minLines = 3,
            maxLines = 6,
            singleLine = false,
            isError = raw.isNotBlank() && normalized == null,
            supportingText = { Text(cookieHint(raw, normalized)) },
        )
        Text(
            text = "获取方式：浏览器登录后按 F12 → 网络（Network）→ 任选一个请求 → " +
                "复制请求头里的 Cookie 整行。登录态等同于密码，只存在本机、" +
                "并按音源隔离，不会发给当前音源以外的任何一方。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 输入框下方的即时反馈文案。 */
private fun cookieHint(raw: String, normalized: String?): String = when {
    raw.isBlank() -> "整行粘贴即可：会自动去掉 `Cookie:` 前缀、换行与多余空格。"
    normalized == null -> "没解析出任何 name=value —— 请确认复制的是 Cookie，而不是网址或整段请求。"
    !CookieLogin.hasSessionKey(normalized) ->
        "已识别 ${cookieFieldCount(normalized)} 个字段，但其中没有常见的会话字段，多半是复制错了。"
    else -> {
        val masked = CookieLogin.mask(normalized)
        "已识别 ${cookieFieldCount(normalized)} 个字段：" + masked.take(120) + if (masked.length > 120) "…" else ""
    }
}

private fun cookieFieldCount(normalized: String): Int =
    normalized.split(';').count { it.contains('=') }

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

    /**
     * 当前在跑的二维码任务（取 key → 出图 → 轮询**全在这一个 Job 里**）。
     *
     * 持有一个 Job 而不是每次现 `launch` 一把，是为了修两个真实故障：
     * 1. `fetchQrCode()` 有四个触发点（首次进页 / 切到扫码方式 / 点「添加账号」/ 刷新按钮），
     *    每次都会再起一条轮询，而旧轮询不会自己停 —— 它 2 秒后就把「登录成功，欢迎 X」
     *    覆盖回「等待扫码…」，用户以为没登上（二维码其实早就扫过了）。
     * 2. 二维码还在轮询时点「退出登录」，轮询读到 803 会把用户**重新登回去**。
     * ⇒ 起新的之前先取消旧的；登录 / 切号 / 登出时也一并取消。
     */
    private var qrJob: Job? = null

    init {
        screenModelScope.launch {
            var first = true
            activeProvider.collect { provider ->
                val pkg = provider?.targetAppPackage
                if (!pkg.isNullOrEmpty()) {
                    targetAppName.value = provider.name
                    targetAppInstalled.value = isPackageInstalled(pkg)
                } else {
                    targetAppName.value = null
                    targetAppInstalled.value = false
                }
                // 登录态与 cookie 都是**按音源隔离**的（存储键里带 providerId），
                // 所以音源一变就得重新查一次。原先只刷账号列表 ⇒ 切换音源后本页
                // 仍停在上一个音源的登录态上。
                checkLoginStatus()
                if (first) {
                    first = false
                    if (!isLogged.value) fetchQrCode()
                }
            }
        }
    }

    fun providerId(): String = AppModel.activeProviderId()

    fun setMethod(index: Int) {
        method.value = index.coerceIn(0, 3)
        showForm.value = true
        // 离开扫码方式就停掉轮询，否则它在后台继续请求、继续改 message。
        if (index == 0) fetchQrCode() else cancelQrPolling()
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
        // ⚠️ 判据是「有没有 uid」，不是 code：NCM 的 login/status **未登录时也返回 code 200**
        // （account/profile 为 null），只看 code 会把过期登录态当成已登录。
        // 而它的 code 又藏在 data 层（顶层没有），所以也不能用 asCodeOk()。
        val ok = isLoggedInStatus(body)
        isLogged.value = ok
        if (ok) {
            runCatching { AppModel.refreshUserProfileAwait() }
            message.value = "已恢复登录"
        }
        refreshAccounts()
    }

    /**
     * 取二维码 + 轮询扫码结果。
     *
     * 「取 key / 出图」与「轮询」刻意放进**同一个 Job**：过期重取改成外层 `repeat`
     * 再来一轮，而不是像原先那样 `800 -> fetchQrCode()` 递归调回自己 —— 那个写法
     * 能跑通，但 `fetchQrCode` 开头会 `cancel()` 掉正在跑的那条协程（也就是它自己），
     * 语义绕、也没人看得懂为什么还能work。
     *
     * `finally` 里统一收 `isLoading`：原先三条退出路径各写一遍，漏一条就是
     * 「一直转圈但什么也不发生」。
     */
    fun fetchQrCode() {
        cancelQrPolling()
        qrJob = screenModelScope.launch {
            isLoading.value = true
            try {
                repeat(QR_MAX_ROUNDS) { round ->
                    val key = runCatching { AppModel.authRepository.getQrKey() }
                        .getOrNull()?.uniCodeKey()
                    if (key == null) {
                        message.value = "获取二维码 key 失败"
                        return@launch
                    }
                    val qrResp = runCatching { AppModel.authRepository.createQrCode(key) }.getOrNull()
                    qrUrl.value = qrResp?.uniQrUrl()
                    qrImgBase64.value = qrResp?.uniQrImage()
                    if (qrUrl.value == null) {
                        message.value = "二维码加载失败"
                        return@launch
                    }
                    isLoading.value = false
                    when (pollQrStatus(key)) {
                        QrPollResult.LOGGED_IN -> return@launch
                        QrPollResult.STOPPED -> return@launch
                        QrPollResult.EXPIRED ->
                            if (round == QR_MAX_ROUNDS - 1) {
                                message.value = "二维码反复过期，请点「刷新二维码」重试"
                            } else {
                                message.value = "二维码已过期，正在重新获取…"
                                isLoading.value = true
                            }
                    }
                }
            } finally {
                isLoading.value = false
            }
        }
    }

    /** 停掉在跑的二维码任务（起新的之前、以及任何会改变登录态的操作之前都要调）。 */
    private fun cancelQrPolling() {
        qrJob?.cancel()
        qrJob = null
    }

    /**
     * 轮询扫码结果。
     *
     * ⚠️ 原实现是 `runCatching { … }.getOrNull() ?: return@repeat` —— 请求一失败
     * 就 `return@repeat` 只跳过**这一轮**，于是断网时会静默空转到 4 分钟结束，
     * 用户看到的是一个永远停在「等待扫码…」的界面，没有任何错误提示。
     * 现在连续失败到上限就明确报错并退出。
     */
    private suspend fun pollQrStatus(key: String): QrPollResult {
        var consecutiveFailures = 0
        repeat(QR_POLL_MAX) {
            kotlinx.coroutines.delay(QR_POLL_INTERVAL_MS)
            val resp = runCatching { AppModel.authRepository.checkQrStatus(key) }.getOrNull()
            if (resp == null) {
                consecutiveFailures++
                if (consecutiveFailures >= QR_FAILURE_LIMIT) {
                    message.value = "查询扫码状态连续失败，请检查网络后刷新二维码"
                    return QrPollResult.STOPPED
                }
            } else {
                consecutiveFailures = 0
                when (resp.asCode()) {
                    801 -> message.value = "等待扫码…"
                    802 -> message.value = "已扫码，请在手机上确认登录"
                    803 -> {
                        onLoginSucceeded(resp.uniCookie())
                        return QrPollResult.LOGGED_IN
                    }
                    800 -> return QrPollResult.EXPIRED
                }
            }
            // 别的入口（切号 / Cookie 登录 / 登出）已经改过登录态 ⇒ 立刻停，别再往回写
            if (isLogged.value) return QrPollResult.LOGGED_IN
        }
        message.value = "二维码已超时，请点「刷新二维码」重试"
        return QrPollResult.STOPPED
    }

    fun loginEmail(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) return
        cancelQrPolling()
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
        cancelQrPolling()
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

    /**
     * Cookie 登录：把粘贴的 cookie 写进当前音源的存储，再用它拉一次资料当作校验。
     *
     * 校验不通过（或网络失败）**必须回滚**到原来的 cookie —— 否则用户一次粘错就把
     * 当前音源原有的登录态覆盖掉了，得重新扫码才能回来。
     */
    fun loginWithCookie(raw: String) {
        val cookie = CookieLogin.normalize(raw)
        if (cookie == null) {
            message.value = "Cookie 格式不对：至少要有一个 name=value"
            return
        }
        cancelQrPolling()
        screenModelScope.launch {
            isLoading.value = true
            try {
                val providerId = providerId()
                val previous = AppModel.cookieStorage.getCookie(providerId)
                AppModel.cookieStorage.saveCookie(providerId, cookie)
                val profile = AppModel.refreshUserProfileAwait()
                if (profile == null) {
                    if (previous.isNullOrEmpty()) AppModel.cookieStorage.clear(providerId)
                    else AppModel.cookieStorage.saveCookie(providerId, previous)
                    AppModel.refreshUserProfileAwait()
                    isLogged.value = false
                    message.value = "Cookie 无效或已过期，请重新获取"
                    return@launch
                }
                AccountStore.save(
                    providerId,
                    AccountStore.SavedAccount(
                        uid = profile.uid.toString(),
                        nickname = profile.nickname,
                        avatarUrl = profile.avatarUrl,
                        cookie = cookie,
                    ),
                )
                isLogged.value = true
                showForm.value = false
                message.value = "登录成功，欢迎 ${profile.nickname}"
                refreshAccounts()
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
        cancelQrPolling()
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
        cancelQrPolling()
        screenModelScope.launch {
            val providerId = providerId()
            val previousCookie = AppModel.cookieStorage.getCookie(providerId)
            val previousUid = AccountStore.activeUid(providerId)
            if (account.cookie.isNotBlank()) {
                AppModel.cookieStorage.saveCookie(providerId, account.cookie)
            }
            AccountStore.setActive(providerId, account.uid)
            val profile = AppModel.refreshUserProfileAwait()
            if (profile != null) {
                isLogged.value = true
                message.value = "已切换到 ${profile.nickname}"
            } else {
                // 目标账号的登录态已失效 ⇒ **回滚**，别把当前音源留成「无登录态」。
                // 原实现只提示一句就完事：cookie 已经被目标账号覆盖，用户的正常登录
                // 就被这次失败操作毁掉了，只能重新扫码才能回来。
                if (previousCookie.isNullOrEmpty()) AppModel.cookieStorage.clear(providerId)
                else AppModel.cookieStorage.saveCookie(providerId, previousCookie)
                AccountStore.setActive(providerId, previousUid)
                val restored = AppModel.refreshUserProfileAwait()
                isLogged.value = restored != null
                message.value = "「${account.nickname}」的登录态已失效，已回到原账号"
            }
            refreshAccounts()
        }
    }

    fun removeAccount(account: AccountStore.SavedAccount) {
        cancelQrPolling()
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
        // 必须先停轮询：否则二维码那边读到 803 会把刚登出的用户**重新登回去**。
        cancelQrPolling()
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

/** [AccountScreenModel.pollQrStatus] 的结果。 */
private enum class QrPollResult {
    /** 已拿到 cookie 并写入登录态（提示由 `onLoginSucceeded` 给）。 */
    LOGGED_IN,

    /** 二维码过期（800），由外层换一个新的 key 再来一轮。 */
    EXPIRED,

    /** 超时 / 连续请求失败 / 登录态已被别的入口改掉 —— 都不再自动重来。 */
    STOPPED,
}

/** 轮询间隔 2 秒，与二维码服务端的过期节奏对齐。 */
private const val QR_POLL_INTERVAL_MS = 2000L

/** 单轮最多轮询 120 次 ⇒ 4 分钟，超过就当作过期。 */
private const val QR_POLL_MAX = 120

/** 连续这么多轮请求都失败就报错退出，不再空转。 */
private const val QR_FAILURE_LIMIT = 5

/** 二维码过期后最多自动重取几轮。 */
private const val QR_MAX_ROUNDS = 3

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
