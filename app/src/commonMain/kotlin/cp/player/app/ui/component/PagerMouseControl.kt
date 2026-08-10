package cp.player.app.ui.component

import androidx.compose.ui.Modifier

/**
 * 桌面端鼠标操作支持：垂直滚轮/Shift+滚轮 翻页，按住鼠标中键或右键左右拖拽翻页。
 *
 * HorizontalPager 默认只响应水平方向的滚轮（多数鼠标没有），且垂直滚轮会被外层
 * 纵向滚动容器消费，导致电脑端无法用鼠标操作首页顶部的横向卡片。
 * Android 端为空实现。
 */
expect fun Modifier.desktopPagerMouseControl(
    onScrollLeft: () -> Unit,
    onScrollRight: () -> Unit,
    pageCount: Int,
): Modifier
