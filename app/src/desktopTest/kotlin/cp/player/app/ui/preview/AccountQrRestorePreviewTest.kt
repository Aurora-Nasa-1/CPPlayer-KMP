package cp.player.app.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.i18n.AppLanguage
import cp.player.app.i18n.ProvideCpStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.component.ScrollColumn
import cp.player.app.ui.component.SettingsDropdownItem
import cp.player.app.ui.component.SettingsFieldGroup
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsNoteEmphasis
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.screen.QrCodeImage
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import java.io.File
import kotlin.test.Test

/**
 * 「上次没扫完的二维码被恢复」那条提示的版式核对（会真的出图）。
 *
 * ### 为什么要出图
 *
 * 它是一条**跨整页宽度**的强调条（`SettingsNote(WARNING)`），而且必须落在
 * `SettingsSection` **之外**（见 `SettingsKit.kt`：说明条是分段卡片之间的元素，
 * 塞进组里会把圆角拼接打断）。这两件事编译和单测都量不到 —— 单测只能断言
 * 「状态是 true 时渲染了这条」，量不到它有没有撑破行、有没有把卡片切开。
 *
 * 英文侧尤其要看：`Restored the QR code you didn't finish scanning …` 比中文长一半，
 * 窄屏最容易出现「一行挤成两行半」。
 *
 * ⚠️ 渲染的是**真实文案 + 真实组件**（`CpStrings` 现取、`SettingsNote` 现用），
 * 不是这里手抄的样例 —— 手抄的样例在文案改了之后会继续「通过」。
 * 扫码区本身用的是真的 [QrCodeImage]，但页面外壳是照 [cp.player.app.ui.screen.AccountScreen]
 * 的结构摆的（那个页面依赖 `AppModel` + 网络，起不来离屏渲染）。
 */
class AccountQrRestorePreviewTest {

    private val qrUrl = "https://music.163.com/login?codekey=0123456789abcdef0123456789abcdef"

    /** 登录页未登录态的结构：状态提示 → 恢复提示 → 登录方式 → 扫码区。 */
    @Composable
    private fun LoginFragment(narrow: Boolean) {
        val s = cpStrings()
        ScrollColumn(Modifier.fillMaxSize()) {
            // 轮询消息（真实页面里它在最上面，2 秒一刷）
            SettingsNote(s.account.qrWaiting, color = MaterialTheme.colorScheme.primary)
            // 本次新增的那一条
            SettingsNote(s.account.qrRestored, emphasis = SettingsNoteEmphasis.WARNING)

            SettingsSection(s.account.loginMethod) {
                SettingsDropdownItem(
                    title = s.account.loginMethod,
                    options = listOf(s.account.channelQr, s.account.channelEmail, s.account.channelCookie),
                    selectedIndex = 0,
                    onSelect = {},
                    index = 0,
                    total = 1,
                )
            }

            SettingsFieldGroup {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = s.account.qrHint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    QrCodeImage(qrUrl, Modifier.size(if (narrow) 180.dp else 200.dp))
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = {}) {
                            Text(s.account.refreshQr)
                        }
                        TextButton(onClick = {}) {
                            Text(s.account.saveQr)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    private fun render(
        name: String,
        width: Int,
        height: Int,
        dark: Boolean,
        language: AppLanguage,
        narrow: Boolean = false,
    ) {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            CpTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                ProvideCpStrings(language) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        LoginFragment(narrow = narrow)
                    }
                }
            }
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
    fun renderPreviews() {
        // 桌面宽屏（正常登录页宽度）
        render("qr-restore-zh-wide", 900, 900, dark = false, language = AppLanguage.ZH_HANS)
        render("qr-restore-en-wide", 900, 900, dark = false, language = AppLanguage.ENGLISH)
        // 窄屏：英文长句最容易在这档翻车
        render("qr-restore-en-narrow", 400, 900, dark = false, language = AppLanguage.ENGLISH, narrow = true)
        render("qr-restore-en-narrow-dark", 400, 900, dark = true, language = AppLanguage.ENGLISH, narrow = true)
        // 深色：强调条用的是 tertiaryContainer，别在深色下变成隐形底
        render("qr-restore-zh-dark", 900, 900, dark = true, language = AppLanguage.ZH_HANS)
    }
}
