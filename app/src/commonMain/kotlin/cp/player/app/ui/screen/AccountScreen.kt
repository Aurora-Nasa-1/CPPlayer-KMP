package cp.player.app.ui.screen

import cp.player.app.ui.component.CpText
import cp.player.app.ui.theme.cpNestedSurface
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import coil3.compose.AsyncImage
import cp.player.app.AppModel
import cp.player.app.auth.AccountStore
import cp.player.app.auth.CookieLogin
import cp.player.app.i18n.AccountStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.component.CpConfirmHost
import cp.player.app.ui.component.CpIconSize
import cp.player.app.ui.component.CpLoadingIndicator
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.CpToggleChip
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsNoteEmphasis
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.rememberConfirmState
import cp.player.app.ui.component.settingsRowHighlightContent
import cp.player.app.ui.theme.cpCardSurface
import cp.player.app.presentation.navigation.AppRoute
import cp.player.app.ui.navigation.LocalAppNavigator
import kotlinx.coroutines.launch

/**
 * 账号与登录界面（遵循 Material 3 Expressive 规范重构）。
 *
 * ### 界面清晰分流设计
 *
 * 1. **未登录态（专为登录设计）**：
 *    - 欢迎引导 Hero：展示当前音源品牌信息与登录权益说明，摒弃冰冷的灰色假人头。
 *    - M3E 分段登录通道切换器：横向胶囊切换（扫码 / 手机号 / 邮箱 / Cookie），当前音源支持的方式一目了然。
 *    - 沉浸式登录面板：
 *      - 扫码登录：高对比度白色圆角底板，实时轮询状态徽标，支持刷新、保存相册；
 *        点「打开 客户端 App」跳转前会**自动把当前二维码存一份到相册**（同一张只存一次），
 *        因为跳走之后登录页上的二维码就看不见了。
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
        val navigator = LocalAppNavigator.current
        val s = cpStrings()
        val model = rememberScreenModel { AccountScreenModel(s.account) }
        val provider by model.activeProvider.collectAsState()
        val profile by AppModel.userProfileFlow.collectAsState()
        val isLogged by model.isLogged.collectAsState()
        val isLoading by model.isLoading.collectAsState()
        val message by model.message.collectAsState()
        val verifyUrl by model.verifyUrl.collectAsState()
        val qrRestored by model.qrRestored.collectAsState()
        val accounts by model.accounts.collectAsState()
        val showForm by model.showForm.collectAsState()

        val confirm = rememberConfirmState()
        @Suppress("DEPRECATION")
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
                    val schema = currentProvider?.settingsSchema
                    LoginCompactHeader(
                        providerName = currentProvider?.name,
                        onSwitchProvider = { navigator.push(AppRoute.PluginHub()) },
                        onOpenSettings = if (currentProvider != null && !schema.isNullOrEmpty()) {
                            { navigator.push(AppRoute.ProviderCustomSettings(currentProvider.id, currentProvider.name, schema)) }
                        } else null,
                    )

                    Spacer(Modifier.height(8.dp))

                    AccountLoginPane(model)

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
                            uid = me.resourceId,
                            providerName = provider?.name ?: s.account.currentProvider,
                            onOpenProfile = { navigator.push(AppRoute.UserProfile(me.uid, me.nickname, me.resourceId)) },
                            onOpenMessages = { navigator.push(AppRoute.Messages) },
                        )
                    }

                    // 已保存账号列表
                    SettingsSection(
                        s.account.accountsOf(provider?.name ?: s.account.currentProvider),
                    ) {
                        val activeUid = AccountStore.activeUid(model.providerId())
                            ?: profile?.resourceId

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
                                    CpText(s.player.collapse)
                                }
                            }

                            AccountLoginPane(model)
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
                            onClick = { navigator.push(AppRoute.PluginHub()) },
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
                                title = s.account.providerCustomSettings,
                                subtitle = s.account.providerCustomSettingsNote(currentProvider.name),
                                icon = Icons.Filled.Tune,
                                index = 1,
                                total = providerTotalItems,
                                onClick = {
                                    navigator.push(AppRoute.ProviderCustomSettings(currentProvider.id, currentProvider.name, schema))
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
                            color = MaterialTheme.colorScheme.errorContainer,
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
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(CpIconSize.inline),
                                )
                                Spacer(Modifier.width(8.dp))
                                CpText(
                                    s.account.logout,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }
            }
        }

        CpRouteScaffold(
            title = if (isLogged) {
                s.account.screenTitle
            } else {
                provider?.name?.let { s.account.loginAs(it) } ?: s.account.loginMethod
            },
            onBack = { navigator.pop() },
        ) { pageModifier -> body(pageModifier) }

        CpConfirmHost(confirm)
    }
}

/**
 * 未登录时的紧凑标题。
 *
 * 刻意不再放大卡片和 72dp 图标：登录页的主角是登录动作本身。大卡片只留给
 * 登录后的 [AccountHeroCard]，未登录时用标题 + 权益说明 + 轻量音源入口即可。
 */
@Composable
internal fun LoginCompactHeader(
    providerName: String?,
    onSwitchProvider: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
) {
    val s = cpStrings()
    Column(modifier = Modifier.fillMaxWidth()) {
        CpText(
            text = providerName?.let { s.account.loginAs(it) } ?: s.account.loginAccount,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
        )
        Spacer(Modifier.height(6.dp))
        CpText(
            text = s.account.loginBenefitsNote,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = Int.MAX_VALUE,
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onSwitchProvider,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Icon(
                    Icons.Filled.SwapHoriz,
                    contentDescription = null,
                    modifier = Modifier.size(CpIconSize.inline),
                )
                Spacer(Modifier.width(4.dp))
                CpText(
                    text = s.account.switchProvider,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (onOpenSettings != null) {
                TextButton(
                    onClick = onOpenSettings,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Icon(
                        Icons.Filled.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(CpIconSize.inline),
                    )
                    Spacer(Modifier.width(4.dp))
                    CpText(
                        text = s.account.providerSettings,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

/**
 * 登录面板（通道切换器 + 当前通道表单）。
 *
 * 账号页未登录态、账号页「添加新账号」与引导登录步骤共用。标题、游客入口和
 * 状态提示由各自页面负责，这里只关心登录动作本身。
 *
 * @param wrapChannels 引导页里用可换行胶囊，避免内层横向滚动与 pager 抢手势。
 */
@Composable
internal fun AccountLoginPane(
    model: AccountScreenModel,
    modifier: Modifier = Modifier,
    wrapChannels: Boolean = false,
) {
    val method by model.method.collectAsState()
    val loginChannels by model.loginChannels.collectAsState()
    val isLoading by model.isLoading.collectAsState()
    val qrUrl by model.qrUrl.collectAsState()
    val isSavingQr by model.isSavingQr.collectAsState()
    val targetAppName by model.targetAppName.collectAsState()
    val targetAppInstalled by model.targetAppInstalled.collectAsState()
    val supportsCaptchaImage by model.supportsCaptchaImage.collectAsState()
    val defaultCountryCode by model.defaultCountryCode.collectAsState()
    val captchaImage by model.captchaImage.collectAsState()
    val imageCaptcha by model.imageCaptcha.collectAsState()

    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var countryCode by rememberSaveable { mutableStateOf("") }
    var captcha by rememberSaveable { mutableStateOf("") }
    var cookieText by rememberSaveable { mutableStateOf("") }

    Column(modifier = modifier.fillMaxWidth()) {
        LoginChannelSelector(
            channels = loginChannels,
            selected = method,
            onSelect = { model.setChannel(it) },
            wrap = wrapChannels,
        )

        Spacer(Modifier.height(12.dp))

        Surface(
            shape = MaterialTheme.shapes.large,
            color = cpCardSurface(),
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
                        isLoading = isLoading,
                        isSavingQr = isSavingQr,
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
                        countryCode = countryCode,
                        onCountryCodeChange = { countryCode = it },
                        fixedCountryCode = defaultCountryCode,
                        captcha = captcha,
                        onCaptchaChange = { captcha = it },
                        isLoading = isLoading,
                        captchaImageUrl = captchaImage.takeIf { supportsCaptchaImage },
                        imageCaptcha = imageCaptcha,
                        onImageCaptchaChange = { model.imageCaptcha.value = it },
                        onRefreshCaptcha = { model.fetchCaptchaImage() },
                        onSendCaptcha = { model.sendCaptcha(phone, defaultCountryCode ?: countryCode) },
                        onSubmit = { useSmsCode ->
                            model.loginPhone(phone, captcha, useSmsCode, defaultCountryCode ?: countryCode)
                        },
                    )
                    LoginChannel.COOKIE -> CookieLoginPanel(
                        raw = cookieText,
                        onRawChange = { cookieText = it },
                        normalized = normalizedCookie,
                        isLoading = isLoading,
                        onSubmit = { model.loginWithCookie(cookieText) },
                    )
                }
            }
        }
    }
}

/**
 * M3E 登录通道胶囊分段单选控件。
 *
 * 手机界面或窄屏下，4 个标签（扫码、手机号、邮箱、Cookie）若强行 weight(1f) 均分
 * 会导致单项宽度不足（仅 ~55-60dp），使图标与文字严重挤压甚至截断。
 * 此处采用响应式布局：
 * - 宽屏（>= 96dp * channels.size）时均分铺满，保证对称平整；
 * - 窄屏（手机端）时自动切换为流畅横向滚动的胶囊条，单项具有舒适的呼吸空间，并支持自动平滑滚动对焦到当前选中项。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LoginChannelSelector(
    channels: List<LoginChannel>,
    selected: LoginChannel,
    onSelect: (LoginChannel) -> Unit,
    wrap: Boolean = false,
) {
    val s = cpStrings()
    if (wrap) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            channels.forEach { channel ->
                LoginChannelTabItem(
                    channel = channel,
                    isSelected = channel == selected,
                    label = channel.labelOf(s),
                    onClick = { onSelect(channel) },
                )
            }
        }
        return
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val minRequiredWidth = 96.dp * channels.size
        val canFitAll = maxWidth >= minRequiredWidth

        Surface(
            shape = MaterialTheme.shapes.large,
            color = cpNestedSurface(),
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
    isLoading: Boolean,
    isSavingQr: Boolean = false,
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
                        // QR 容器固定为纯白纸底，使用固定深灰确保浅/深色主题下均有高对比度
                        color = Color(0xFF47464F),
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
            if (qrUrl != null) {
                OutlinedButton(
                    onClick = onSaveQr,
                    enabled = !isLoading && !isSavingQr,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(CpIconSize.inline))
                    Spacer(Modifier.width(6.dp))
                    CpText(if (isSavingQr) s.savingQr else s.saveQr)
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
    countryCode: String,
    onCountryCodeChange: (String) -> Unit,
    fixedCountryCode: String? = null,
    captcha: String,
    onCaptchaChange: (String) -> Unit,
    isLoading: Boolean,
    captchaImageUrl: String? = null,
    imageCaptcha: String = "",
    onImageCaptchaChange: (String) -> Unit = {},
    onRefreshCaptcha: () -> Unit = {},
    onSendCaptcha: () -> Unit,
    onSubmit: (useSmsCode: Boolean) -> Unit,
) {
    val s = cpStrings().account
    var useSmsCode by rememberSaveable { mutableStateOf(true) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CpToggleChip(
                checked = useSmsCode,
                onCheckedChange = { if (it) useSmsCode = true },
                label = s.smsCodeLogin,
            )
            CpToggleChip(
                checked = !useSmsCode,
                onCheckedChange = { if (it) useSmsCode = false },
                label = s.passwordLogin,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (fixedCountryCode == null) {
                OutlinedTextField(
                    value = countryCode,
                    onValueChange = { onCountryCodeChange(it.filter(Char::isDigit).take(4)) },
                    label = { CpText(s.countryCodeLabel) },
                    placeholder = { CpText("86") },
                    modifier = Modifier.width(110.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
            }
            OutlinedTextField(
                value = phone,
                onValueChange = { onPhoneChange(it.filter(Char::isDigit)) },
                label = { CpText(s.phoneLabel) },
                leadingIcon = { Icon(Icons.Filled.Phone, null) },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
            )
        }
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
                    color = cpNestedSurface(),
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
                label = { CpText(if (useSmsCode) s.smsCodeLogin else s.passwordLabel) },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (useSmsCode) KeyboardType.Number else KeyboardType.Password,
                ),
                visualTransformation = if (useSmsCode) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
            )
            if (useSmsCode) {
                Button(
                    onClick = onSendCaptcha,
                    enabled = phone.isNotBlank() && !isLoading,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.height(54.dp),
                ) {
                    CpText(s.sendCaptcha)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { onSubmit(useSmsCode) },
            enabled = !isLoading && phone.isNotBlank() && captcha.isNotBlank(),
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
            // 提示里有「没解析出任何 name=value」这类完整诊断 + 已识别的字段清单，
            // 单行会把原因裁掉，用户只能看到结果看不到为什么。
            supportingText = { CpText(cookieHint(s, raw, normalized), maxLines = Int.MAX_VALUE) },
        )
        CpText(
            text = s.cookieHowTo,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // 这是「怎么获取 Cookie」的完整步骤（F12 → Network → 复制请求头），
            // 单行只剩开头一句，等于没有教程。
            maxLines = Int.MAX_VALUE,
        )
    }
}

/**
 * 安全验证/风控挑战提示卡片。
 * 支持直接唤起系统 WebView / 外部浏览器完成人机或安全核验，并支持复制验证链接。
 */
@Composable
internal fun SecurityVerificationCard(
    verifyUrl: String,
    onOpenWebview: () -> Unit,
    onDismiss: () -> Unit,
    onCopyUrl: () -> Unit,
) {
    val s = cpStrings().account
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
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
                    tint = MaterialTheme.colorScheme.onErrorContainer,
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
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            CpText(
                text = s.verifyUrlHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
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
 * 已登录状态下的 M3E 个人信息 Hero 卡片。
 */
@Composable
private fun AccountHeroCard(
    nickname: String?,
    avatarUrl: String?,
    uid: String?,
    providerName: String,
    onOpenProfile: () -> Unit,
    onOpenMessages: () -> Unit,
) {
    val s = cpStrings()
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = cpCardSurface(),
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
                color = cpNestedSurface(),
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                CpText(
                    text = buildString {
                        append(providerName)
                        if (uid != null) {
                            append(" · ")
                            append(s.account.accountId(uid))
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
        color = cpNestedSurface(),
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
    !normalized.contains(';') && !normalized.contains(' ') ->
        strings.cookieTokenAccepted + CookieLogin.mask(normalized)
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

/** 账号页的 Voyager 适配层（Thin Adapter）。状态机在 [cp.player.app.presentation.account.AccountPresenter]。 */
class AccountScreenModel(account: AccountStrings) : ScreenModel {
    private val presenter = cp.player.app.presentation.account.AccountPresenter(
        account = account,
        scope = screenModelScope,
        qrPngBytes = ::qrCodePngBytes,
    )

    val activeProvider get() = presenter.activeProvider
    val isLoading get() = presenter.isLoading
    val isSavingQr get() = presenter.isSavingQr
    val isLogged get() = presenter.isLogged
    val message get() = presenter.message
    val qrUrl get() = presenter.qrUrl
    val qrImgBase64 get() = presenter.qrImgBase64
    val qrRestored get() = presenter.qrRestored
    val targetAppName get() = presenter.targetAppName
    val targetAppInstalled get() = presenter.targetAppInstalled
    val accounts get() = presenter.accounts
    val method get() = presenter.method
    val showForm get() = presenter.showForm
    val loginChannels get() = presenter.loginChannels
    val supportsCaptchaImage get() = presenter.supportsCaptchaImage
    val defaultCountryCode get() = presenter.defaultCountryCode
    val captchaImage get() = presenter.captchaImage
    val imageCaptcha get() = presenter.imageCaptcha
    val verifyUrl get() = presenter.verifyUrl

    fun providerId(): String = presenter.providerId()
    fun setChannel(channel: LoginChannel) = presenter.setChannel(channel)
    fun openVerifyUrl() = presenter.openVerifyUrl()
    fun clearVerifyUrl() = presenter.clearVerifyUrl()
    fun fetchCaptchaImage() = presenter.fetchCaptchaImage()
    fun startAddAccount() = presenter.startAddAccount()
    fun cancelAddAccount() = presenter.cancelAddAccount()
    fun fetchQrCode() = presenter.fetchQrCode()
    fun loginEmail(email: String, password: String) = presenter.loginEmail(email, password)
    fun loginPhone(phone: String, codeOrPass: String, useSmsCode: Boolean, countryCode: String? = null) =
        presenter.loginPhone(phone, codeOrPass, useSmsCode, countryCode)
    fun loginWithCookie(raw: String) = presenter.loginWithCookie(raw)
    fun sendCaptcha(phone: String, countryCode: String? = null) = presenter.sendCaptcha(phone, countryCode)
    fun loginAnonymous() = presenter.loginAnonymous()
    fun switchAccount(account: AccountStore.SavedAccount) = presenter.switchAccount(account)
    fun removeAccount(account: AccountStore.SavedAccount) = presenter.removeAccount(account)
    fun logout() = presenter.logout()
    fun saveQrCode() = presenter.saveQrCode()
    fun openTargetApp() = presenter.openTargetApp()
}

typealias LoginChannel = cp.player.app.presentation.account.LoginChannel

