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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
    var updateResult by remember { mutableStateOf<AppUpdateChecker.UpdateResult?>(null) }
    var announcement by remember { mutableStateOf<AppUpdateChecker.RemoteAnnouncement?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var showAnnouncement by remember { mutableStateOf(false) }
    var showDestructiveConfirm by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var legacyModules by remember { mutableStateOf<List<LegacyProviderModule>>(emptyList()) }
    var operationError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val status = AppUpdateChecker.checkStatus()
        updateResult = status.update
        announcement = status.announcement?.takeIf {
            noticeStorage.getString("seen_announcement_id") != it.id
        }
        showDialog = status.update != null
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
        AppUpdateDialog(
            result = result,
            onDismiss = {
                if (!downloading) {
                    markAnnouncementSeen()
                    showDialog = false
                }
            },
            onDownload = {
                if (result.destructivePolicy != null) {
                    legacyModules = if (result.destructivePolicy.removeLegacyProviders) {
                        AppModel.legacyProviderModulesToRemove()
                    } else emptyList()
                    showDestructiveConfirm = true
                } else {
                    startDownload(
                        result.downloadUrl,
                        result.assetName ?: "CPPlayer-${result.versionName}",
                        result.assetSha256,
                        result.assetSize,
                        result.releaseUrl,
                    )
                }
            },
        )
    }

    announcement?.takeIf { showAnnouncement }?.let { notice ->
        AlertDialog(
            onDismissRequest = ::markAnnouncementSeen,
            title = { CpText(notice.title) },
            text = {
                Column {
                    CpText(notice.message)
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
        AlertDialog(
            onDismissRequest = { if (!downloading) showDestructiveConfirm = false },
            title = { CpText(policy.title) },
            text = {
                Column {
                    CpText(policy.message)
                    Spacer(Modifier.height(10.dp))
                    if (legacyModules.isEmpty()) {
                        CpText("未发现符合条件的旧版 Provider V1/V3 模块目录，不会删除 SDK Provider 或账号数据。")
                    } else {
                        CpText("以下旧模块目录将在确认后排队，并仅在目标更新版本成功启动后删除；如果安装被取消或失败，旧版本会保留这些目录：")
                        Spacer(Modifier.height(6.dp))
                        legacyModules.forEach { module ->
                            CpText("${module.name} · API V${module.apiVersion} · ${module.id}")
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
                            result.downloadUrl,
                            result.assetName ?: "CPPlayer-${result.versionName}",
                            result.assetSha256,
                            result.assetSize,
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
        AlertDialog(
            onDismissRequest = { operationError = null },
            title = { CpText("更新未能启动") },
            text = { CpText(error) },
            confirmButton = { TextButton(onClick = { operationError = null }) { CpText("知道了") } },
        )
    }
}
