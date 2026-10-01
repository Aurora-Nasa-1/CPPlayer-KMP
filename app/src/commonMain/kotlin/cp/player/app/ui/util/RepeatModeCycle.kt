package cp.player.app.ui.util

import cp.player.core.playback.RepeatMode

/**
 * 循环模式的下一档：关 → 列表循环 → 单曲循环 → 关。
 *
 * 这段 `when` 原先在 `MainScreen`（宽屏 / 窄屏两处）与 `PlayerScreen` 里**逐字重复了三份**，
 * 想调一次顺序（比如把「单曲」挪到「列表」前面）就得记得改三处 —— 迟早只改到一处，
 * 于是同一个按钮在手机版和桌面版上走出不同的循环。收敛到这里，只此一份。
 */
fun RepeatMode.next(): RepeatMode = when (this) {
    RepeatMode.OFF -> RepeatMode.ALL
    RepeatMode.ALL -> RepeatMode.ONE
    RepeatMode.ONE -> RepeatMode.OFF
}
