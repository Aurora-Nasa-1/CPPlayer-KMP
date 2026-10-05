package cp.player.app.ui.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.i18n.AppLanguage
import cp.player.app.i18n.cpStrings
import cp.player.app.i18n.displayName
import cp.player.app.i18n.displayNote
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.util.popOrNotify

/**
 * 语言设置页：跟随系统 / 简体中文 / English 三选一。
 *
 * ### 选项列表由 [AppLanguage.entries] 驱动
 *
 * 不在本页面写死三个分支：加一种语言只需要在 `AppLanguage` 里加一个成员 +
 * 两条文案，这边的行数与 `total` 都会自动跟着走 —— 少一个要同步的地方，
 * 就少一处「加了语言但列表里没有」的选择题。
 *
 * ### 为什么选完立刻返回
 *
 * 写盘 -> `AppModel.appLanguageFlow` 翻转 -> `ProvideCpStrings` 换掉 CompositionLocal
 * -> 整棵树重组，**本页自己的标题也在这棵树里**，所以选完 English 的瞬间这一页标题
 * 就变成 "Language"。留在页面上会让人以为是随机跳变；返回设置列表则能看到整片换语言，
 * 反馈明确。
 */
class LanguageSettingsScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val current by AppModel.appLanguageFlow.collectAsState()
        val strings = cpStrings()
        val options = AppLanguage.entries

        CpRouteScaffold(
            title = strings.language.screenTitle,
            onBack = { navigator.popOrNotify() },
        ) { pageModifier ->
            SettingsPage(pageModifier) {
                SettingsSection(strings.language.screenTitle) {
                    options.forEachIndexed { index, language ->
                        val selected = language == current
                        SettingsClickItem(
                            title = language.displayName(strings),
                            subtitle = language.displayNote(strings),
                            selected = selected,
                            trailingContent = {
                                if (selected) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = strings.language.current,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            },
                            index = index,
                            total = options.size,
                            onClick = {
                                AppModel.setAppLanguage(language)
                                navigator.popOrNotify()
                            },
                        )
                    }
                }
                SettingsNote(strings.language.applyNote)
            }
        }
    }
}
