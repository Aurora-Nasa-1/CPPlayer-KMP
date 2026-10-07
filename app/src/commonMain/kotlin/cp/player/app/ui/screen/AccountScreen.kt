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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
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
import cp.player.app.i18n.AccountStrings
import cp.player.app.i18n.CpStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.auth.CookieLogin
import cp.player.app.auth.QrLoginSession
import cp.player.app.auth.QrLoginStore
import cp.player.app.platform.isPackageInstalled
import cp.player.app.platform.openTargetApp
import cp.player.app.platform.saveQrCodeToGallery
import cp.player.app.ui.component.CpIconSize
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.util.popOrNotify
import cp.player.app.ui.component.SettingsButtonItem
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsDropdownItem
import cp.player.app.ui.component.SettingsFieldGroup
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsNoteEmphasis
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.settingsRowHighlightContent
import cp.player.core.api.isLoggedInStatus
import cp.player.core.util.currentTimeMillis
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
        val s = cpStrings()
        val model = rememberScreenModel { AccountScreenModel(s.account) }
        val provider by model.activeProvider.collectAsState()
        val profile by AppModel.userProfileFlow.collectAsState()
        val isLogged by model.isLogged.collectAsState()
        val isLoading by model.isLoading.collectAsState()
        val message by model.message.collectAsState()
        val qrUrl by model.qrUrl.collectAsState()
        val qrImgBase64 by model.qrImgBase64.collectAsState()
        val qrRestored by model.qrRestored.collectAsState()
        val targetAppName by model.targetAppName.collectAsState()
        val targetAppInstalled by model.targetAppInstalled.collectAsState()
        val accounts by model.accounts.collectAsState()
        val method by model.method.collectAsState()
        val showForm by model.showForm.collectAsState()
        // 登录方式按音源能力动态生成（manifest.loginMethods；未声明 = 网易云系全量）
        val loginChannels by model.loginChannels.collectAsState()
        // 图形验证码（音源声明了 captchaImage 能力时才出现的人机校验）
        val supportsCaptchaImage by model.supportsCaptchaImage.collectAsState()
        val captchaImage by model.captchaImage.collectAsState()
        val imageCaptcha by model.imageCaptcha.collectAsState()
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
        // 「移除账号」的二次确认：删掉的是已保存的登录凭据，移除后必须重新登录。
        val confirm = cp.player.app.ui.component.rememberConfirmState()

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

                // 「这张二维码是上次没扫完、这次恢复出来的」要**单独一条常显提示**：
                // 轮询每 2 秒就把 message 覆盖成「等待扫码…」，捎在 message 里等于没提示。
                // ⚠️ 必须放在 SettingsSection 之外（说明条是分段卡片之间的元素）。
                if (qrRestored) {
                    SettingsNote(s.account.qrRestored, emphasis = SettingsNoteEmphasis.WARNING)
                }

                // 登录区（方式选择 + 表单 + 提交按钮）抽成局部块，两个位置复用：
                // 未登录 → 紧跟 Hero 放在页面**最上面**，手机端第一屏就是二维码/登录表单，
                // 不用滚过「当前音源 / 音源隔离」才找到登录入口；
                // 已登录「添加账号」→ 放在账号列表之后（见下方 showForm 分支）。
                val loginSection: @Composable () -> Unit = {
                    // 清洗后的 cookie（null = 粘进来的东西里一个 k=v 都没有）。
                    // 表单与提交按钮都要用它，所以在这一层算一次。
                    val normalizedCookie = CookieLogin.normalize(cookieText)

                    SettingsSection(
                        if (isLogged) s.account.addAccount else s.account.loginMethod,
                    ) {
                        SettingsDropdownItem(
                            title = s.account.loginMethod,
                            // 只显示当前音源支持的方式（音源未声明时是网易云系全量）
                            options = loginChannels.map { it.labelOf(s) },
                            selectedIndex = loginChannels.indexOf(method).coerceAtLeast(0),
                            onSelect = { model.setChannel(loginChannels[it]) },
                            index = 0,
                            total = 1,
                        )
                    }

                    SettingsFieldGroup {
                        when (method) {
                            LoginChannel.QR -> QrLoginContent(
                                qrUrl = qrUrl,
                                qrImgBase64 = qrImgBase64,
                                isLoading = isLoading,
                                targetAppName = targetAppName,
                                targetAppInstalled = targetAppInstalled,
                                onSaveQr = { model.saveQrCode() },
                                onOpenTargetApp = { model.openTargetApp() },
                                onRefresh = { model.fetchQrCode() },
                            )
                            LoginChannel.EMAIL -> EmailLoginForm(
                                email = email,
                                onEmailChange = { email = it },
                                password = password,
                                onPasswordChange = { password = it },
                            )
                            LoginChannel.PHONE -> PhoneLoginForm(
                                phone = phone,
                                onPhoneChange = { phone = it },
                                captcha = captcha,
                                onCaptchaChange = { captcha = it },
                                isLoading = isLoading,
                                // 图形验证码：音源声明 captchaImage 能力时才显示
                                captchaImageUrl = captchaImage.takeIf { supportsCaptchaImage },
                                imageCaptcha = imageCaptcha,
                                onImageCaptchaChange = { model.imageCaptcha.value = it },
                                onRefreshCaptcha = { model.fetchCaptchaImage() },
                                onSendCaptcha = { model.sendCaptcha(phone) },
                            )
                            LoginChannel.COOKIE -> CookieLoginForm(
                                raw = cookieText,
                                onRawChange = { cookieText = it },
                                normalized = normalizedCookie,
                            )
                        }
                    }

                    when (method) {
                        LoginChannel.EMAIL -> SettingsButtonItem(
                            text = if (isLoading) s.account.loggingIn else s.account.channelEmail,
                            index = 0,
                            total = 2,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
                            onClick = { model.loginEmail(email, password) },
                        )
                        LoginChannel.PHONE -> SettingsButtonItem(
                            text = if (isLoading) s.account.loggingIn else s.account.channelPhone,
                            index = 0,
                            total = 2,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            enabled = !isLoading && phone.isNotBlank(),
                            onClick = { model.loginPhone(phone, captcha) },
                        )
                        LoginChannel.COOKIE -> SettingsButtonItem(
                            text = if (isLoading) s.account.loggingIn else s.account.channelCookie,
                            index = 0,
                            total = 2,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            enabled = !isLoading && normalizedCookie != null,
                            onClick = { model.loginWithCookie(cookieText) },
                        )
                        LoginChannel.QR -> {
                            // 扫码方式靠轮询自动登录，没有提交按钮；保留旧入口方便切到账密
                            val fallback = loginChannels.firstOrNull { it != LoginChannel.QR }
                            if (fallback != null) {
                                SettingsButtonItem(
                                    text = s.account.switchToMethod(fallback.labelOf(s)),
                                    index = 0,
                                    total = 1,
                                    onClick = { model.setChannel(fallback) },
                                )
                            }
                        }
                    }
                    if (method != LoginChannel.QR) {
                        SettingsButtonItem(
                            text = s.account.guestLogin,
                            index = 1,
                            total = 2,
                            enabled = !isLoading,
                            onClick = { model.loginAnonymous() },
                        )
                    }
                }

                // 「我的」两个入口。它们以前根本不存在 —— 用户资料与私信端点早就有了，
                // 但应用里除了这页顶部的头像之外，没有第二个地方能把它们打开。
                // （未登录时没有资料可看，整块不渲染。）
                if (isLogged) profile?.let { me ->
                    SettingsSection(s.account.sectionMine) {
                        SettingsClickItem(
                            title = s.account.myProfile,
                            subtitle = s.account.myProfileNote,
                            icon = Icons.Filled.Person,
                            index = 0,
                            total = 2,
                            onClick = { navigator.push(UserProfileScreen(me.uid, me.nickname)) },
                        )
                        SettingsClickItem(
                            title = s.account.messages,
                            subtitle = s.account.messagesNote,
                            icon = Icons.AutoMirrored.Filled.Message,
                            index = 1,
                            total = 2,
                            onClick = { navigator.push(MessagesScreen()) },
                        )
                    }
                }

                // 未登录：登录区紧跟 Hero 放在最上面（手机端一进页就能扫码/填表单）。
                if (!isLogged) loginSection()

                SettingsSection(s.account.currentProvider) {
                    SettingsClickItem(
                        title = provider?.name ?: s.account.noProvider,
                        subtitle = provider?.let { s.account.providerMeta(it.type.name, it.version) }
                            ?: s.account.noProviderNote,
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
                                Text(s.account.switchProvider, style = MaterialTheme.typography.labelSmall)
                            }
                        },
                    )
                }

                SettingsSection(s.account.sectionIsolation) {
                    cp.player.app.ui.component.SettingsSwitchItem(
                        title = s.account.isolationTitle,
                        subtitle = s.account.isolationSubtitle,
                        checked = switchAccount,
                        onCheckedChange = AppModel::setIsolationSwitchAccount,
                        index = 0,
                        total = 1,
                    )
                }
                SettingsNote(s.account.isolationHint)

                if (isLogged) {
                    SettingsSection(
                        s.account.accountsOf(provider?.name ?: s.account.currentProvider),
                    ) {
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
                                title = account.nickname.ifBlank { s.account.unknownAccount },
                                subtitle = buildString {
                                    append(s.account.accountId(account.uid))
                                    if (isActive) append(s.account.activeSuffix)
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
                                                contentDescription = s.account.currentlyLoggedIn,
                                                tint = trailingTint,
                                                modifier = Modifier.size(CpIconSize.list),
                                            )
                                        }
                                        IconButton(onClick = {
                                            confirm.request(
                                                title = s.account.removeAccount,
                                                message = s.account.removeAccountMessage(account.nickname) +
                                                    if (AccountStore.activeUid(model.providerId()) == account.uid) {
                                                        s.account.removeAccountActiveNote
                                                    } else {
                                                        s.account.removeAccountSavedNote
                                                    },
                                                confirmLabel = s.account.removeLabel,
                                                onConfirm = { model.removeAccount(account) },
                                            )
                                        }) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = s.account.removeAccount,
                                                tint = trailingTint,
                                                modifier = Modifier.size(CpIconSize.inline),
                                            )
                                        }
                                    }
                                },
                            )
                        }
                        SettingsButtonItem(
                            text = s.account.addAccount,
                            subtitle = s.account.addAccountNote,
                            index = accounts.size,
                            total = accounts.size + 1,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            onClick = { model.startAddAccount() },
                        )
                    }
                    // 已登录「添加账号」：复用同一个登录区，放在账号列表之后。
                    if (showForm) loginSection()
                } else {
                    SettingsNote(s.account.loginBenefitsNote)
                }

                if (isLogged) {
                    SettingsSection(s.account.sectionAccountActions) {
                        SettingsButtonItem(
                            text = s.account.logout,
                            subtitle = s.account.logoutNote,
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
            title = s.account.screenTitle,
            onBack = { navigator.popOrNotify() },
        ) { pageModifier -> body(pageModifier) }

        cp.player.app.ui.component.CpConfirmHost(confirm)
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
    val s = cpStrings()
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
            text = if (isLogged) {
                nickname.orEmpty().ifBlank { s.account.loggedIn }
            } else {
                s.account.notLoggedIn
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (isLogged && uid != null) {
                s.account.accountId(uid.toString())
            } else {
                s.account.loggedInHint
            },
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
    val s = cpStrings().account
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = s.qrHint,
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
                    else -> Text(s.qrLoadFailed, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onRefresh, enabled = !isLoading) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(CpIconSize.inline))
                Spacer(Modifier.width(4.dp))
                Text(s.refreshQr)
            }
            if (qrImgBase64 != null) {
                TextButton(onClick = onSaveQr, enabled = !isLoading) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(CpIconSize.inline))
                    Spacer(Modifier.width(4.dp))
                    Text(s.saveQr)
                }
            }
        }
        if (targetAppName != null) {
            OutlinedButton(onClick = onOpenTargetApp) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(CpIconSize.inline))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (targetAppInstalled) {
                        s.openApp(targetAppName)
                    } else {
                        s.installApp(targetAppName)
                    },
                )
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
    val s = cpStrings().account
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            label = { Text(s.emailLabel) },
            leadingIcon = { Icon(Icons.Filled.Email, null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text(s.passwordLabel) },
            leadingIcon = { Icon(Icons.Filled.Lock, null) },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        Text(
            text = s.passwordNote,
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
    // 图形验证码（人机校验）：音源声明 captchaImage 能力时提供图片，用户输入答案
    captchaImageUrl: String? = null,
    imageCaptcha: String = "",
    onImageCaptchaChange: (String) -> Unit = {},
    onRefreshCaptcha: () -> Unit = {},
    onSendCaptcha: () -> Unit,
) {
    val s = cpStrings().account
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = phone,
            onValueChange = onPhoneChange,
            label = { Text(s.phoneLabel) },
            leadingIcon = { Icon(Icons.Filled.Phone, null) },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true,
        )
        if (captchaImageUrl != null) {
            OutlinedTextField(
                value = imageCaptcha,
                onValueChange = onImageCaptchaChange,
                label = { Text(s.imageCaptchaLabel) },
                supportingText = { Text(s.imageCaptchaHint(s.refreshCaptcha)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.width(148.dp).height(48.dp).clip(RoundedCornerShape(8.dp)),
                ) {
                    AsyncImage(
                        model = captchaImageUrl,
                        contentDescription = s.imageCaptchaLabel,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onRefreshCaptcha, enabled = !isLoading) {
                    Text(s.refreshCaptcha)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = captcha,
                onValueChange = onCaptchaChange,
                label = { Text(s.captchaOrPassword) },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedButton(
                onClick = onSendCaptcha,
                enabled = phone.isNotBlank() && !isLoading,
            ) { Text(s.sendCaptcha) }
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
    val s = cpStrings().account
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = raw,
            onValueChange = onRawChange,
            label = { Text(s.cookieLabel) },
            leadingIcon = { Icon(Icons.Filled.Key, null) },
            placeholder = { Text(s.cookiePlaceholder) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp),
            minLines = 3,
            maxLines = 6,
            singleLine = false,
            isError = raw.isNotBlank() && normalized == null,
            supportingText = { Text(cookieHint(s, raw, normalized)) },
        )
        Text(
            text = s.cookieHowTo,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 输入框下方的即时反馈文案。
 *
 * ⚠️ 是**纯函数**（被 `OutlinedTextField(supportingText)` 直接当字符串用），
 * 读不到 CompositionLocal —— 语言由调用方传进来（见 I18N.md §5.9）。
 */
private fun cookieHint(strings: AccountStrings, raw: String, normalized: String?): String = when {
    raw.isBlank() -> strings.cookieHintEmpty
    normalized == null -> strings.cookieHintUnparsed
    !CookieLogin.hasSessionKey(normalized) ->
        strings.cookieNoSessionKey(cookieFieldCount(normalized))
    else -> {
        val masked = CookieLogin.mask(normalized)
        strings.cookieRecognized(cookieFieldCount(normalized)) +
            masked.take(120) + if (masked.length > 120) "…" else ""
    }
}

private fun cookieFieldCount(normalized: String): Int =
    normalized.split(';').count { it.contains('=') }

class AccountScreenModel(private val account: AccountStrings) : ScreenModel {
    val activeProvider: StateFlow<cp.player.core.provider.BackendProvider?> = AppModel.activeProviderFlow
    val isLoading = MutableStateFlow(false)
    val isLogged = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val qrUrl = MutableStateFlow<String?>(null)
    val qrImgBase64 = MutableStateFlow<String?>(null)

    /**
     * 当前这张二维码是不是**上次没扫完、这次恢复出来的**。
     *
     * 只存布尔量、不存提示文案：状态带文案，切语言时那句话就永远是旧语言（见
     * `docs/dev/I18N.md` §5.5）；显示用的句子由界面拿 `account.qrRestored` 现取。
     */
    val qrRestored = MutableStateFlow(false)
    val targetAppName = MutableStateFlow<String?>(null)
    val targetAppInstalled = MutableStateFlow(false)
    val accounts = MutableStateFlow<List<AccountStore.SavedAccount>>(emptyList())
    val method = MutableStateFlow(LoginChannel.QR)
    val showForm = MutableStateFlow(false)

    /**
     * 当前音源可用的登录方式。
     *
     * 由音源 manifest 的 `loginMethods` 声明决定（`qr` / `email` / `sms`）；
     * **未声明 = 网易云系默认全量**（旧行为）；`cookie` 是宿主端能力，永远保留。
     */
    val loginChannels = MutableStateFlow(LoginChannel.DEFAULT)

    /** 音源是否声明了图形验证码能力（`captchaImage`）。 */
    val supportsCaptchaImage = MutableStateFlow(false)

    /** 图形验证码图片（data-url 形态，AsyncImage 直接可显示）；null = 还没取。 */
    val captchaImage = MutableStateFlow<String?>(null)

    /** 用户输入的图形验证码答案。 */
    val imageCaptcha = MutableStateFlow("")

    /** 图形验证码会话 cookie（`captcha/image` 响应带回，发送/登录时原样传回）。 */
    private var captchaSessionCookie: String? = null

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
                // 登录方式按音源声明过滤（见 LoginChannel 注释）；当前选中的方式
                // 若被新音源砍掉，回落到第一个可用方式，别停在不可用的表单上。
                val declared = provider?.loginMethods
                    ?.map { it.trim().lowercase() }
                    ?.filter { it.isNotEmpty() }
                loginChannels.value = if (declared.isNullOrEmpty()) {
                    LoginChannel.DEFAULT
                } else {
                    buildList {
                        if ("qr" in declared) add(LoginChannel.QR)
                        if ("email" in declared) add(LoginChannel.EMAIL)
                        if ("sms" in declared || "phone" in declared) add(LoginChannel.PHONE)
                        add(LoginChannel.COOKIE)
                    }
                }
                supportsCaptchaImage.value = declared?.contains("captchaimage") == true
                if (method.value !in loginChannels.value) {
                    setChannel(loginChannels.value.first())
                }
                // 登录态与 cookie 都是**按音源隔离**的（存储键里带 providerId），
                // 所以音源一变就得重新查一次。原先只刷账号列表 ⇒ 切换音源后本页
                // 仍停在上一个音源的登录态上。
                checkLoginStatus()
                if (first) {
                    first = false
                    if (!isLogged.value && LoginChannel.QR in loginChannels.value) restoreOrFetchQrCode()
                }
            }
        }
    }

    fun providerId(): String = AppModel.activeProviderId()

    fun setChannel(channel: LoginChannel) {
        method.value = channel
        showForm.value = true
        // 离开扫码方式就停掉轮询，否则它在后台继续请求、继续改 message。
        // （现场留在盘上：切回扫码方式时接着用，不必再扫一张新的。）
        if (channel == LoginChannel.QR) restoreOrFetchQrCode() else cancelQrPolling()
        // 切到手机号登录时预取一次图形验证码（音源声明了该能力才有）。
        if (channel == LoginChannel.PHONE && supportsCaptchaImage.value) fetchCaptchaImage()
    }

    /** 取图形验证码；拿到后记住会话 cookie，发送验证码 / 登录时原样带回。 */
    fun fetchCaptchaImage() {
        if (!supportsCaptchaImage.value) return
        screenModelScope.launch {
            val body = runCatching { AppModel.authRepository.getCaptchaImage() }.getOrNull()
            val obj = body?.asObject()
            val img = (obj?.get("captchaImage") as? JsonPrimitive)?.contentOrNull
            if (obj != null && obj.asCodeOk() && !img.isNullOrBlank()) {
                captchaImage.value = img
                captchaSessionCookie = (obj["cookie"] as? JsonPrimitive)?.contentOrNull
            } else {
                captchaImage.value = null
                captchaSessionCookie = null
                message.value = account.captchaImageFailed
            }
        }
    }

    fun startAddAccount() {
        showForm.value = true
        // 与进页时同一套判据：上次没扫完的那张还在就直接接着扫，不必再开一张新的。
        restoreOrFetchQrCode()
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
            message.value = account.loginRestored
        }
        refreshAccounts()
    }

    /**
     * 重新要一张二维码：界面上的「刷新二维码」按钮走这里。
     *
     * 刻意**不**去恢复上次的现场 —— 用户点刷新就是嫌当前这张不能用了（多半已过期），
     * 再把它捞回来只会原地打转。
     */
    fun fetchQrCode() {
        qrRestored.value = false
        startQrJob(initialKey = null, reuseQr = false)
    }

    /**
     * 进登录页 / 切回扫码方式时的入口：**先看有没有上次没扫完的现场**。
     *
     * 现场是 [QrLoginStore] 落的那一份（用户切后台去扫码时进程被系统回收，见它的 KDoc）。
     * 有就接着同一张二维码继续轮询 —— 用户手机里那张二维码还有效，不用重扫；
     * 没有就照旧取一张新的。
     *
     * 已经在轮询时直接返回：`init` 里「先按音源能力纠正方式、再补一次首次进入」两条
     * 路径都会走到这里，不去重就会把刚建的 Job 取消再重建一遍。
     */
    private fun restoreOrFetchQrCode() {
        if (qrJob?.isActive == true) return
        // 音源没声明扫码能力时保持原样（老行为就是「照旧要一张二维码」）——
        // 这里若把 method 改成 QR，下拉框仍只列可用方式，标签与实际表单会对不上。
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

    /**
     * 二维码任务本体：取 key / 出图 /（恢复现场）/ 轮询**全在这一个 Job 里**。
     *
     * 之所以守着「一个 Job」这个形态：过期重取改成外层 `repeat` 再来一轮，而不是像原先
     * 那样 `800 -> fetchQrCode()` 递归调回自己 —— 那个写法能跑通，但 `fetchQrCode` 开头会
     * `cancel()` 掉正在跑的那条协程（也就是它自己），语义绕、也没人看得懂为什么还能 work。
     * `finally` 里统一收 `isLoading`：原先三条退出路径各写一遍，漏一条就是
     * 「一直转圈但什么也不发生」。
     *
     * @param initialKey 恢复现场时**已有的** key；null = 本轮重新向服务端要一个。
     * @param reuseQr 第一轮是否沿用界面上已有的那张二维码图（恢复现场时不重复请求出图，
     *   否则刚恢复出来的那张会被新图顶掉，用户手机上那张立刻作废）。
     */
    private fun startQrJob(initialKey: String?, reuseQr: Boolean) {
        cancelQrPolling()
        qrJob = screenModelScope.launch {
            isLoading.value = true
            try {
                var pendingKey = initialKey
                var pendingReuse = reuseQr
                repeat(QR_MAX_ROUNDS) { round ->
                    val key = pendingKey
                        ?: runCatching { AppModel.authRepository.getQrKey() }.getOrNull()?.uniCodeKey()
                    val reuse = pendingReuse
                    pendingKey = null
                    pendingReuse = false
                    if (key == null) {
                        message.value = account.qrKeyFailed
                        return@launch
                    }
                    if (!reuse) {
                        val qrResp = runCatching { AppModel.authRepository.createQrCode(key) }.getOrNull()
                        val url = qrResp?.uniQrUrl()
                        val img = qrResp?.uniQrImage()
                        qrUrl.value = url
                        qrImgBase64.value = img
                        if (url == null) {
                            message.value = account.qrLoadFailed
                            return@launch
                        }
                        // 换成新图了 ⇒「恢复出来的」这个说法不再成立；同时把现场落盘，
                        // 这样接下来无论进程怎么死，下次进来都是这张二维码。
                        qrRestored.value = false
                        val providerId = providerId()
                        QrLoginStore.save(
                            providerId,
                            QrLoginSession(
                                key = key,
                                url = url,
                                imageBase64 = img,
                                createdAtMs = currentTimeMillis(),
                            ),
                        )
                    }
                    isLoading.value = false
                    when (pollQrStatus(key)) {
                        QrPollResult.LOGGED_IN -> return@launch
                        QrPollResult.STOPPED -> {
                            // 超时 / 连续失败：这张二维码已经盖棺，别让它下次开机又被恢复出来。
                            if (!isLogged.value) QrLoginStore.clear(providerId())
                            return@launch
                        }
                        QrPollResult.EXPIRED ->
                            if (round == QR_MAX_ROUNDS - 1) {
                                message.value = account.qrExpiredRetry
                            } else {
                                message.value = account.qrRefreshing
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
                    message.value = account.qrPollFailed
                    return QrPollResult.STOPPED
                }
            } else {
                consecutiveFailures = 0
                when (resp.asCode()) {
                    801 -> message.value = account.qrWaiting
                    802 -> message.value = account.qrScanned
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
        message.value = account.qrTimedOut
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
                message.value = account.loginFailed(e.message)
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
                val body = AppModel.authRepository.loginWithPhone(
                    phone,
                    codeOrPass,
                    imageCaptcha.value.ifBlank { null },
                    captchaSessionCookie,
                )
                if (!body.asCodeOk()) {
                    // 登录失败多半是图形验证码不对/过期 —— 立即换一张再让用户输
                    val reason = (body.asObject()?.get("msg") as? JsonPrimitive)?.contentOrNull
                    message.value = account.loginFailed(reason ?: account.pleaseRetry)
                    fetchCaptchaImage()
                    return@launch
                }
                onLoginSucceeded(body.uniCookie(), ok = true)
            } catch (e: Exception) {
                message.value = account.loginFailed(e.message)
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
            message.value = account.cookieFormatError
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
                    message.value = account.cookieInvalid
                    return@launch
                }
                // 粘 cookie 登录成功：扫码现场一并作废（与 onLoginSucceeded 同一规矩）。
                QrLoginStore.clear(providerId)
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
                message.value = account.loginWelcome(profile.nickname)
                refreshAccounts()
            } finally {
                isLoading.value = false
            }
        }
    }

    fun sendCaptcha(phone: String) {
        screenModelScope.launch {
            runCatching {
                AppModel.authRepository.sendCaptcha(
                    phone,
                    imageCaptcha.value.ifBlank { null },
                    captchaSessionCookie,
                )
            }
                .onSuccess { body ->
                    if (body.asCodeOk()) {
                        message.value = account.captchaSent
                    } else {
                        // 失败时换一张图 —— 咪咕的验证码一次一换，旧图已作废
                        val reason = (body.asObject()?.get("msg") as? JsonPrimitive)?.contentOrNull
                        message.value = account.captchaSendFailed(reason ?: account.pleaseRetry)
                        fetchCaptchaImage()
                    }
                }
                .onFailure { message.value = account.captchaSendFailed(it.message) }
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
                message.value = account.guestLoginFailed(e.message)
            } finally {
                isLoading.value = false
            }
        }
    }

    /** 登录成功：写回当前音源的 cookie → 拉资料 → 存进账号列表（音源隔离）。 */
    private suspend fun onLoginSucceeded(cookie: String?, ok: Boolean = true) {
        if (!ok) {
            message.value = account.loginFailedRetry
            return
        }
        val providerId = providerId()
        if (!cookie.isNullOrEmpty()) AppModel.cookieStorage.saveCookie(providerId, cookie)
        val profile = AppModel.refreshUserProfileAwait()
        isLogged.value = profile != null
        if (profile != null) {
            // 这单登录已经成了 ⇒ 扫码现场作废，别让下次开机又把它恢复出来。
            QrLoginStore.clear(providerId)
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
            message.value = account.loginWelcome(profile.nickname)
        } else {
            message.value = account.loginCheckFailed
        }
        refreshAccounts()
    }

    fun switchAccount(account: AccountStore.SavedAccount) {
        cancelQrPolling()
        screenModelScope.launch {
            val providerId = providerId()
            val previousCookie = AppModel.cookieStorage.getCookie(providerId)
            val previousUid = AccountStore.activeUid(providerId)
            // 换账号 = 登录态要变了 ⇒ 没扫完的那张二维码不再代表「正在进行的登录」。
            QrLoginStore.clear(providerId)
            if (account.cookie.isNotBlank()) {
                AppModel.cookieStorage.saveCookie(providerId, account.cookie)
            }
            AccountStore.setActive(providerId, account.uid)
            val profile = AppModel.refreshUserProfileAwait()
            if (profile != null) {
                isLogged.value = true
                // ⚠️ 作用域里 `account` 是形参（SavedAccount），文案那份要显式指回外层。
                message.value = this@AccountScreenModel.account.switchedTo(profile.nickname)
            } else {
                // 目标账号的登录态已失效 ⇒ **回滚**，别把当前音源留成「无登录态」。
                // 原实现只提示一句就完事：cookie 已经被目标账号覆盖，用户的正常登录
                // 就被这次失败操作毁掉了，只能重新扫码才能回来。
                if (previousCookie.isNullOrEmpty()) AppModel.cookieStorage.clear(providerId)
                else AppModel.cookieStorage.saveCookie(providerId, previousCookie)
                AccountStore.setActive(providerId, previousUid)
                val restored = AppModel.refreshUserProfileAwait()
                isLogged.value = restored != null
                // ⚠️ 作用域里 `account` 是形参（SavedAccount），文案那份要显式指回外层；
                // 这里还在 `launch` 里，`this` 是 CoroutineScope，必须写全 this@AccountScreenModel。
                message.value = this@AccountScreenModel.account.accountExpired(account.nickname)
            }
            refreshAccounts()
        }
    }

    fun removeAccount(account: AccountStore.SavedAccount) {
        cancelQrPolling()
        screenModelScope.launch {
            val providerId = providerId()
            val wasActive = AccountStore.activeUid(providerId) == account.uid
            QrLoginStore.clear(providerId)
            AccountStore.remove(providerId, account.uid)
            if (wasActive) {
                isLogged.value = false
                AppModel.clearUserProfile()
                message.value = this@AccountScreenModel.account.removedSession(account.nickname)
            } else {
                message.value = this@AccountScreenModel.account.removedAccount(account.nickname)
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
            // 登出后不该再恢复上次那张二维码（那是登出前那次登录的现场）。
            QrLoginStore.clear(providerId)
            AppModel.clearUserProfile()
            isLogged.value = false
            message.value = account.loggedOut
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

/**
 * 登录方式。显示与可用性由**当前音源**的能力声明决定
 * （manifest `loginMethods`：`qr` / `email` / `sms` / `cookie` / `captchaImage`）。
 *
 * `cookie` 是宿主端能力（粘贴即可，不依赖音源实现登录接口），只要音源声明了
 * 其它方式就一并保留；音源**什么都没声明**时按旧行为展示全量（网易云系音源
 * 不用改 manifest 就有全部方式）。
 */
enum class LoginChannel(val labelOf: (CpStrings) -> String) {
    QR({ it.account.channelQr }),
    EMAIL({ it.account.channelEmail }),
    PHONE({ it.account.channelPhone }),
    COOKIE({ it.account.channelCookie }),
    ;

    companion object {
        /** 未声明 loginMethods 时的默认全集（网易云系行为，向后兼容）。 */
        val DEFAULT = listOf(QR, EMAIL, PHONE, COOKIE)
    }
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
