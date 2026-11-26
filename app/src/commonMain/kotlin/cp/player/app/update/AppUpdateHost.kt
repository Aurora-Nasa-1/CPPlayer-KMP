package cp.player.app.update

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import cp.player.app.ui.component.CpAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cp.player.app.AppModel
import cp.player.app.platform.downloadAndInstallUpdate
import cp.player.app.platform.openUrl
import cp.player.app.ui.component.AppUpdateDialog
import cp.player.app.ui.component.CpText
import cp.player.core.BackendResult
import cp.player.core.provider.LegacyProviderModule
import cp.player.core.util.defaultSettingsStorage
import kotlinx.coroutines.launch

/**
 * Checks once per application session and owns the update dialog.
 *
 * Keeping this above the Navigator means the prompt also appears on the first
 * screen (including onboarding), rather than only after the user opens About.
 */
@Composable
fun AppUpdateHost() {
    val scope = rememberCoroutineScope()
    val noticeStorage = remember { defaultSettingsStorage("app_update_notices") }
    val updateSettings = remember { defaultSettingsStorage("app_update_settings") }
    var updateResult by remember { mutableStateOf<AppUpdateChecker.UpdateResult?>(null) }
    var announcement by remember { mutableStateOf<AppUpdateChecker.RemoteAnnouncement?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var showAnnouncement by remember { mutableStateOf(false) }
    var showDestructiveConfirm by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var legacyModules by remember { mutableStateOf<List<LegacyProviderModule>>(emptyList()) }
    var pendingAsset by remember { mutableStateOf<AppUpdateChecker.UpdateAsset?>(null) }
    var operationError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val status = AppUpdateChecker.checkStatus()
        updateResult = status.update
        announcement = status.announcement?.takeIf {
            noticeStorage.getString("seen_announcement_id") != it.id
        }
        // Kazumi: 自动检查受「自动更新」开关控制；手动检查不经过这里。
        val autoUpdateEnabled = updateSettings.getString("auto_update_enabled", "true") != "false"
        showDialog = status.update != null && autoUpdateEnabled
        showAnnouncement = status.update == null && announcement != null
    }

    val result = updateResult
    fun markAnnouncementSeen() {
        announcement?.let { noticeStorage.putString("seen_announcement_id", it.id) }
        showAnnouncement = false
    }
    fun startDownload(
        url: String?,
        fileName: String?,
        sha256: String?,
        size: Long?,
        fallbackUrl: String?,
        beforeInstall: suspend () -> Boolean = { true },
    ) {
        if (url == null) {
            fallbackUrl?.let(::openUrl)
            return
        }
        if (downloading) return
        downloading = true
        operationError = null
        scope.launch {
            val completed = downloadAndInstallUpdate(
                url = url,
                fileName = fileName ?: "CPPlayer-update",
                expectedSha256 = sha256,
                expectedSize = size,
                beforeInstall = beforeInstall,
            )
            downloading = false
            if (completed) {
                markAnnouncementSeen()
                showDialog = false
            } else {
                operationError = "下载、校验、清理排队或启动安装器失败；更新包保留在下载目录，请检查系统授权后重试。"
            }
        }
    }

    if (showDialog && result != null) {
        fun requestDownload(asset: AppUpdateChecker.UpdateAsset?) {
            if (result.destructivePolicy != null) {
                pendingAsset = asset
                legacyModules = if (result.destructivePolicy.removeLegacyProviders) {
                    AppModel.legacyProviderModulesToRemove()
                } else emptyList()
                showDestructiveConfirm = true
            } else {
                startDownload(
                    asset?.downloadUrl ?: result.downloadUrl,
                    asset?.name ?: result.assetName ?: "CPPlayer-${result.versionName}",
                    asset?.sha256 ?: result.assetSha256,
                    asset?.size ?: result.assetSize,
                    result.releaseUrl,
                )
            }
        }

        AppUpdateDialog(
            result = result,
            onDismiss = {
                if (!downloading) {
                    markAnnouncementSeen()
                    showDialog = false
                }
            },
            onDownload = { requestDownload(null) },
            onDownloadAsset = { requestDownload(it) },
            isAutoCheck = true,
            onDisableAutoUpdate = {
                updateSettings.putString("auto_update_enabled", "false")
                markAnnouncementSeen()
                showDialog = false
            },
        )
    }

    announcement?.takeIf { showAnnouncement }?.let { notice ->
        CpAlertDialog(
            onDismissRequest = ::markAnnouncementSeen,
            title = { CpText(notice.title) },
            text = {
                Column {
                    // 公告正文是多段文字，默认单行只会显示第一行。
                    CpText(notice.message, maxLines = Int.MAX_VALUE)
                    notice.url?.let { url ->
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = { openUrl(url) }) { CpText("查看公告详情") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = ::markAnnouncementSeen) { CpText("知道了") }
            },
        )
    }

    if (showDestructiveConfirm && result?.destructivePolicy != null) {
        val policy = result.destructivePolicy
        CpAlertDialog(
            onDismissRequest = { if (!downloading) showDestructiveConfirm = false },
            title = { CpText(policy.title) },
            text = {
                Column {
                    CpText(policy.message, maxLines = Int.MAX_VALUE)
                    Spacer(Modifier.height(10.dp))
                    if (legacyModules.isEmpty()) {
                        CpText(
                            "未发现符合条件的旧版 Provider V1/V3 模块目录，不会删除 SDK Provider 或账号数据。",
                            maxLines = Int.MAX_VALUE,
                        )
                    } else {
                        // 这是「到底会删哪些目录」的说明，用户必须读全才敢确认。
                        CpText(
                            "以下旧模块目录将在确认后排队，并仅在目标更新版本成功启动后删除；如果安装被取消或失败，旧版本会保留这些目录：",
                            maxLines = Int.MAX_VALUE,
                        )
                        Spacer(Modifier.height(6.dp))
                        legacyModules.forEach { module ->
                            CpText(
                                "${module.name} · API V${module.apiVersion} · ${module.id}",
                                maxLines = Int.MAX_VALUE,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !downloading,
                    onClick = {
                        showDestructiveConfirm = false
                        val policyForInstall = policy
                        val idsForInstall = legacyModules.map { it.id }.toSet()
                        startDownload(
                            pendingAsset?.downloadUrl ?: result.downloadUrl,
                            pendingAsset?.name ?: result.assetName ?: "CPPlayer-${result.versionName}",
                            pendingAsset?.sha256 ?: result.assetSha256,
                            pendingAsset?.size ?: result.assetSize,
                            result.releaseUrl,
                            beforeInstall = {
                                if (!policyForInstall.removeLegacyProviders || idsForInstall.isEmpty()) true
                                else AppModel.scheduleLegacyProviderCleanup(idsForInstall, result.versionName) is BackendResult.Success
                            },
                        )
                    },
                ) { CpText("确认并下载") }
            },
            dismissButton = {
                TextButton(onClick = { showDestructiveConfirm = false }) { CpText("取消") }
            },
        )
    }

    operationError?.let { error ->
        CpAlertDialog(
            onDismissRequest = { operationError = null },
            title = { CpText("更新未能启动") },
            // 失败原因是排查唯一线索（可能带命令行输出），不能截断。
            text = { CpText(error, maxLines = Int.MAX_VALUE) },
            confirmButton = { TextButton(onClick = { operationError = null }) { CpText("知道了") } },
        )
    }
}
