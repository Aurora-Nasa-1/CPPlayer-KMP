package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.theme.cpCardSurface

/**
 * 引导第 3 步：登录。
 *
 * 这里只负责「引导上下文」：步骤标题、权益说明、无音源空态、登录成功态，以及
 * 游客继续入口。真正的通道切换与登录表单由 [loginPane] 注入，账号页与引导页共用
 * 同一份实现，避免两套登录逻辑漂移。
 *
 * 刻意不自动翻页：扫码成功的瞬间用户通常还在看手机，自动跳走会让「登录成功了
 * 没有」变成一个需要回头确认的问题。成功态停在原地，由用户点「继续」。
 */
@Composable
internal fun OnboardingLoginPage(
    modifier: Modifier,
    hasProvider: Boolean,
    providerName: String?,
    isLogged: Boolean,
    nickname: String?,
    message: String?,
    verifyUrl: String?,
    qrRestored: Boolean,
    onGoToProvider: () -> Unit,
    onContinue: () -> Unit,
    onOpenVerifyUrl: () -> Unit,
    onDismissVerify: () -> Unit,
    onCopyVerifyUrl: () -> Unit,
    loginPane: @Composable () -> Unit,
) {
    val s = cpStrings()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CpText(
                text = s.onboarding.loginStepLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            CpText(
                text = providerName?.let { s.account.loginAs(it) } ?: s.onboarding.loginTitle,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            Spacer(Modifier.height(8.dp))
            CpText(
                text = s.onboarding.loginBenefits,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = Int.MAX_VALUE,
            )
            Spacer(Modifier.height(16.dp))

            when {
                !hasProvider -> NoProviderLoginCard(
                    onGoToProvider = onGoToProvider,
                    onContinue = onContinue,
                )
                isLogged -> LoginSuccessCard(
                    nickname = nickname,
                    providerName = providerName,
                    onContinue = onContinue,
                )
                else -> {
                    message?.let {
                        SettingsNote(it, color = MaterialTheme.colorScheme.primary)
                    }
                    verifyUrl?.let { url ->
                        SecurityVerificationCard(
                            verifyUrl = url,
                            onOpenWebview = onOpenVerifyUrl,
                            onDismiss = onDismissVerify,
                            onCopyUrl = onCopyVerifyUrl,
                        )
                    }
                    if (qrRestored) {
                        SettingsNote(
                            text = cpStrings().account.qrRestored,
                            emphasis = cp.player.app.ui.component.SettingsNoteEmphasis.WARNING,
                        )
                    }

                    loginPane()

                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onContinue) {
                        CpText(
                            text = s.onboarding.loginLater,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    CpText(
                        text = s.onboarding.loginLaterNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun NoProviderLoginCard(
    onGoToProvider: () -> Unit,
    onContinue: () -> Unit,
) {
    val s = cpStrings()
    Surface(
        shape = MaterialTheme.shapes.large,
        color = cpCardSurface(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.CloudQueue,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            CpText(
                text = s.onboarding.noProviderTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            CpText(
                text = s.onboarding.noProviderBody,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = Int.MAX_VALUE,
            )
            Button(
                onClick = onGoToProvider,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().height(44.dp),
            ) {
                CpText(s.onboarding.goConfigureProvider)
            }
            TextButton(onClick = onContinue) {
                CpText(
                    text = s.onboarding.guestContinue,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LoginSuccessCard(
    nickname: String?,
    providerName: String?,
    onContinue: () -> Unit,
) {
    val s = cpStrings()
    Surface(
        shape = MaterialTheme.shapes.large,
        color = cpCardSurface(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(64.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            CpText(
                text = nickname?.takeIf { it.isNotBlank() } ?: s.onboarding.loggedIn,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            CpText(
                text = s.onboarding.loginSuccess(providerName ?: s.account.currentProvider),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onContinue,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                CpText(s.onboarding.continueLabel)
            }
        }
    }
}
