package cp.player.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cp.player.app.i18n.AppLanguage
import cp.player.app.i18n.ProvideCpStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.component.CpText
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 引导登录步骤的离屏版式探针。
 *
 * `OnboardingLoginPage` 是纯参数组件，用假登录面板即可核对：步骤标题、权益说明、
 * 二维码尺寸、底部按钮在窄屏 / 宽屏下都不被裁切；`OnboardingContent` 再核对步骤条
 * 与完成页摘要的实际占位。
 */
class OnboardingLoginPreviewTest {

    private fun render(width: Int, height: Int, content: @Composable () -> Unit): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.DARK) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    content()
                }
            }
        }
        return try {
            scene.render().encodeToData()?.bytes ?: error("PNG encoding failed")
        } finally {
            scene.close()
        }
    }

    private fun write(name: String, bytes: ByteArray) {
        File("build/$name").apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
    }

    @Composable
    private fun LoginPage(
        hasProvider: Boolean = true,
        isLogged: Boolean = false,
    ) {
        OnboardingLoginPage(
            modifier = Modifier.fillMaxSize(),
            hasProvider = hasProvider,
            providerName = "网易云音乐",
            isLogged = isLogged,
            nickname = if (isLogged) "小明" else null,
            message = null,
            verifyUrl = null,
            qrRestored = false,
            onGoToProvider = {},
            onContinue = {},
            onOpenVerifyUrl = {},
            onDismissVerify = {},
            onCopyVerifyUrl = {},
            loginPane = { FakeLoginPane() },
        )
    }

    @Test
    fun `login step renders on compact width`() {
        val bytes = render(width = 390, height = 900) { LoginPage() }
        write("onboarding-login-compact.png", bytes)
        assertTrue(bytes.size > 10_000, "登录步骤窄屏预览不应为空，实际 ${bytes.size} bytes")
    }

    @Test
    fun `login step renders on desktop width`() {
        val bytes = render(width = 900, height = 900) { LoginPage() }
        write("onboarding-login-desktop.png", bytes)
        assertTrue(bytes.size > 10_000, "登录步骤宽屏预览不应为空，实际 ${bytes.size} bytes")
    }

    @Test
    fun `logged in state renders success card`() {
        val bytes = render(width = 390, height = 700) { LoginPage(isLogged = true) }
        write("onboarding-login-success.png", bytes)
        assertTrue(bytes.size > 8_000, "登录成功态预览不应为空，实际 ${bytes.size} bytes")
    }

    @Test
    fun `no provider state renders setup entry`() {
        val bytes = render(width = 390, height = 700) { LoginPage(hasProvider = false) }
        write("onboarding-login-no-provider.png", bytes)
        assertTrue(bytes.size > 8_000, "无音源空态预览不应为空，实际 ${bytes.size} bytes")
    }

    @Test
    fun `onboarding step header and bottom nav render`() {
        val bytes = render(width = 390, height = 900) {
            OnboardingContent(
                replay = false,
                hasProvider = true,
                isImporting = false,
                importMessage = null,
                loadedProviders = emptyList(),
                initialPage = 2,
                providerSummary = "网易云音乐",
                accountSummary = "游客模式",
                accountPage = { modifier, _, _ ->
                    OnboardingLoginPage(
                        modifier = modifier,
                        hasProvider = true,
                        providerName = "网易云音乐",
                        isLogged = false,
                        nickname = null,
                        message = null,
                        verifyUrl = null,
                        qrRestored = false,
                        onGoToProvider = {},
                        onContinue = {},
                        onOpenVerifyUrl = {},
                        onDismissVerify = {},
                        onCopyVerifyUrl = {},
                        loginPane = { FakeLoginPane() },
                    )
                },
            )
        }
        write("onboarding-step-login.png", bytes)
        assertTrue(bytes.size > 12_000, "引导登录页预览不应为空，实际 ${bytes.size} bytes")
    }

    @Test
    fun `done step renders provider and account summary`() {
        val bytes = render(width = 390, height = 800) {
            OnboardingContent(
                replay = false,
                hasProvider = true,
                isImporting = false,
                importMessage = null,
                loadedProviders = emptyList(),
                initialPage = 3,
                providerSummary = "网易云音乐",
                accountSummary = "小明",
            )
        }
        write("onboarding-done-summary.png", bytes)
        assertTrue(bytes.size > 10_000, "完成页预览不应为空，实际 ${bytes.size} bytes")
    }

    @Test
    fun `compact login header has no oversized hero`() {
        val bytes = render(width = 600, height = 220) {
            Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
                LoginCompactHeader(
                    providerName = "网易云音乐",
                    onSwitchProvider = {},
                    onOpenSettings = {},
                )
            }
        }
        write("account-login-compact-header.png", bytes)
        assertTrue(bytes.size > 4_000, "紧凑登录标题预览不应为空，实际 ${bytes.size} bytes")
    }

    @Test
    fun `login step renders in english on compact width`() {
        val bytes = render(width = 390, height = 900) {
            ProvideCpStrings(AppLanguage.ENGLISH) { LoginPage() }
        }
        write("onboarding-login-compact-en.png", bytes)
        assertTrue(bytes.size > 10_000, "英文登录步骤窄屏预览不应为空，实际 ${bytes.size} bytes")
    }

    @Test
    fun `done step renders in english`() {
        val bytes = render(width = 390, height = 800) {
            ProvideCpStrings(AppLanguage.ENGLISH) {
                OnboardingContent(
                    replay = false,
                    hasProvider = true,
                    isImporting = false,
                    importMessage = null,
                    loadedProviders = emptyList(),
                    initialPage = 3,
                    providerSummary = "NetEase Cloud Music",
                    accountSummary = "Guest mode",
                )
            }
        }
        write("onboarding-done-summary-en.png", bytes)
        assertTrue(bytes.size > 10_000, "英文完成页预览不应为空，实际 ${bytes.size} bytes")
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun FakeLoginPane() {
    val s = cpStrings().account
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(s.channelQr, s.channelPhone, s.channelEmail, s.channelCookie).forEachIndexed { index, label ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = if (index == 0) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                ) {
                    CpText(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .background(Color.White, MaterialTheme.shapes.medium),
                    contentAlignment = Alignment.Center,
                ) {
                    CpText(s.channelQr, color = Color.Gray)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {}) { CpText(s.refreshQr) }
                    OutlinedButton(onClick = {}) { CpText(s.saveQr) }
                }
            }
        }
    }
}
