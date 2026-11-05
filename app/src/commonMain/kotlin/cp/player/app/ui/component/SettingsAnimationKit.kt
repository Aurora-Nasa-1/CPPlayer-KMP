package cp.player.app.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cp.player.app.AppModel
import cp.player.app.ui.theme.ColorSource
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.theme.LocalIsDarkTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.app.ui.theme.displayName
import kotlin.math.cos
import kotlin.math.sin

/**
 * Android 16 (Material 3 Expressive) 原生风格设置动效卡片与模型工具包。
 *
 * 提供：
 * 1. [SettingsAnimationCard] - 原生设置顶栏插画展示卡片容器（圆角 28dp，双层纵深高光）
 * 2. [SettingsPhoneFrame] - 原生拟真手机机身与屏幕边框（中置打孔摄像头、状态栏微缩组件、底栏手势条）
 * 3. [DarkThemePhonePreviewCard] - 原生 Android 16「深色主题」动态预览卡片（日月弧线平移旋转、界面元素缩放过渡、纯黑 AMOLED 对比）
 * 4. [MotionDisplayPhonePreviewCard] - 视觉动效预览卡片（封面共享元素形变飞行、流体光晕弥散扩散）
 * 5. [AudioEnginePhonePreviewCard] - Hi-Res 发烧音频内核与硬件源码直通频谱动效卡片
 */

// ==============================================================================
// 1. 通用动效卡片容器 (SettingsAnimationCard)
// ==============================================================================

/**
 * 贴合 Android 16 的大圆角插画动效卡片容器。
 *
 * @param modifier 外部修饰符
 * @param title 顶部小标题（可选）
 * @param subtitle 顶部副标题说明（可选）
 * @param badgeText 右上角标签（如「实时动效」、「点击切换」）
 * @param containerColor 背景色（默认 surfaceContainerHigh）
 * @param onClick 点击事件（可选，用于交互式触发卡片内部动效）
 * @param content 卡片内部动效舞台
 */
@Composable
fun SettingsAnimationCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    badgeText: String? = null,
    badgeColor: Color = MaterialTheme.colorScheme.primaryContainer,
    badgeContentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = containerColor,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 14.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (title != null || badgeText != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        if (title != null) {
                            CpText(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (subtitle != null) {
                            CpText(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (badgeText != null) {
                        Surface(
                            shape = CircleShape,
                            color = badgeColor,
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(badgeContentColor, CircleShape),
                                )
                                CpText(
                                    text = badgeText,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = badgeContentColor,
                                )
                            }
                        }
                    }
                }
            }

            // 内部动效舞台
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp),
                contentAlignment = Alignment.Center,
                content = content,
            )
        }
    }
}

// ==============================================================================
// 2. 原生手机机身与屏幕边框 (SettingsPhoneFrame)
// ==============================================================================

/**
 * 具有拟真 Android 旗舰机身质感的缩放模型外框。
 *
 * 包含：
 * - 极窄边框与金属/磨砂质感外壳
 * - 中置微型打孔摄像头
 * - 微缩状态栏（时间、Wi-Fi 弧形波、蜂窝信号阶梯、电池电量胶囊）
 * - 底部原生手势条
 * - 柔和的外部投影
 */
@Composable
fun SettingsPhoneFrame(
    modifier: Modifier = Modifier,
    width: Dp = 142.dp,
    height: Dp = 224.dp,
    chassisColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    screenColor: Color = MaterialTheme.colorScheme.surface,
    isDarkContent: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val outlineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val statusContentColor = if (isDarkContent) {
        Color.White.copy(alpha = 0.85f)
    } else {
        Color.Black.copy(alpha = 0.8f)
    }

    // 手机整体外壳
    Surface(
        modifier = modifier
            .size(width = width, height = height)
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(26.dp),
                ambientColor = Color.Black.copy(alpha = 0.15f),
                spotColor = Color.Black.copy(alpha = 0.25f),
            ),
        shape = RoundedCornerShape(26.dp),
        color = chassisColor,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, outlineColor),
    ) {
        // 手机屏幕视口（带边距与内圆角）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(screenColor),
        ) {
            // 屏幕内容
            content()

            // 顶部微缩状态栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .padding(horizontal = 10.dp)
                    .align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // 时间
                CpText(
                    text = "09:41",
                    fontSize = 7.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = statusContentColor,
                    letterSpacing = 0.sp,
                )

                // 中置打孔摄像头
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(Color.Black.copy(alpha = 0.9f), CircleShape)
                        .border(0.5.dp, Color.White.copy(alpha = 0.15f), CircleShape),
                )

                // 信号与电量图标组
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    // Wi-Fi 简易符号
                    Canvas(modifier = Modifier.size(7.dp)) {
                        drawCircle(
                            color = statusContentColor,
                            radius = size.minDimension / 5f,
                            center = Offset(size.width / 2f, size.height * 0.8f),
                        )
                        drawArc(
                            color = statusContentColor,
                            startAngle = 200f,
                            sweepAngle = 140f,
                            useCenter = false,
                            style = Stroke(width = 1.2f, cap = StrokeCap.Round),
                        )
                    }

                    // 蜂窝信号 3 阶柱
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(1.dp),
                        modifier = Modifier.height(6.dp),
                    ) {
                        Box(Modifier.width(1.2.dp).height(2.5.dp).background(statusContentColor, RoundedCornerShape(0.5.dp)))
                        Box(Modifier.width(1.2.dp).height(4.2.dp).background(statusContentColor, RoundedCornerShape(0.5.dp)))
                        Box(Modifier.width(1.2.dp).height(6.dp).background(statusContentColor, RoundedCornerShape(0.5.dp)))
                    }

                    // 电池胶囊
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 1.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(9.dp)
                                .height(5.dp)
                                .border(0.8.dp, statusContentColor, RoundedCornerShape(1.5.dp))
                                .padding(0.8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.75f)
                                    .fillMaxSize()
                                    .background(statusContentColor, RoundedCornerShape(0.8.dp)),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(0.8.dp)
                                .height(2.5.dp)
                                .background(statusContentColor, RoundedCornerShape(topEnd = 0.5.dp, bottomEnd = 0.5.dp)),
                        )
                    }
                }
            }

            // 底部原生手势条
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
                    .width(36.dp)
                    .height(3.dp)
                    .background(statusContentColor.copy(alpha = 0.35f), CircleShape),
            )
        }
    }
}

// ==============================================================================
// 3. 原生 Android 16 深色主题动态预览卡片 (DarkThemePhonePreviewCard)
// ==============================================================================

/**
 * 贴合原生 Android 16 设置中「深色主题」的交互式手机动效卡片。
 *
 * 具有：
 * - 点击卡片可交互切换白天 / 黑夜预览态
 * - 日月弧线轨迹平移、缩放与自转过渡
 * - 手机屏幕内实时展示音乐播放器与设置卡片，跟随主题无缝渐变
 * - 支持 AMOLED 纯黑高对比度模式模拟
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DarkThemePhonePreviewCard(
    modifier: Modifier = Modifier,
    isDark: Boolean = LocalIsDarkTheme.current,
    pureBlack: Boolean = false,
    colorSource: ColorSource = ColorSource.FIXED,
    allowInteractiveToggle: Boolean = true,
) {
    // 交互预览状态：默认跟随系统/当前设置，点击卡片可就地切换动效
    var interactiveDark by remember(isDark) { mutableStateOf(isDark) }
    var interactivePureBlack by remember(pureBlack) { mutableStateOf(pureBlack) }

    val effectiveDark = if (allowInteractiveToggle) interactiveDark else isDark
    val effectivePureBlack = if (allowInteractiveToggle) interactivePureBlack else pureBlack

    // 核心平移动画：日月运行在圆弧轨迹上
    val celestialProgress by animateFloatAsState(
        targetValue = if (effectiveDark) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.78f,
            stiffness = 260f,
        ),
        label = "celestialProgress",
    )

    // 手机悬浮轻微上下起伏呼吸动效
    val infiniteTransition = rememberInfiniteTransition(label = "phoneFloat")
    val floatOffset by infiniteTransition.animateFloat(
        initialValue = -2.5f,
        targetValue = 2.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "floatOffset",
    )

    // 手机屏幕背景色过渡
    val screenBgColor by animateColorAsState(
        targetValue = when {
            effectiveDark && effectivePureBlack -> Color(0xFF000000)
            effectiveDark -> Color(0xFF14161C)
            else -> Color(0xFFF7F9FE)
        },
        animationSpec = tween(450, easing = FastOutSlowInEasing),
        label = "screenBg",
    )

    // 手机内卡片背景过渡
    val cardBgColor by animateColorAsState(
        targetValue = when {
            effectiveDark && effectivePureBlack -> Color(0xFF121418)
            effectiveDark -> Color(0xFF22262E)
            else -> Color(0xFFFFFFFF)
        },
        animationSpec = tween(450, easing = FastOutSlowInEasing),
        label = "cardBg",
    )

    // 手机内文字颜色过渡
    val primaryTextColor by animateColorAsState(
        targetValue = if (effectiveDark) Color(0xFFEDEDF2) else Color(0xFF181C22),
        animationSpec = tween(400),
        label = "primaryText",
    )
    val secondaryTextColor by animateColorAsState(
        targetValue = if (effectiveDark) Color(0xFF9EA3B0) else Color(0xFF6B7280),
        animationSpec = tween(400),
        label = "secondaryText",
    )

    // 强调色（跟随应用当前 Monet 种子色）
    val accentColor = MaterialTheme.colorScheme.primary
    val accentContainerColor = MaterialTheme.colorScheme.primaryContainer

    SettingsAnimationCard(
        modifier = modifier,
        title = "主题与视觉实时预览",
        subtitle = if (allowInteractiveToggle) "点击卡片可在浅色 / 深色 / 纯黑之间交互演示" else "贴合 Android 16 现代动态色彩系统",
        badgeText = if (effectiveDark) {
            if (effectivePureBlack) "AMOLED 纯黑" else "深色主题"
        } else {
            "浅色模式"
        },
        badgeColor = if (effectiveDark) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        badgeContentColor = if (effectiveDark) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
        },
        onClick = if (allowInteractiveToggle) {
            {
                if (!interactiveDark) {
                    // 白天 -> 暗色
                    interactiveDark = true
                    interactivePureBlack = false
                } else if (!interactivePureBlack) {
                    // 暗色 -> 纯黑
                    interactivePureBlack = true
                } else {
                    // 纯黑 -> 白天
                    interactiveDark = false
                    interactivePureBlack = false
                }
            }
        } else null,
    ) {
        // 卡片背景动态环境光晕
        val haloAlpha by animateFloatAsState(
            targetValue = if (effectiveDark) 0.18f else 0.28f,
            animationSpec = tween(400),
            label = "haloAlpha",
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = if (effectiveDark) {
                        listOf(accentColor.copy(alpha = haloAlpha), Color.Transparent)
                    } else {
                        listOf(Color(0xFFFFB74D).copy(alpha = haloAlpha), Color.Transparent)
                    },
                    center = Offset(size.width * 0.5f, size.height * 0.45f),
                    radius = size.width * 0.45f,
                ),
            )
        }

        // ========================
        // 浮动日月微缩天体动效
        // ========================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
        ) {
            // 太阳：金黄辐射、微光发散、白天显现、弧线平移至右上方
            val sunAlpha = (1f - celestialProgress).coerceIn(0f, 1f)
            val sunScale = 0.7f + (1f - celestialProgress) * 0.35f
            val sunOffsetX = (1f - celestialProgress) * 70.dp - 25.dp
            val sunOffsetY = celestialProgress * 30.dp - 10.dp

            if (sunAlpha > 0.02f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 28.dp, top = 20.dp)
                        .offset(x = sunOffsetX, y = sunOffsetY)
                        .graphicsLayer {
                            alpha = sunAlpha
                            scaleX = sunScale
                            scaleY = sunScale
                            rotationZ = (1f - celestialProgress) * 45f
                        },
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFFFB74D).copy(alpha = 0.25f),
                        modifier = Modifier.size(54.dp),
                    ) {}
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFFFA000),
                        modifier = Modifier
                            .size(34.dp)
                            .align(Alignment.Center),
                        shadowElevation = 4.dp,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.LightMode,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            // 月亮：银白冷光、夜空繁星点缀、暗夜平移进入
            val moonAlpha = celestialProgress.coerceIn(0f, 1f)
            val moonScale = 0.7f + celestialProgress * 0.35f
            val moonOffsetX = -(celestialProgress * 65.dp - 20.dp)
            val moonOffsetY = (1f - celestialProgress) * 30.dp - 10.dp

            if (moonAlpha > 0.02f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 28.dp, top = 20.dp)
                        .offset(x = moonOffsetX, y = moonOffsetY)
                        .graphicsLayer {
                            alpha = moonAlpha
                            scaleX = moonScale
                            scaleY = moonScale
                            rotationZ = celestialProgress * -30f
                        },
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        modifier = Modifier.size(52.dp),
                    ) {}
                    Surface(
                        shape = CircleShape,
                        color = if (effectivePureBlack) Color(0xFF1E293B) else MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(34.dp)
                            .align(Alignment.Center),
                        shadowElevation = 4.dp,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.DarkMode,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }

        // ========================
        // 核心手机模型与屏幕内容
        // ========================
        SettingsPhoneFrame(
            modifier = Modifier
                .offset(y = floatOffset.dp)
                .graphicsLayer {
                    // 轻微随日月位置发生透视微倾斜
                    rotationZ = (celestialProgress - 0.5f) * 2.2f
                },
            width = 148.dp,
            height = 216.dp,
            screenColor = screenBgColor,
            isDarkContent = effectiveDark,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 22.dp, bottom = 14.dp, start = 8.dp, end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 1. 微缩顶栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = primaryTextColor,
                            modifier = Modifier.size(10.dp),
                        )
                        CpText(
                            text = "CPPlayer",
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryTextColor,
                        )
                    }

                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = secondaryTextColor,
                        modifier = Modifier.size(10.dp),
                    )
                }

                // 2. 迷你播放器英雄卡片 (Hero Player Card)
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = cardBgColor,
                    shadowElevation = if (effectiveDark) 1.dp else 2.dp,
                    border = if (effectivePureBlack) {
                        androidx.compose.foundation.BorderStroke(0.6.dp, Color(0xFF262A32))
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // 迷你专辑封面
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            accentColor,
                                            MaterialTheme.colorScheme.tertiary,
                                        ),
                                    ),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MusicNote,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp),
                            )
                        }

                        // 歌曲信息与律动音频条
                        Column(modifier = Modifier.weight(1f)) {
                            CpText(
                                text = "星空流光 (Hi-Res)",
                                fontSize = 7.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = primaryTextColor,
                                maxLines = 1,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                modifier = Modifier.padding(top = 2.dp),
                            ) {
                                // 律动小柱子
                                val bounce1 by infiniteTransition.animateFloat(
                                    initialValue = 2f,
                                    targetValue = 6f,
                                    animationSpec = infiniteRepeatable(tween(400), RepeatMode.Reverse),
                                    label = "b1",
                                )
                                val bounce2 by infiniteTransition.animateFloat(
                                    initialValue = 5f,
                                    targetValue = 2f,
                                    animationSpec = infiniteRepeatable(tween(480), RepeatMode.Reverse),
                                    label = "b2",
                                )
                                val bounce3 by infiniteTransition.animateFloat(
                                    initialValue = 3f,
                                    targetValue = 7f,
                                    animationSpec = infiniteRepeatable(tween(360), RepeatMode.Reverse),
                                    label = "b3",
                                )

                                Box(Modifier.width(1.2.dp).height(bounce1.dp).background(accentColor, CircleShape))
                                Box(Modifier.width(1.2.dp).height(bounce2.dp).background(accentColor, CircleShape))
                                Box(Modifier.width(1.2.dp).height(bounce3.dp).background(accentColor, CircleShape))

                                CpText(
                                    text = "FLAC 96kHz",
                                    fontSize = 6.sp,
                                    color = secondaryTextColor,
                                    modifier = Modifier.padding(start = 2.dp),
                                )
                            }
                        }

                        // 播放按钮
                        Surface(
                            shape = CircleShape,
                            color = accentColor,
                            modifier = Modifier.size(16.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(10.dp),
                                )
                            }
                        }
                    }
                }

                // 3. 迷你设置分组卡片 1：深色模式切换行
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = cardBgColor,
                    border = if (effectivePureBlack) {
                        androidx.compose.foundation.BorderStroke(0.6.dp, Color(0xFF262A32))
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .background(
                                        if (effectiveDark) accentContainerColor else Color(0xFFE5E7EB),
                                        CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = if (effectiveDark) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                                    contentDescription = null,
                                    tint = if (effectiveDark) accentColor else Color(0xFF4B5563),
                                    modifier = Modifier.size(8.dp),
                                )
                            }
                            CpText(
                                text = "深色主题",
                                fontSize = 7.sp,
                                fontWeight = FontWeight.Medium,
                                color = primaryTextColor,
                            )
                        }

                        // 迷你微缩 Switch 开关
                        val switchThumbOffset by animateFloatAsState(
                            targetValue = if (effectiveDark) 9f else 0f,
                            animationSpec = spring(stiffness = 300f),
                            label = "switchThumb",
                        )
                        Box(
                            modifier = Modifier
                                .width(18.dp)
                                .height(9.dp)
                                .clip(CircleShape)
                                .background(if (effectiveDark) accentColor else Color(0xFFD1D5DB))
                                .padding(1.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .offset(x = switchThumbOffset.dp)
                                    .background(Color.White, CircleShape),
                            )
                        }
                    }
                }

                // 4. 迷你设置分组卡片 2：纯黑模式行
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = cardBgColor,
                    border = if (effectivePureBlack) {
                        androidx.compose.foundation.BorderStroke(0.6.dp, Color(0xFF262A32))
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .background(
                                        if (effectivePureBlack) Color(0xFF1E293B) else Color(0xFFE5E7EB),
                                        CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(if (effectivePureBlack) Color(0xFF60A5FA) else Color(0xFF9CA3AF), CircleShape),
                                )
                            }
                            CpText(
                                text = "纯黑底色 (AMOLED)",
                                fontSize = 7.sp,
                                fontWeight = FontWeight.Medium,
                                color = primaryTextColor,
                            )
                        }

                        // 迷你指示点
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    if (effectivePureBlack) Color(0xFF34D399) else Color(0xFF9CA3AF).copy(alpha = 0.5f),
                                    CircleShape,
                                ),
                        )
                    }
                }
            }
        }
    }
}

// ==============================================================================
// 4. 视觉动效与流体光晕预览卡片 (MotionDisplayPhonePreviewCard)
// ==============================================================================

/**
 * 原生动效与流体渐变背景的动态展示卡片。
 *
 * 演示：
 * - 封面从微缩条平滑飞入大播放器（平移与连续缩放）
 * - 弥散流体光晕的缓缓流淌与色彩变换
 */
@Composable
fun MotionDisplayPhonePreviewCard(
    modifier: Modifier = Modifier,
    flightActive: Boolean = true,
    fluidActive: Boolean = true,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "motionFlight")

    // 模拟连续的封面飞行与收缩
    val flightProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "flightProgress",
    )

    // 流体光晕旋转角度
    val fluidRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "fluidRot",
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary

    SettingsAnimationCard(
        modifier = modifier,
        title = "界面转场与流动光晕",
        subtitle = "流体动态背景弥散渲染 · 封面共享轴平滑飞行动画",
        badgeText = "Expressive 动效",
    ) {
        // 环境光效
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(primaryColor.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(size.width * 0.5f, size.height * 0.5f),
                    radius = size.width * 0.42f,
                ),
            )
        }

        SettingsPhoneFrame(
            width = 148.dp,
            height = 216.dp,
            screenColor = Color(0xFF13151A),
            isDarkContent = true,
        ) {
            // 流体光晕层
            if (fluidActive) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { rotationZ = fluidRotation },
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(primaryColor.copy(alpha = 0.35f), Color.Transparent),
                                center = Offset(size.width * 0.3f, size.height * 0.35f),
                                radius = size.width * 0.6f,
                            ),
                        )
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(tertiaryColor.copy(alpha = 0.28f), Color.Transparent),
                                center = Offset(size.width * 0.7f, size.height * 0.65f),
                                radius = size.width * 0.55f,
                            ),
                        )
                    }
                }
            }

            // 内部播放页结构
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 22.dp, bottom = 12.dp, start = 10.dp, end = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // 顶栏微缩
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(10.dp),
                    )
                    CpText(
                        text = "正在播放",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.9f),
                    )
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(10.dp),
                    )
                }

                // 核心：平滑飞行的封面！
                // 当 flightProgress 变化时，尺寸从 36dp 变化到 74dp，Y 轴平移
                val coverSize = if (flightActive) {
                    36.dp + (flightProgress * 38.dp)
                } else {
                    68.dp
                }
                val coverOffsetY = if (flightActive) {
                    (1f - flightProgress) * 26.dp
                } else {
                    0.dp
                }

                Box(
                    modifier = Modifier
                        .offset(y = coverOffsetY)
                        .size(coverSize)
                        .shadow(8.dp, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF6366F1),
                                    Color(0xFFA855F7),
                                    Color(0xFFEC4899),
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(coverSize * 0.42f),
                    )
                }

                // 曲目与波形进度条
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CpText(
                        text = "Aurora Flight",
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    CpText(
                        text = "Continuous Motion",
                        fontSize = 6.5.sp,
                        color = Color.White.copy(alpha = 0.65f),
                    )

                    // 进度条
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.15f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.55f)
                                .height(3.dp)
                                .clip(CircleShape)
                                .background(primaryColor),
                        )
                    }
                }
            }
        }
    }
}

// ==============================================================================
// 5. Hi-Res 发烧音频内核与硬件源码直通卡片 (AudioEnginePhonePreviewCard)
// ==============================================================================

/**
 * Hi-Res 发烧音频与 DAC 独占直通卡片。
 *
 * 包含：
 * - 8 频段跳动脉冲声学频谱动效
 * - Hi-Res 金标与 Bit-Perfect 源码输出徽标
 * - WASAPI / Oboe 硬件设备直通路径状态
 */
@Composable
fun AudioEnginePhonePreviewCard(
    modifier: Modifier = Modifier,
    isFlickEngine: Boolean = true,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "audioEq")

    // 8 根柱子的独立动效频率
    val h1 by infiniteTransition.animateFloat(2f, 16f, infiniteRepeatable(tween(260), RepeatMode.Reverse), "h1")
    val h2 by infiniteTransition.animateFloat(6f, 22f, infiniteRepeatable(tween(340), RepeatMode.Reverse), "h2")
    val h3 by infiniteTransition.animateFloat(12f, 28f, infiniteRepeatable(tween(220), RepeatMode.Reverse), "h3")
    val h4 by infiniteTransition.animateFloat(8f, 26f, infiniteRepeatable(tween(420), RepeatMode.Reverse), "h4")
    val h5 by infiniteTransition.animateFloat(14f, 24f, infiniteRepeatable(tween(300), RepeatMode.Reverse), "h5")
    val h6 by infiniteTransition.animateFloat(4f, 20f, infiniteRepeatable(tween(380), RepeatMode.Reverse), "h6")
    val h7 by infiniteTransition.animateFloat(9f, 25f, infiniteRepeatable(tween(290), RepeatMode.Reverse), "h7")
    val h8 by infiniteTransition.animateFloat(3f, 15f, infiniteRepeatable(tween(330), RepeatMode.Reverse), "h8")

    val heights = listOf(h1, h2, h3, h4, h5, h6, h7, h8)
    val goldColor = Color(0xFFFFD700)
    val goldContainer = Color(0xFF332900)

    SettingsAnimationCard(
        modifier = modifier,
        title = "Hi-Res 音频引擎与源码直通",
        subtitle = if (isFlickEngine) "硬件直通模式就绪 · 绕过系统音频混音器" else "标准音频引擎已启用",
        badgeText = if (isFlickEngine) "Bit-Perfect" else "标准引擎",
        badgeColor = if (isFlickEngine) goldContainer else MaterialTheme.colorScheme.surfaceVariant,
        badgeContentColor = if (isFlickEngine) goldColor else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        SettingsPhoneFrame(
            width = 148.dp,
            height = 216.dp,
            screenColor = Color(0xFF0F1115),
            isDarkContent = true,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 22.dp, bottom = 12.dp, start = 8.dp, end = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // 顶部 Hi-Res 标识与 DAC 信息
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = goldContainer,
                    border = androidx.compose.foundation.BorderStroke(0.6.dp, goldColor.copy(alpha = 0.6f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .background(goldColor, CircleShape),
                        )
                        CpText(
                            text = "Hi-Res AUDIO · 192kHz/24bit",
                            fontSize = 6.sp,
                            fontWeight = FontWeight.Bold,
                            color = goldColor,
                        )
                    }
                }

                // 中间：8 柱跳动脉冲频谱
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    heights.forEach { h ->
                        Box(
                            modifier = Modifier
                                .width(6.dp)
                                .height(h.dp)
                                .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            goldColor,
                                            Color(0xFFFF9100),
                                            MaterialTheme.colorScheme.primary,
                                        ),
                                    ),
                                ),
                        )
                    }
                }

                // 底部直通状态卡片
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF1B1E26),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            modifier = Modifier.size(16.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Tune,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(10.dp),
                                )
                            }
                        }
                        Column {
                            CpText(
                                text = if (isFlickEngine) "WASAPI Exclusive 直通" else "系统混音共享通道",
                                fontSize = 7.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                            )
                            CpText(
                                text = if (isFlickEngine) "Direct Hardware Stream" else "Standard Mix Channel",
                                fontSize = 5.5.sp,
                                color = Color.White.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
        }
    }
}
