package cp.player.app.ui.wall

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

/**
 * 墙的指针输入：**滚轮 = 无极缩放**，以及桌面端的悬停焦点。
 *
 * 为什么必须是 `expect` / `actual`：`onPointerEvent` 与 `PointerEventType.Scroll`
 * 是 Compose Desktop 的 API，`commonMain` 里不存在。这与
 * `ui/component/HorizontalWheelScroll.kt` 是同一套范式（那边把垂直滚轮映射成横向滚动，
 * 这边把滚轮映射成焦距）。
 *
 * ⚠️ 挂载位置：只挂在**墙的画布**上，不外溢到整页 —— 墙是全屏画布、没有"页面可滚"，
 * 所以抢走滚轮不会影响任何既有滚动容器。退出墙模式后滚轮语义自动还原。
 *
 * @param onZoomFactor 滚轮 → 缩放倍率 + 事件位置（倍率乘在当前 zoom 上，保持对数手感）。
 * @param onHover 指针位置（视口坐标）。Android 端无 hover，不会回调。
 */
@Composable
expect fun Modifier.wallPointerZoom(
    onZoomFactor: (factor: Float, position: Offset) -> Unit,
    onHover: (position: Offset) -> Unit,
): Modifier
