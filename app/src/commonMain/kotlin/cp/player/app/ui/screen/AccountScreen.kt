package cp.player.app.ui.screen

import cp.player.app.ui.component.CpText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import cp.player.app.auth.QrLoginSession
import cp.player.app.auth.QrLoginStore
import cp.player.app.i18n.AccountStrings
import cp.player.app.i18n.CpStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.isPackageInstalled
import cp.player.app.platform.openTargetApp
import cp.player.app.platform.openWebview
import cp.player.app.platform.saveQrCodeToGallery
import cp.player.app.ui.component.CpConfirmHost
import cp.player.app.ui.component.CpIconSize
import cp.player.app.ui.component.CpLoadingIndicator
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsNoteEmphasis
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.rememberConfirmState
import cp.player.app.ui.component.settingsRowHighlightContent
import cp.player.app.ui.theme.LocalIsDarkTheme
import cp.player.app.ui.util.popOrNotify
import cp.player.core.api.extractVerifyUrl
import cp.player.core.api.isLoggedInStatus
import cp.player.core.api.isVerificationChallenge
import cp.player.core.provider.ProviderCapability
import cp.player.core.util.currentTimeMillis

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
 * 账号与登录界面（遵循 Material 3 Expressive 规范重构）。
 *
 * ### 界面清晰分流设计
 *
 * 1. **未登录态（专为登录设计）**：
 *    - 欢迎引导 Hero：展示当前音源品牌信息与登录权益说明，摒弃冰冷的灰色假人头。
 *    - M3E 分段登录通道切换器：横向胶囊切换（扫码 / 手机号 / 邮箱 / Cookie），当前音源支持的方式一目了然。
 *    - 沉浸式登录面板：
 *      - 扫码登录：高对比度白色圆角底板，实时轮询状态徽标，支持刷新、保存相册及直达客户端 App。
 *      - 手机号登录：输入框 + 图形验证码（按需）+ 短信验证码 + 全宽主登录按钮。
 *      - 邮箱登录：邮箱 + 密码（密码隐藏遮罩）+ 全宽主登录按钮。
 *      - Cookie 登录：多行文本框带实时清洗识别校验与安全脱敏回显。
 *    - 底部辅助入口：游客模式体验与切换音源入口。
 *
 * 2. **已登录态（账号与个人中心）**：
 *    - M3E 个人信息卡片：圆角头像、加粗昵称、音源徽标与 UID、个人主页与私信快捷入口。
 *    - 已保存账号管理：多账号列表，当前登录项高亮指示，支持一键平滑切号与二次确认移除。
 *    - 添加新账号：可折叠展开的登录面板，保持多账号流程自洽。
 *    - 音源服务信息卡片：展示当前音源状态，支持便捷换源。
 *    - 危险操作区：符合破坏性操作规范的登出按钮，配备二次确认保护。
 *
 * 3. **无用与晦涩设置清理**：
 *    - 移除暴露内部技术细节的「音源账号隔离」设置开关与说明，音源底层自动隔离保持默认良好运作，不在界面滋扰用户。
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
        val verifyUrl by model.verifyUrl.collectAsState()
        val webviewLoginUrl by model.webviewLoginUrl.collectAsState()
        val qrUrl by model.qrUrl.collectAsState()
        val qrImgBase64 by model.qrImgBase64.collectAsState()
        val qrRestored by model.qrRestored.collectAsState()
        val targetAppName by model.targetAppName.collectAsState()
        val targetAppInstalled by model.targetAppInstalled.collectAsState()
        val accounts by model.accounts.collectAsState()
        val method by model.method.collectAsState()
        val showForm by model.showForm.collectAsState()
        val loginChannels by model.loginChannels.collectAsState()
        val supportsCaptchaImage by model.supportsCaptchaImage.collectAsState()
        val captchaImage by model.captchaImage.collectAsState()
        val imageCaptcha by model.imageCaptcha.collectAsState()

        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var phone by remember { mutableStateOf("") }
        var captcha by remember { mutableStateOf("") }
        var cookieText by remember { mutableStateOf("") }

        val confirm = rememberConfirmState()
        val clipboardManager = LocalClipboardManager.current

        val body: @Composable (Modifier) -> Unit = { pageModifier ->
            SettingsPage(pageModifier) {
                // 状态消息提醒
                message?.let {
                    SettingsNote(it, color = MaterialTheme.colorScheme.primary)
                }

                verifyUrl?.let { url ->
                    SecurityVerificationCard(
                        verifyUrl = url,
                        onOpenWebview = { model.openVerifyUrl() },
                        onDismiss = { model.clearVerifyUrl() },
                        onCopyUrl = {
                            clipboardManager.setText(AnnotatedString(url))
                            model.message.value = s.account.verifyCopied
                        },
                    )
                }

                if (qrRestored && (!isLogged || showForm)) {
                    SettingsNote(s.account.qrRestored, emphasis = SettingsNoteEmphasis.WARNING)
                }

                if (!isLogged) {
                    // ================= 未登录态：明确为登录界面 =================
                    val currentProvider = provider
                    val unloggedHasSettings = !currentProvider?.settingsSchema.isNullOrEmpty()
                    LoginBrandHeader(
                        providerName = currentProvider?.name,
                        providerType = currentProvider?.type?.name,
                        onSwitchProvider = { navigator.push(ProviderManagementScreen()) },
                        onOpenSettings = if (unloggedHasSettings && currentProvider != null) {
                            { navigator.push(ProviderCustomSettingsScreen(currentProvider.id, currentProvider.name, currentProvider.settingsSchema!!)) }
                        } else null,
                    )

                    Spacer(Modifier.height(8.dp))

                    // 登录通道选择分段切换器
                    LoginChannelSelector(
                        channels = loginChannels,
                        selected = method,
                        onSelect = { model.setChannel(it) },
                    )

                    Spacer(Modifier.height(12.dp))

                    // 登录卡片容器
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(CpSpacing.formRowHorizontal),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            val normalizedCookie = CookieLogin.normalize(cookieText)

                            when (method) {
                                LoginChannel.QR -> QrLoginPanel(
                                    qrUrl = qrUrl,
                                    qrImgBase64 = qrImgBase64,
                                    isLoading = isLoading,
                                    targetAppName = targetAppName,
                                    targetAppInstalled = targetAppInstalled,
                                    onSaveQr = { model.saveQrCode() },
                                    onOpenTargetApp = { model.openTargetApp() },
                                    onRefresh = { model.fetchQrCode() },
                                )
                                LoginChannel.EMAIL -> EmailLoginPanel(
                                    email = email,
                                    onEmailChange = { email = it },
                                    password = password,
                                    onPasswordChange = { password = it },
                                    isLoading = isLoading,
                                    onSubmit = { model.loginEmail(email, password) },
                                )
                                LoginChannel.PHONE -> PhoneLoginPanel(
                                    phone = phone,
                                    onPhoneChange = { phone = it },
                                    captcha = captcha,
                                    onCaptchaChange = { captcha = it },
                                    isLoading = isLoading,
                                    captchaImageUrl = captchaImage.takeIf { supportsCaptchaImage },
                                    imageCaptcha = imageCaptcha,
                                    onImageCaptchaChange = { model.imageCaptcha.value = it },
                                    onRefreshCaptcha = { model.fetchCaptchaImage() },
                                    onSendCaptcha = { model.sendCaptcha(phone) },
                                    onSubmit = { model.loginPhone(phone, captcha) },
                                )
                                LoginChannel.COOKIE -> CookieLoginPanel(
                                    raw = cookieText,
                                    onRawChange = { cookieText = it },
                                    normalized = normalizedCookie,
                                    isLoading = isLoading,
                                    onSubmit = { model.loginWithCookie(cookieText) },
                                )
                                LoginChannel.WEBVIEW -> WebviewLoginPanel(
                                    webviewUrl = webviewLoginUrl,
                                    isLoading = isLoading,
                                    onFetchUrl = { model.fetchWebviewLoginUrl() },
                                    onOpenWebview = { model.openWebviewLogin() },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // 底部辅助入口：游客登录与说明
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { model.loginAnonymous() },
                            enabled = !isLoading,
                        ) {
                            CpText(
                                s.account.guestLogin,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    // ================= 已登录态：明确为账号与个人中心 =================
                    profile?.let { me ->
                        AccountHeroCard(
                            nickname = me.nickname,
                            avatarUrl = me.avatarUrl,
                            uid = me.uid,
                            providerName = provider?.name ?: s.account.currentProvider,
                            onOpenProfile = { navigator.push(UserProfileScreen(me.uid, me.nickname)) },
                            onOpenMessages = { navigator.push(MessagesScreen()) },
                        )
                    }

                    // 已保存账号列表
                    SettingsSection(
                        s.account.accountsOf(provider?.name ?: s.account.currentProvider),
                    ) {
                        val activeUid = AccountStore.activeUid(model.providerId())
                            ?: profile?.uid?.toString()

                        accounts.forEachIndexed { index, account ->
                            val isActive = account.uid == activeUid
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
                                total = accounts.size + if (!showForm) 1 else 0,
                                selected = isActive,
                                mergeSemantics = false,
                                leadingContent = { AccountAvatar(url = account.avatarUrl, size = 36.dp) },
                                onClick = {
                                    if (!isActive) model.switchAccount(account)
                                },
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

                        if (!showForm) {
                            SettingsClickItem(
                                title = s.account.addAccount,
                                subtitle = s.account.addAccountNote,
                                icon = Icons.Filled.Add,
                                index = accounts.size,
                                total = accounts.size + 1,
                                onClick = { model.startAddAccount() },
                            )
                        }
                    }

                    // 展开的「添加新账号」面板
                    AnimatedVisibility(
                        visible = showForm,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = CpSpacing.formRowHorizontal, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CpText(
                                    s.account.addAccount,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                TextButton(onClick = { model.cancelAddAccount() }) {
                                    CpText(s.account.removeLabel.takeIf { false } ?: "收起")
                                }
                            }

                            LoginChannelSelector(
                                channels = loginChannels,
                                selected = method,
                                onSelect = { model.setChannel(it) },
                            )

                            Spacer(Modifier.height(8.dp))

                            Surface(
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(CpSpacing.formRowHorizontal),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    val normalizedCookie = CookieLogin.normalize(cookieText)
                                    when (method) {
                                        LoginChannel.QR -> QrLoginPanel(
                                            qrUrl = qrUrl,
                                            qrImgBase64 = qrImgBase64,
                                            isLoading = isLoading,
                                            targetAppName = targetAppName,
                                            targetAppInstalled = targetAppInstalled,
                                            onSaveQr = { model.saveQrCode() },
                                            onOpenTargetApp = { model.openTargetApp() },
                                            onRefresh = { model.fetchQrCode() },
                                        )
                                        LoginChannel.EMAIL -> EmailLoginPanel(
                                            email = email,
                                            onEmailChange = { email = it },
                                            password = password,
                                            onPasswordChange = { password = it },
                                            isLoading = isLoading,
                                            onSubmit = { model.loginEmail(email, password) },
                                        )
                                        LoginChannel.PHONE -> PhoneLoginPanel(
                                            phone = phone,
                                            onPhoneChange = { phone = it },
                                            captcha = captcha,
                                            onCaptchaChange = { captcha = it },
                                            isLoading = isLoading,
                                            captchaImageUrl = captchaImage.takeIf { supportsCaptchaImage },
                                            imageCaptcha = imageCaptcha,
                                            onImageCaptchaChange = { model.imageCaptcha.value = it },
                                            onRefreshCaptcha = { model.fetchCaptchaImage() },
                                            onSendCaptcha = { model.sendCaptcha(phone) },
                                            onSubmit = { model.loginPhone(phone, captcha) },
                                        )
                                        LoginChannel.COOKIE -> CookieLoginPanel(
                                            raw = cookieText,
                                            onRawChange = { cookieText = it },
                                            normalized = normalizedCookie,
                                            isLoading = isLoading,
                                            onSubmit = { model.loginWithCookie(cookieText) },
                                        )
                                        LoginChannel.WEBVIEW -> WebviewLoginPanel(
                                            webviewUrl = webviewLoginUrl,
                                            isLoading = isLoading,
                                            onFetchUrl = { model.fetchWebviewLoginUrl() },
                                            onOpenWebview = { model.openWebviewLogin() },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 当前音源信息
                    val hasCustomConfig = !provider?.settingsSchema.isNullOrEmpty()
                    val providerTotalItems = if (hasCustomConfig) 2 else 1
                    SettingsSection(s.account.currentProvider) {
                        SettingsClickItem(
                            title = provider?.name ?: s.account.noProvider,
                            subtitle = provider?.let { s.account.providerMeta(it.type.name, it.version) }
                                ?: s.account.noProviderNote,
                            icon = Icons.Filled.Extension,
                            index = 0,
                            total = providerTotalItems,
                            onClick = { navigator.push(ProviderManagementScreen()) },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CpText(
                                        s.account.switchProvider,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(CpIconSize.inline),
                                    )
                                }
                            },
                        )
                        val currentProvider = provider
                        if (hasCustomConfig && currentProvider != null) {
                            val schema = currentProvider.settingsSchema!!
                            SettingsClickItem(
                                title = "音源专属配置",
                                subtitle = "自定义 ${currentProvider.name} 的各项参数与高级选项",
                                icon = Icons.Filled.Tune,
                                index = 1,
                                total = providerTotalItems,
                                onClick = {
                                    navigator.push(ProviderCustomSettingsScreen(currentProvider.id, currentProvider.name, schema))
                                },
                                trailingContent = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(CpIconSize.inline),
                                    )
                                },
                            )
                        }
                    }

                    // 账号退出操作
                    SettingsSection(s.account.sectionAccountActions) {
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.large)
                                .clickable {
                                    confirm.request(
                                        title = s.account.logout,
                                        message = s.account.logoutNote,
                                        confirmLabel = s.account.logout,
                                        destructive = true,
                                        onConfirm = { model.logout() },
                                    )
                                },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = CpSpacing.formRowHorizontal,
                                        vertical = CpSpacing.formRowVertical + 4.dp,
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Logout,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(CpIconSize.inline),
                                )
                                Spacer(Modifier.width(8.dp))
                                CpText(
                                    s.account.logout,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }

        CpRouteScaffold(
            title = if (isLogged) s.account.screenTitle else (provider?.name?.let { "登录 $it" } ?: s.account.loginMethod),
            onBack = { navigator.popOrNotify() },
        ) { pageModifier -> body(pageModifier) }

        CpConfirmHost(confirm)
    }
}

/**
 * 未登录时的 M3E 品牌引导 Hero。
 */
@Composable
private fun LoginBrandHeader(
    providerName: String?,
    providerType: String?,
    onSwitchProvider: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
) {
    val s = cpStrings()
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(72.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Extension,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            CpText(
                text = providerName?.let { "登录 $it 账号" } ?: s.account.loginMethod,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            CpText(
                text = s.account.loginBenefitsNote,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onSwitchProvider,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(
                        Icons.Filled.SwapHoriz,
                        contentDescription = null,
                        modifier = Modifier.size(CpIconSize.inline),
                    )
                    Spacer(Modifier.width(6.dp))
                    CpText(
                        text = providerName?.let { "切换音源 (当前: $it)" } ?: s.account.switchProvider,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (onOpenSettings != null) {
                    OutlinedButton(
                        onClick = onOpenSettings,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(
                            Icons.Filled.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(CpIconSize.inline),
                        )
                        Spacer(Modifier.width(6.dp))
                        CpText(
                            text = "音源配置",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

/**
 * M3E 登录通道胶囊分段单选控件。
 *
 * 手机界面或窄屏下，5 个标签（扫码、手机号、邮箱、Cookie、WebView）若强行 weight(1f) 均分
 * 会导致单项宽度不足（仅 ~55-60dp），使图标与文字严重挤压甚至截断。
 * 此处采用响应式布局：
 * - 宽屏（>= 96dp * channels.size）时均分铺满，保证对称平整；
 * - 窄屏（手机端）时自动切换为流畅横向滚动的胶囊条，单项具有舒适的呼吸空间，并支持自动平滑滚动对焦到当前选中项。
 */
@Composable
private fun LoginChannelSelector(
    channels: List<LoginChannel>,
    selected: LoginChannel,
    onSelect: (LoginChannel) -> Unit,
) {
    val s = cpStrings()
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val minRequiredWidth = 96.dp * channels.size
        val canFitAll = maxWidth >= minRequiredWidth

        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large),
        ) {
            if (canFitAll) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    channels.forEach { channel ->
                        LoginChannelTabItem(
                            channel = channel,
                            isSelected = channel == selected,
                            label = channel.labelOf(s),
                            modifier = Modifier.weight(1f),
                            onClick = { onSelect(channel) },
                        )
                    }
                }
            } else {
                val listState = rememberLazyListState()
                val selectedIndex = channels.indexOf(selected)
                LaunchedEffect(selected) {
                    if (selectedIndex in channels.indices) {
                        listState.animateScrollToItem(selectedIndex)
                    }
                }
                LazyRow(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(channels, key = { it.name }) { channel ->
                        LoginChannelTabItem(
                            channel = channel,
                            isSelected = channel == selected,
                            label = channel.labelOf(s),
                            onClick = { onSelect(channel) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginChannelTabItem(
    channel: LoginChannel,
    isSelected: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val icon = when (channel) {
        LoginChannel.QR -> Icons.Filled.QrCode2
        LoginChannel.PHONE -> Icons.Filled.Phone
        LoginChannel.EMAIL -> Icons.Filled.Email
        LoginChannel.COOKIE -> Icons.Filled.Key
        LoginChannel.WEBVIEW -> Icons.Filled.Language
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            CpText(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * 扫码登录面板。
 */
@Composable
private fun QrLoginPanel(
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CpText(
            text = s.qrHint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))

        // 二维码白底容器：避免深色主题反色导致摄像头无法扫码
        Surface(
            color = Color.White,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .size(220.dp)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(16.dp),
                ),
        ) {
            Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
                when {
                    isLoading -> CpLoadingIndicator(Modifier.size(44.dp))
                    qrUrl != null -> QrCodeImage(qrUrl, Modifier.size(200.dp))
                    else -> CpText(
                        s.qrLoadFailed,
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onRefresh,
                enabled = !isLoading,
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(CpIconSize.inline))
                Spacer(Modifier.width(6.dp))
                CpText(s.refreshQr)
            }
            if (qrImgBase64 != null) {
                OutlinedButton(
                    onClick = onSaveQr,
                    enabled = !isLoading,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(CpIconSize.inline))
                    Spacer(Modifier.width(6.dp))
                    CpText(s.saveQr)
                }
            }
        }

        if (targetAppName != null) {
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onOpenTargetApp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().height(44.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(CpIconSize.inline))
                Spacer(Modifier.width(8.dp))
                CpText(
                    if (targetAppInstalled) {
                        s.openApp(targetAppName)
                    } else {
                        s.installApp(targetAppName)
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/**
 * 邮箱登录面板。
 */
@Composable
private fun EmailLoginPanel(
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    isLoading: Boolean,
    onSubmit: () -> Unit,
) {
    val s = cpStrings().account
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            label = { CpText(s.emailLabel) },
            leadingIcon = { Icon(Icons.Filled.Email, null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { CpText(s.passwordLabel) },
            leadingIcon = { Icon(Icons.Filled.Lock, null) },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
        )
        CpText(
            text = s.passwordNote,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onSubmit,
            enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            if (isLoading) {
                CpLoadingIndicator(Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                CpText(s.loggingIn)
            } else {
                CpText(s.channelEmail, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/**
 * 手机号登录面板。
 */
@Composable
private fun PhoneLoginPanel(
    phone: String,
    onPhoneChange: (String) -> Unit,
    captcha: String,
    onCaptchaChange: (String) -> Unit,
    isLoading: Boolean,
    captchaImageUrl: String? = null,
    imageCaptcha: String = "",
    onImageCaptchaChange: (String) -> Unit = {},
    onRefreshCaptcha: () -> Unit = {},
    onSendCaptcha: () -> Unit,
    onSubmit: () -> Unit,
) {
    val s = cpStrings().account
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = phone,
            onValueChange = onPhoneChange,
            label = { CpText(s.phoneLabel) },
            leadingIcon = { Icon(Icons.Filled.Phone, null) },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
        )
        if (captchaImageUrl != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = imageCaptcha,
                    onValueChange = onImageCaptchaChange,
                    label = { CpText(s.imageCaptchaLabel) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.width(110.dp).height(54.dp).clip(MaterialTheme.shapes.small),
                ) {
                    AsyncImage(
                        model = captchaImageUrl,
                        contentDescription = s.imageCaptchaLabel,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                FilledTonalIconButton(
                    onClick = onRefreshCaptcha,
                    enabled = !isLoading,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = s.refreshCaptcha)
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
                label = { CpText(s.captchaOrPassword) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
            )
            Button(
                onClick = onSendCaptcha,
                enabled = phone.isNotBlank() && !isLoading,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.height(54.dp),
            ) {
                CpText(s.sendCaptcha)
            }
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onSubmit,
            enabled = !isLoading && phone.isNotBlank(),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            if (isLoading) {
                CpLoadingIndicator(Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                CpText(s.loggingIn)
            } else {
                CpText(s.channelPhone, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/**
 * Cookie 登录面板。
 */
@Composable
private fun CookieLoginPanel(
    raw: String,
    onRawChange: (String) -> Unit,
    normalized: String?,
    isLoading: Boolean,
    onSubmit: () -> Unit,
) {
    val s = cpStrings().account
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CookieLoginForm(
            raw = raw,
            onRawChange = onRawChange,
            normalized = normalized,
        )
        Button(
            onClick = onSubmit,
            enabled = !isLoading && normalized != null,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            if (isLoading) {
                CpLoadingIndicator(Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                CpText(s.loggingIn)
            } else {
                CpText(s.channelCookie, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/**
 * 供测试与外部调用的 Cookie 登录表单。
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
            label = { CpText(s.cookieLabel) },
            leadingIcon = { Icon(Icons.Filled.Key, null) },
            placeholder = { CpText(s.cookiePlaceholder) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp),
            minLines = 3,
            maxLines = 6,
            singleLine = false,
            shape = MaterialTheme.shapes.medium,
            isError = raw.isNotBlank() && normalized == null,
            supportingText = { CpText(cookieHint(s, raw, normalized)) },
        )
        CpText(
            text = s.cookieHowTo,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 安全验证/风控挑战提示卡片。
 * 支持直接唤起系统 WebView / 外部浏览器完成人机或安全核验，并支持复制验证链接。
 */
@Composable
private fun SecurityVerificationCard(
    verifyUrl: String,
    onOpenWebview: () -> Unit,
    onDismiss: () -> Unit,
    onCopyUrl: () -> Unit,
) {
    val s = cpStrings().account
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(CpSpacing.formRowHorizontal),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Filled.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp),
                )
                CpText(
                    text = s.verifyRequiredTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = s.clearVerify,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            CpText(
                text = s.verifyUrlHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onOpenWebview,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f).height(42.dp),
                ) {
                    Icon(Icons.Filled.Language, null, Modifier.size(CpIconSize.inline))
                    Spacer(Modifier.width(6.dp))
                    CpText(s.openVerifyUrl)
                }
                OutlinedButton(
                    onClick = onCopyUrl,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.height(42.dp),
                ) {
                    Icon(Icons.Filled.ContentCopy, null, Modifier.size(CpIconSize.inline))
                    Spacer(Modifier.width(4.dp))
                    CpText(s.copyVerifyUrl)
                }
            }
        }
    }
}

/**
 * WebView 网页登录面板。
 */
@Composable
private fun WebviewLoginPanel(
    webviewUrl: String?,
    isLoading: Boolean,
    onFetchUrl: () -> Unit,
    onOpenWebview: () -> Unit,
) {
    val s = cpStrings().account
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Filled.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    CpText(
                        s.webviewLoginTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                CpText(
                    s.webviewLoginHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        if (webviewUrl != null) {
            Button(
                onClick = onOpenWebview,
                enabled = !isLoading,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(CpIconSize.inline))
                Spacer(Modifier.width(8.dp))
                CpText(s.openWebviewLogin, style = MaterialTheme.typography.titleSmall)
            }
        } else {
            Button(
                onClick = onFetchUrl,
                enabled = !isLoading,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (isLoading) {
                    CpLoadingIndicator(Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    CpText(s.webviewUrlLoading)
                } else {
                    Icon(Icons.Filled.Language, null, Modifier.size(CpIconSize.inline))
                    Spacer(Modifier.width(8.dp))
                    CpText(s.fetchWebviewUrl, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

/**
 * 已登录状态下的 M3E 个人信息 Hero 卡片。
 */
@Composable
private fun AccountHeroCard(
    nickname: String?,
    avatarUrl: String?,
    uid: Long?,
    providerName: String,
    onOpenProfile: () -> Unit,
    onOpenMessages: () -> Unit,
) {
    val s = cpStrings()
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .size(84.dp)
                        .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), CircleShape),
                ) {
                    AccountAvatar(
                        url = avatarUrl,
                        size = 80.dp,
                        fallbackTint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            CpText(
                text = nickname.orEmpty().ifBlank { s.account.loggedIn },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.height(4.dp))

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                CpText(
                    text = buildString {
                        append(providerName)
                        if (uid != null) {
                            append(" · ")
                            append(s.account.accountId(uid.toString()))
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilledTonalButton(
                    onClick = onOpenProfile,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(CpIconSize.inline))
                    Spacer(Modifier.width(6.dp))
                    CpText(s.account.myProfile, style = MaterialTheme.typography.labelLarge)
                }
                FilledTonalButton(
                    onClick = onOpenMessages,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Message, contentDescription = null, modifier = Modifier.size(CpIconSize.inline))
                    Spacer(Modifier.width(6.dp))
                    CpText(s.account.messages, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/**
 * 头像：有 URL 走图片，否则回退到 Person 图标。
 */
@Composable
private fun AccountAvatar(
    url: String?,
    size: androidx.compose.ui.unit.Dp,
    fallbackTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
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

/**
 * 账号与登录 ScreenModel。
 */
class AccountScreenModel(private val account: AccountStrings) : ScreenModel {
    val activeProvider: StateFlow<cp.player.core.provider.BackendProvider?> = AppModel.activeProviderFlow
    val isLoading = MutableStateFlow(false)
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
    val captchaImage = MutableStateFlow<String?>(null)
    val imageCaptcha = MutableStateFlow("")
    val verifyUrl = MutableStateFlow<String?>(null)
    val webviewLoginUrl = MutableStateFlow<String?>(null)
    private var captchaSessionCookie: String? = null

    private var qrJob: Job? = null

    init {
        screenModelScope.launch {
            var lastProviderId: String? = null
            activeProvider.collect { provider ->
                val currentProviderId = provider?.id
                val isProviderSwitched = lastProviderId != null && lastProviderId != currentProviderId
                lastProviderId = currentProviderId

                if (isProviderSwitched) {
                    cancelQrPolling()
                    qrUrl.value = null
                    qrImgBase64.value = null
                    qrRestored.value = false
                    webviewLoginUrl.value = null
                    captchaImage.value = null
                    captchaSessionCookie = null
                    verifyUrl.value = null
                    message.value = null
                    isLogged.value = false
                    accounts.value = emptyList()
                }

                val pkg = provider?.targetAppPackage
                if (!pkg.isNullOrEmpty()) {
                    targetAppName.value = provider.name
                    targetAppInstalled.value = isPackageInstalled(pkg)
                } else {
                    targetAppName.value = null
                    targetAppInstalled.value = false
                }
                val declared = provider?.loginMethods
                    ?.map { it.trim().lowercase() }
                    ?.filter { it.isNotEmpty() }
                loginChannels.value = if (declared.isNullOrEmpty()) {
                    LoginChannel.DEFAULT
                } else {
                    buildList {
                        if ("qr" in declared) add(LoginChannel.QR)
                        if ("sms" in declared || "phone" in declared) add(LoginChannel.PHONE)
                        if ("email" in declared) add(LoginChannel.EMAIL)
                        if ("webview" in declared || "web" in declared) {
                            add(LoginChannel.WEBVIEW)
                        }
                        add(LoginChannel.COOKIE)
                    }
                }
                supportsCaptchaImage.value = declared?.contains("captchaimage") == true
                if (method.value !in loginChannels.value) {
                    method.value = loginChannels.value.first()
                }
                checkLoginStatus()
                if (!isLogged.value && method.value == LoginChannel.QR && LoginChannel.QR in loginChannels.value) {
                    restoreOrFetchQrCode()
                }
            }
        }
    }

    fun providerId(): String = activeProvider.value?.id ?: AppModel.activeProviderId()

    fun setChannel(channel: LoginChannel) {
        method.value = channel
        if (channel == LoginChannel.QR) restoreOrFetchQrCode() else cancelQrPolling()
        if (channel == LoginChannel.PHONE && supportsCaptchaImage.value) fetchCaptchaImage()
        if (channel == LoginChannel.WEBVIEW && webviewLoginUrl.value == null) fetchWebviewLoginUrl()
    }

    fun fetchWebviewLoginUrl() {
        screenModelScope.launch {
            isLoading.value = true
            try {
                val resp = AppModel.authRepository.getWebviewLoginUrl()
                val url = extractVerifyUrl(resp) ?: ((resp as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull
                if (!url.isNullOrBlank()) {
                    webviewLoginUrl.value = url
                } else {
                    message.value = account.webviewUrlFailed
                }
            } catch (e: Exception) {
                message.value = account.webviewUrlFailed
            } finally {
                isLoading.value = false
            }
        }
    }

    fun openWebviewLogin(customUrl: String? = null) {
        val url = customUrl?.takeIf { it.isNotBlank() } ?: webviewLoginUrl.value
        if (!url.isNullOrBlank()) {
            openWebview(url, account.webviewLoginTitle)
        } else {
            fetchWebviewLoginUrl()
        }
    }

    fun openVerifyUrl() {
        verifyUrl.value?.let { openWebview(it, account.verifyRequiredTitle) }
    }

    fun clearVerifyUrl() {
        verifyUrl.value = null
    }

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
        val currentUid = profile?.uid?.toString()
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

    private suspend fun checkLoginStatus() {
        val hasCookie = AppModel.cookieStorage.getCookie(providerId())?.isNotEmpty() == true
        if (!hasCookie) {
            isLogged.value = false
            refreshAccounts()
            return
        }
        val body = runCatching { AppModel.authRepository.getLoginStatus() }.getOrNull()
        val ok = isLoggedInStatus(body)
        isLogged.value = ok
        if (ok) {
            runCatching { AppModel.refreshUserProfileAwait() }
            message.value = account.loginRestored
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

    private fun cancelQrPolling() {
        qrJob?.cancel()
        qrJob = null
    }

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
                when (resolveQrStatus(resp)) {
                    QrStatusKind.WAITING -> message.value = account.qrWaiting
                    QrStatusKind.SCANNED -> message.value = account.qrScanned
                    QrStatusKind.SUCCESS -> {
                        onLoginSucceeded(resp.uniCookie())
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
        screenModelScope.launch {
            isLoading.value = true
            try {
                val body = AppModel.authRepository.login(email, password)
                val vUrl = extractVerifyUrl(body)
                if (vUrl != null || isVerificationChallenge(body)) {
                    verifyUrl.value = vUrl
                    message.value = account.verifyRequired
                    return@launch
                }
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
                    val vUrl = extractVerifyUrl(body)
                    if (vUrl != null || isVerificationChallenge(body)) {
                        verifyUrl.value = vUrl
                        message.value = account.verifyRequired
                        return@launch
                    }
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
                        val vUrl = extractVerifyUrl(body)
                        if (vUrl != null || isVerificationChallenge(body)) {
                            verifyUrl.value = vUrl
                            message.value = account.verifyRequired
                            return@launch
                        }
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
            QrLoginStore.clear(providerId)
            if (account.cookie.isNotBlank()) {
                AppModel.cookieStorage.saveCookie(providerId, account.cookie)
            }
            AccountStore.setActive(providerId, account.uid)
            val profile = AppModel.refreshUserProfileAwait()
            if (profile != null) {
                isLogged.value = true
                message.value = this@AccountScreenModel.account.switchedTo(profile.nickname)
            } else {
                if (previousCookie.isNullOrEmpty()) AppModel.cookieStorage.clear(providerId)
                else AppModel.cookieStorage.saveCookie(providerId, previousCookie)
                AccountStore.setActive(providerId, previousUid)
                val restored = AppModel.refreshUserProfileAwait()
                isLogged.value = restored != null
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
                // ⚠️ 移除**当前**账号也必须走一次服务端 logout：
                //   AccountStore.remove 只清 active 标记与 cookie，不碰接口缓存；
                //   而缓存键只含 providerId + cookie 哈希，同一音源再登录会命中
                //   上一个账号的 user/playlist、user/like、user/cloud 响应。
                //   失败无所谓（cookie 已经没了），但它必须在缓存能清之前被**调用过**。
                runCatching { AppModel.authRepository.logout() }
                AppModel.clearUserProfile()
                message.value = this@AccountScreenModel.account.removedSession(account.nickname)
            } else {
                message.value = this@AccountScreenModel.account.removedAccount(account.nickname)
            }
            refreshAccounts()
        }
    }

    fun logout() {
        cancelQrPolling()
        screenModelScope.launch {
            val providerId = providerId()
            runCatching { AppModel.authRepository.logout() }
            AppModel.cookieStorage.clear(providerId)
            AccountStore.setActive(providerId, null)
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
    WEBVIEW({ it.account.channelWebview }),
    ;

    companion object {
        val DEFAULT = listOf(QR, PHONE, EMAIL, COOKIE, WEBVIEW)
    }
}


private const val QR_POLL_INTERVAL_MS = 2000L
private const val QR_POLL_MAX = 120
private const val QR_FAILURE_LIMIT = 5
private const val QR_MAX_ROUNDS = 3

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

private enum class QrStatusKind {
    WAITING,
    SCANNED,
    SUCCESS,
    EXPIRED,
    UNKNOWN
}

private fun resolveQrStatus(resp: JsonElement?): QrStatusKind {
    if (resp == null) return QrStatusKind.UNKNOWN
    val obj = resp as? JsonObject
    val data = obj?.get("data") as? JsonObject

    val code = (obj?.get("code") as? JsonPrimitive)?.intOrNull
        ?: (data?.get("code") as? JsonPrimitive)?.intOrNull
    val status = (obj?.get("status") as? JsonPrimitive)?.intOrNull
        ?: (data?.get("status") as? JsonPrimitive)?.intOrNull
    val statusStr = (obj?.get("status") as? JsonPrimitive)?.contentOrNull?.uppercase()
        ?: (data?.get("status") as? JsonPrimitive)?.contentOrNull?.uppercase()
        ?: (obj?.get("state") as? JsonPrimitive)?.contentOrNull?.uppercase()
        ?: (data?.get("state") as? JsonPrimitive)?.contentOrNull?.uppercase()

    if (code == 803 || status == 803 || status == 2 || status == 4 ||
        statusStr in setOf("SUCCESS", "CONFIRMED", "LOGGED_IN", "FINISH") ||
        (!resp.uniCookie().isNullOrBlank() && (code == 200 || code == 0))) {
        return QrStatusKind.SUCCESS
    }
    if (code == 802 || status == 802 || status == 1 || statusStr in setOf("SCANNED", "WAITING_CONFIRM")) {
        return QrStatusKind.SCANNED
    }
    if (code == 800 || status == 800 || status == 3 || statusStr in setOf("EXPIRED", "TIMEOUT")) {
        return QrStatusKind.EXPIRED
    }
    if (code == 801 || status == 801 || status == 0 || statusStr in setOf("WAITING", "WAITING_SCAN")) {
        return QrStatusKind.WAITING
    }
    return QrStatusKind.UNKNOWN
}
