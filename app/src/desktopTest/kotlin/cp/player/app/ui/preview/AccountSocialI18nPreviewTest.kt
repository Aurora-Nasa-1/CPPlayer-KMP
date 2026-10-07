package cp.player.app.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.auth.CookieLogin
import cp.player.app.i18n.AppLanguage
import cp.player.app.i18n.ProvideCpStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.ScrollColumn
import cp.player.app.ui.component.SettingsButtonItem
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsDropdownItem
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.screen.CookieLoginForm
import cp.player.app.ui.screen.LoginChannel
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import java.io.File
import kotlin.test.Test

/**
 * 批次 5（账号与社交）的**多语言版式核对**（会真的出图）。
 *
 * ### 为什么另开一个文件而不是并进 `SettingsI18nPreviewTest`
 *
 * 账号页那一段**不是设置行**：它有 Hero、二维码、以及一段 400+ 字符的 Cookie 说明，
 * 塞进设置树的出图里会让那张图变得没法看；而社交三态（`ContentState`）与设置行
 * 又是两套组件。分开出图，每张图盯自己那一片。
 *
 * ### 这里渲染的是真实文案，不是手抄样例
 *
 * 每一行都直接读 [cpStrings()]，文案改了图就跟着变 —— 手抄的样例会在文案改完之后
 * 继续「通过」（`SettingsI18nPreviewTest` 的 KDoc 里写过同一个理由）。
 * 唯一不是真实数据的是账号 id / cookie 样本值：那是**页面上的数据**，不是文案。
 *
 * ⚠️ 整页（`AccountScreen`）本身不出图：它要登录态与音源，离屏渲染拿不到，
 * 所以这里按「这一片的组件」渲染（与 I18N.md §4.3 同一取舍）。
 */
class AccountSocialI18nPreviewTest {

    /** Cookie 样本值：够长才能看出「已识别 N 个字段」那条提示会不会撑破行。 */
    private val cookieSample =
        "MUSIC_U=00ABCDEF1234567890ABCDEF1234567890ABCDEF1234567890ABCDEF12; " +
            "__csrf=9f8e7d6c5b4a39281706f5e4d3c2b1a0"

    /** 账号页那一片（登录方式下拉 / 当前音源 / 音源隔离 / 账号列表 / 账号操作）。 */
    @Composable
    private fun AccountFields() {
        val s = cpStrings()
        ScrollColumn(Modifier.fillMaxSize()) {
            SettingsSection(s.account.loginMethod) {
                SettingsDropdownItem(
                    title = s.account.loginMethod,
                    // 枚举的显示名已经改成 `(CpStrings) -> String`（I18N.md §5.3）：
                    // 这里出图能看出「英文界面里混进中文渠道名」这类漏迁移。
                    options = LoginChannel.entries.map { it.labelOf(s) },
                    selectedIndex = 0,
                    onSelect = {},
                    index = 0,
                    total = 1,
                )
            }

            SettingsSection(s.account.sectionMine) {
                SettingsClickItem(
                    title = s.account.myProfile,
                    subtitle = s.account.myProfileNote,
                    icon = Icons.Filled.Person,
                    index = 0,
                    total = 2,
                    onClick = {},
                )
                SettingsClickItem(
                    title = s.account.messages,
                    subtitle = s.account.messagesNote,
                    icon = Icons.AutoMirrored.Filled.Message,
                    index = 1,
                    total = 2,
                    onClick = {},
                )
            }

            SettingsSection(s.account.currentProvider) {
                SettingsClickItem(
                    title = s.account.noProvider,
                    subtitle = s.account.noProviderNote,
                    icon = Icons.Filled.Extension,
                    index = 0,
                    total = 1,
                    onClick = null,
                )
            }

            SettingsSection(s.account.sectionIsolation) {
                SettingsSwitchItem(
                    title = s.account.isolationTitle,
                    subtitle = s.account.isolationSubtitle,
                    checked = true,
                    onCheckedChange = {},
                    index = 0,
                    total = 1,
                )
            }
            SettingsNote(s.account.isolationHint)

            SettingsSection(s.account.accountsOf(s.account.currentProvider)) {
                SettingsClickItem(
                    title = s.account.unknownAccount,
                    subtitle = s.account.accountId("1234567890") + s.account.activeSuffix,
                    index = 0,
                    total = 2,
                    selected = true,
                    onClick = {},
                )
                SettingsButtonItem(
                    text = s.account.addAccount,
                    subtitle = s.account.addAccountNote,
                    index = 1,
                    total = 2,
                    onClick = {},
                )
            }

            SettingsSection(s.account.sectionAccountActions) {
                SettingsButtonItem(
                    text = s.account.logout,
                    subtitle = s.account.logoutNote,
                    index = 0,
                    total = 1,
                    onClick = {},
                )
            }
            SettingsNote(s.account.loginBenefitsNote)
        }
    }

    /** Cookie 登录表单：英文下那三段说明最长，窄屏最容易翻车。 */
    @Composable
    private fun CookieForm() {
        Box(Modifier.fillMaxSize().padding(16.dp)) {
            CookieLoginForm(
                raw = cookieSample,
                onRawChange = {},
                normalized = CookieLogin.normalize(cookieSample),
            )
        }
    }

    /** 社交三态：私信 / 消息 / 空态提示。文案全部来自 `social.*`。 */
    @Composable
    private fun SocialStates() {
        val s = cpStrings()
        ScrollColumn(Modifier.fillMaxSize()) {
            ContentState(
                title = s.social.chat.loginRequired,
                message = s.social.chat.accountBound,
            )
            ContentState(
                title = s.social.messages.empty,
                message = s.social.messages.emptyHint,
            )
            ContentState(
                title = s.social.chat.empty,
                message = s.social.chat.emptyHint,
                actionLabel = s.player.retry,
                onAction = {},
                error = true,
            )
            ContentState(
                title = s.social.messages.selectConversation,
                message = s.social.messages.selectConversationNote,
            )
        }
    }

    private fun render(name: String, width: Int, height: Int, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.LIGHT) { content() }
        }
        try {
            val data = scene.render().encodeToData() ?: error("encode failed")
            val out = File("build-verify/preview").apply { mkdirs() }
            File(out, "$name.png").writeBytes(data.bytes)
        } finally {
            scene.close()
        }
    }

    @Test
    fun renderBothLanguages() {
        AppLanguage.entries
            .filter { it != AppLanguage.SYSTEM } // SYSTEM 取决于宿主 locale，出图不稳定
            .forEach { language ->
                val tag = when (language) {
                    AppLanguage.ZH_HANS -> "zh"
                    AppLanguage.ENGLISH -> "en"
                    AppLanguage.SYSTEM -> error("unreachable")
                }
                listOf(
                    "account" to @Composable { AccountFields() },
                    "cookie" to @Composable { CookieForm() },
                    "social" to @Composable { SocialStates() },
                ).forEach { (name, content) ->
                    render("i18n-$name-$tag-wide", 1000, 1200) {
                        ProvideCpStrings(language) {
                            Box(
                                Modifier.fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background),
                            ) { content() }
                        }
                    }
                    render("i18n-$name-$tag-narrow", 480, 1200) {
                        ProvideCpStrings(language) {
                            Box(
                                Modifier.fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background),
                            ) { content() }
                        }
                    }
                }
            }
    }
}
