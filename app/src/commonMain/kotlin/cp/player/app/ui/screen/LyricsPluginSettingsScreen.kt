package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.rememberZipPicker
import cp.player.app.platform.sendPlatformToast
import cp.player.app.ui.component.CpConfirmHost
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.SettingsButtonItem
import cp.player.app.ui.component.SettingsLazyPage
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsSwitchItem
import cp.player.app.ui.component.rememberConfirmState
import cp.player.app.ui.util.popOrNotify
import cp.player.core.lyrics.BuiltinLyricsSourceIds
import cp.player.core.lyrics.LyricsCapability
import cp.player.core.lyrics.LyricsSourceEntry
import cp.player.core.lyrics.LyricsSourceRegistry
import kotlinx.coroutines.launch

/**
 * 歌词来源页（统一来源体系）。
 *
 * ### 它取代了什么
 * 改造前歌词来源由**两个互不相干的控件**表达：`播放设置 → 歌词来源`（三档枚举）
 * 与 `账号与音源 → 歌词源插件`（各插件独立开关）。两者无法组合 ——
 * 用户想「AMLL 优先 → 插件 X → 音源」是表达不出来的，插件顺序还由目录名字典序决定。
 *
 * 现在这里是一张**有序列表**：顺序即优先级，首个命中即胜出。
 * 内置来源（边车 / AMLL / 音源）与第三方插件同在一张表里，只差一个「内置」徽章。
 *
 * ### 交互约定
 * - 开关即时生效并落盘；
 * - 排序用上移 / 下移按钮（**不用拖拽**：触屏拖拽误触率高，且桌面/移动要两套实现，
 *   而按钮在两个平台上行为完全一致、可键盘操作、对无障碍更友好）；
 * - **排序 / 删除按钮画在这一行卡片内部**（开关左侧），而不是在卡片外另铺三行 ——
 *   否则每条来源横跨四行，「哪些按钮属于哪条来源」全靠猜，列表一长就糊了；
 * - 删除是破坏性操作，走 `CpConfirmHost` 二次确认；
 * - 内置来源不可删除（它们是随宿主分发的第一方来源）。
 */
class LyricsPluginSettingsScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val s = cpStrings()
        val t = s.lyricsPlugin
        val registry: LyricsSourceRegistry = AppModel.lyricsSources
        val scope = rememberCoroutineScope()
        val confirm = rememberConfirmState()

        var sources by remember { mutableStateOf<List<LyricsSourceEntry>>(emptyList()) }

        val refresh: suspend () -> Unit = {
            sources = runCatching { registry.allSources() }.getOrDefault(emptyList())
        }

        LaunchedEffect(Unit) { refresh() }

        val pickZip = rememberZipPicker(onPicked = { zipPath ->
            if (zipPath != null) {
                scope.launch {
                    val message = runCatching { AppModel.backend.lyricsPlugins.importPluginZip(zipPath) }
                        .fold(
                            onSuccess = { ids ->
                                registry.reload()
                                if (ids.isEmpty()) t.importFailed else t.importSuccessCount(ids.size)
                            },
                            onFailure = { t.importFailed },
                        )
                    sendPlatformToast(message)
                    refresh()
                }
            }
        })

        /** 交换相邻两项并落盘。列表顺序就是**全部**来源的顺序（含停用项）。 */
        fun move(id: String, delta: Int) {
            val ids = sources.map { it.id }.toMutableList()
            val from = ids.indexOf(id)
            val to = from + delta
            if (from < 0 || to !in ids.indices) return
            ids[from] = ids[to].also { ids[to] = ids[from] }
            scope.launch {
                registry.setOrder(ids)
                refresh()
            }
        }

        CpRouteScaffold(
            title = t.screenTitle,
            onBack = { navigator.popOrNotify() },
        ) { pageModifier ->
            SettingsLazyPage(pageModifier) {
                item { SettingsNote(t.note) }
                item { SettingsNote(t.orderNote) }

                if (sources.isEmpty()) {
                    item { SettingsNote(t.emptyMessage) }
                } else {
                    sources.forEachIndexed { index, source ->
                        item(key = "src-${source.id}") {
                            SettingsSwitchItem(
                                title = sourceTitle(source, t),
                                subtitle = sourceSubtitle(source, t),
                                checked = source.enabled,
                                onCheckedChange = { checked ->
                                    scope.launch {
                                        registry.setEnabled(source.id, checked)
                                        // 内置来源还要同步插件服务自己的开关（它管 JS 是否加载）。
                                        if (!source.bundled) {
                                            runCatching {
                                                AppModel.backend.lyricsPlugins.setEnabled(source.id, checked)
                                            }
                                        }
                                        refresh()
                                    }
                                },
                                index = index,
                                total = sources.size,
                                icon = Icons.Filled.Extension,
                                // 排序 / 删除按钮收进卡片本身，而不是在卡片外再铺三行 ——
                                // 那样每条来源会横跨四行，「哪些按钮属于哪条来源」全靠猜，
                                // 列表一长就彻底糊了。
                                leadingTrailingContent = {
                                    LyricsSourceRowActions(
                                        source = source,
                                        isFirst = index == 0,
                                        isLast = index == sources.lastIndex,
                                        t = t,
                                        onMoveUp = { move(source.id, -1) },
                                        onMoveDown = { move(source.id, +1) },
                                        onDelete = {
                                            confirm.request(
                                                title = t.deleteConfirmTitle,
                                                message = t.deleteConfirmMessage(source.name),
                                                onConfirm = {
                                                    scope.launch {
                                                        runCatching {
                                                            AppModel.backend.lyricsPlugins.deletePlugin(source.id)
                                                        }
                                                        registry.reload()
                                                        refresh()
                                                    }
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        }
                    }
                }

                item {
                    SettingsButtonItem(
                        text = t.importAction,
                        onClick = { pickZip() },
                        index = 0,
                        total = 1,
                        icon = Icons.Filled.FileUpload,
                    )
                }
            }

            CpConfirmHost(confirm)
        }
    }
}

/**
 * 一条来源的排序 / 删除按钮，**画在卡片内部**（开关左侧）。
 *
 * ### 为什么是图标按钮而不是整行文字按钮
 * 放进卡片后空间只剩按钮那一小条：三个文字按钮（「上移」「下移」「删除插件」）在这里
 * 放不下，英文下尤其（`Move up` / `Move down` / `Delete plugin`）。
 * 图标按钮是紧凑版，**描述靠 `contentDescription` 给读屏**，功能不打折。
 *
 * ### 为什么到顶/到底要禁用而不是隐藏
 * 隐藏会让按钮位置随行而变（首行的「上移」消失后，「下移」会滑到原来「上移」的位置），
 * 用户按肌肉记忆点下去就点错了。禁用则位置固定，还能看出「这里到头了」。
 *
 * ⚠️ 刻意**不做拖拽**：拖拽在触屏上误触率高、且桌面与移动需要两套实现，
 * 而上下移动按钮在两个平台行为一致、可键盘操作、无障碍友好。列表长度通常 < 10，够用。
 */
@Composable
private fun LyricsSourceRowActions(
    source: LyricsSourceEntry,
    isFirst: Boolean,
    isLast: Boolean,
    t: cp.player.app.i18n.LyricsPluginStrings,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    // 按钮本身没有文字，靠这三个 contentDescription 让读屏念出「上移 AMLL 官方词库」——
    // 只念「上移」的话，一页里每个来源都有同一个按钮，读屏用户分不清按的是哪一条。
    val label = { action: String -> "$action ${sourceTitle(source, t)}" }
    Row(verticalAlignment = Alignment.CenterVertically) {
        SettingsRowIconButton(
            icon = Icons.Filled.KeyboardArrowUp,
            contentDescription = label(t.moveUp),
            enabled = !isFirst,
            tint = tint,
            onClick = onMoveUp,
        )
        SettingsRowIconButton(
            icon = Icons.Filled.KeyboardArrowDown,
            contentDescription = label(t.moveDown),
            enabled = !isLast,
            tint = tint,
            onClick = onMoveDown,
        )
        if (source.removable) {
            SettingsRowIconButton(
                icon = Icons.Filled.DeleteOutline,
                contentDescription = label(t.deleteAction),
                enabled = true,
                tint = MaterialTheme.colorScheme.error,
                onClick = onDelete,
            )
        }
    }
}

/**
 * 行内小图标按钮：40dp 圆形底座，与歌曲列表 `SongItem` 的行尾「更多」同款。
 * 三枚并排放进一行，因此比 `IconButton` 默认的 48dp 略收 —— 但**保持 40dp 不减**，
 * 那已经是触屏可点区域的下限。
 */
@Composable
private fun SettingsRowIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    tint: Color,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 标题：内置来源查文案层（`localizeName`），第三方用作者给的名字。 */
private fun sourceTitle(
    source: LyricsSourceEntry,
    t: cp.player.app.i18n.LyricsPluginStrings,
): String = if (!source.localizeName) {
    source.name
} else {
    when (source.id) {
        BuiltinLyricsSourceIds.SIDECAR -> t.builtinSidecar
        BuiltinLyricsSourceIds.AMLL -> t.builtinAmll
        BuiltinLyricsSourceIds.PROVIDER -> t.builtinProvider
        else -> source.name
    }
}

/** `作者 · 版本 · 能力`（内置来源改用固定的说明句）。 */
private fun sourceSubtitle(
    source: LyricsSourceEntry,
    t: cp.player.app.i18n.LyricsPluginStrings,
): String {
    val capabilities = source.capabilities.mapNotNull { capability ->
        when (capability) {
            LyricsCapability.SEARCH_SONGS -> t.capabilitySearchSongs
            LyricsCapability.GET_LYRICS -> t.capabilityGetLyrics
            LyricsCapability.SEARCH_COVERS -> t.capabilitySearchCovers
            LyricsCapability.LOOKUP_BY_ID -> t.capabilityLookupById
        }
    }.joinToString(" / ")

    // 内置来源的「能力标签」没有信息量（用户不关心边车算不算搜索歌曲），
    // 换成一句**它在哪儿取词**的说明 —— 那才是用户拖着排序时需要的判据。
    val parts = if (source.bundled) {
        listOfNotNull(
            t.bundledBadge.takeIf { it.isNotBlank() },
            builtinNote(source, t)?.takeIf { it.isNotBlank() },
        )
    } else {
        listOfNotNull(
            listOfNotNull(
                source.author.takeIf { it.isNotBlank() },
                source.version.takeIf { it.isNotBlank() }?.let { "v$it" },
            ).joinToString(" · ").takeIf { it.isNotBlank() },
            capabilities.takeIf { it.isNotBlank() },
        )
    }
    val body = parts.joinToString(" — ")
    // 停用是**行为**差异，必须显眼：用户拖着排序时最容易忽略的就是自己关掉的那条。
    return if (source.enabled) body else "$body · ${t.disabledHint}"
}

private fun builtinNote(
    source: LyricsSourceEntry,
    t: cp.player.app.i18n.LyricsPluginStrings,
): String? = when (source.id) {
    BuiltinLyricsSourceIds.SIDECAR -> t.builtinSidecarNote
    BuiltinLyricsSourceIds.AMLL -> t.builtinAmllNote
    BuiltinLyricsSourceIds.PROVIDER -> t.builtinProviderNote
    else -> null
}
