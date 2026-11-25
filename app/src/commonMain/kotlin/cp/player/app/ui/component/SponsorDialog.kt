package cp.player.app.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.ImageSaveResult
import cp.player.app.platform.saveImageToLocal
import cp.player.app.resources.Res
import cp.player.app.resources.sponsor_alipay
import cp.player.app.resources.sponsor_wechat
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

/**
 * 赞助支付类型枚举。
 */
enum class SponsorPaymentType {
    WECHAT,
    ALIPAY,
}

// 在白底收款码卡片与浅色容器上校准至满足 WCAG AA（>= 4.5:1）的高对比度品牌色阶
private val WeChatBrandColor = Color(0xFF057A3D)
private val AlipayBrandColor = Color(0xFF0C5AD6)
private val HeartBrandColor = Color(0xFFC2185B)

/**
 * 赞助与支持弹窗。
 *
 * 弹出展示微信支付与支付宝赞助收款码，支持扫码支持。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SponsorDialog(
    initialType: SponsorPaymentType = SponsorPaymentType.WECHAT,
    onDismiss: () -> Unit,
) {
    val isExpanded = LocalIsExpanded.current
    val s = cpStrings().about

    var selectedType by remember { mutableStateOf(initialType) }
    val scope = rememberCoroutineScope()

    CpAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .widthIn(min = 320.dp, max = if (isExpanded) 620.dp else 420.dp)
            .fillMaxWidth(if (isExpanded) 0.70f else 0.95f),
        icon = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.size(52.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = s.sponsorDialogTitle,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        },
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CpText(
                    text = s.sponsorDialogTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                CpText(
                    text = s.sponsorDialogSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // M3E 连通切换：微信支付 / 支付宝
                CpConnectedChoice(
                    selectedIndex = if (selectedType == SponsorPaymentType.WECHAT) 0 else 1,
                    labels = listOf(s.wechatPay, s.alipay),
                    onSelect = { idx ->
                        selectedType = if (idx == 0) SponsorPaymentType.WECHAT else SponsorPaymentType.ALIPAY
                    },
                )

                // 收款码展示卡片：二维码外层垫白色背景以保证任意主题下对比度清晰可扫
                AnimatedContent(
                    targetState = selectedType,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "SponsorQrSwitch",
                ) { targetType ->
                    var saveState by remember(targetType) {
                        mutableStateOf<SponsorQrSaveState>(SponsorQrSaveState.Idle)
                    }
                    val qrPainter = when (targetType) {
                        SponsorPaymentType.WECHAT -> painterResource(Res.drawable.sponsor_wechat)
                        SponsorPaymentType.ALIPAY -> painterResource(Res.drawable.sponsor_alipay)
                    }
                    val brandColor = when (targetType) {
                        SponsorPaymentType.WECHAT -> WeChatBrandColor
                        SponsorPaymentType.ALIPAY -> AlipayBrandColor
                    }
                    val brandName = when (targetType) {
                        SponsorPaymentType.WECHAT -> s.wechatPay
                        SponsorPaymentType.ALIPAY -> s.alipay
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Surface(
                            modifier = Modifier
                                .size(if (isExpanded) 280.dp else 240.dp)
                                .clip(MaterialTheme.shapes.large)
                                .border(
                                    width = 1.dp,
                                    color = brandColor.copy(alpha = 0.35f),
                                    shape = MaterialTheme.shapes.large,
                                ),
                            shape = MaterialTheme.shapes.large,
                            color = Color.White,
                            shadowElevation = 2.dp,
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    painter = qrPainter,
                                    contentDescription = brandName,
                                    modifier = Modifier.fillMaxWidth(),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                        }

                        CpText(
                            text = s.sponsorAccount,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        OutlinedButton(
                            enabled = saveState != SponsorQrSaveState.Saving,
                            onClick = {
                                saveState = SponsorQrSaveState.Saving
                                scope.launch {
                                    val bytes = runCatching {
                                        when (targetType) {
                                            SponsorPaymentType.WECHAT ->
                                                Res.readBytes("drawable/sponsor_wechat.png")
                                            SponsorPaymentType.ALIPAY ->
                                                Res.readBytes("drawable/sponsor_alipay.jpg")
                                        }
                                    }.getOrNull()
                                    val result = if (bytes == null || bytes.isEmpty()) {
                                        ImageSaveResult.Failed
                                    } else {
                                        saveImageToLocal(
                                            imageBytes = bytes,
                                            fileName = when (targetType) {
                                                SponsorPaymentType.WECHAT -> "CPPlayer-sponsor-wechat"
                                                SponsorPaymentType.ALIPAY -> "CPPlayer-sponsor-alipay"
                                            },
                                            extension = when (targetType) {
                                                SponsorPaymentType.WECHAT -> "png"
                                                SponsorPaymentType.ALIPAY -> "jpg"
                                            },
                                            dialogTitle = s.saveQrCode,
                                        )
                                    }
                                    saveState = when (result) {
                                        is ImageSaveResult.Saved ->
                                            SponsorQrSaveState.Saved(result.location)
                                        ImageSaveResult.Cancelled -> SponsorQrSaveState.Idle
                                        ImageSaveResult.Failed -> SponsorQrSaveState.Failed
                                    }
                                }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            CpText(
                                text = if (saveState == SponsorQrSaveState.Saving) {
                                    s.savingQrCode
                                } else {
                                    s.saveQrCode
                                },
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }

                        when (val state = saveState) {
                            is SponsorQrSaveState.Saved -> CpText(
                                text = state.location?.let(s::qrSavedTo) ?: s.qrSavedToGallery,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            SponsorQrSaveState.Failed -> CpText(
                                text = s.qrSaveFailed,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            else -> Unit
                        }
                    }
                }

                CpText(
                    text = s.sponsorThanks,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                CpText(s.close)
            }
        },
    )
}

/**
 * 「关于」页面内嵌的赞助卡片组件：提供直观的二维码缩略图与点击全屏放大入口。
 */
@Composable
fun SponsorInlineCard(
    onOpenDialog: (SponsorPaymentType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = cpStrings().about

    // 页面上的内联卡，走卡片层（深浅两套层级是反的，见 `cpCardSurface` 的层级表）。
    val cardBg = cp.player.app.ui.theme.cpCardSurface()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = cardBg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    CpText(
                        text = s.sponsorDeveloper,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    CpText(
                        text = s.sponsorAccount,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 微信与支付宝收款码缩略卡片
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SponsorThumbnailItem(
                    title = s.wechatPay,
                    brandColor = WeChatBrandColor,
                    painter = painterResource(Res.drawable.sponsor_wechat),
                    onClick = { onOpenDialog(SponsorPaymentType.WECHAT) },
                    modifier = Modifier.weight(1f),
                )

                SponsorThumbnailItem(
                    title = s.alipay,
                    brandColor = AlipayBrandColor,
                    painter = painterResource(Res.drawable.sponsor_alipay),
                    onClick = { onOpenDialog(SponsorPaymentType.ALIPAY) },
                    modifier = Modifier.weight(1f),
                )
            }

            CpText(
                text = s.sponsorThanks,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SponsorThumbnailItem(
    title: String,
    brandColor: Color,
    painter: androidx.compose.ui.graphics.painter.Painter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .border(
                width = 1.dp,
                color = brandColor.copy(alpha = 0.28f),
                shape = MaterialTheme.shapes.medium,
            ),
        shape = MaterialTheme.shapes.medium,
        color = Color.White,
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.95f),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painter,
                    contentDescription = title,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.ZoomIn,
                    contentDescription = null,
                    tint = brandColor,
                    modifier = Modifier.size(14.dp),
                )
                CpText(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = brandColor,
                )
            }
        }
    }
}

private sealed interface SponsorQrSaveState {
    data object Idle : SponsorQrSaveState
    data object Saving : SponsorQrSaveState
    data class Saved(val location: String?) : SponsorQrSaveState
    data object Failed : SponsorQrSaveState
}
