// ⚠️ 必须 opt-in：8 参数版 `Shapes` 构造器带 `@ExperimentalMaterial3ExpressiveApi`
// （5 参数的老构造器不带）。**文件级 opt-in 比逐个属性标注更稳** ——
// 后面再往这个文件加形状令牌时不会又踩一次。
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package cp.player.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Material 3 Expressive 形状刻度。
//
// ⚠️ 这里的值是 material3 1.11.0-alpha07 的**官方令牌真值**，取自
// `androidx.compose.material3.tokens.ShapeTokens` 的 <clinit>（javap 读出）。
// **不要凭记忆或对照文档改** —— 令牌值随版本漂，文档与实现偶有出入。
//
// ⚠️ 必须用 **8 参数**构造。1.11 起 `Shapes` 新增了 largeIncreased /
// extraLargeIncreased / extraExtraLarge 三槽；只填 5 个会让这三个走默认值，
// 与自定义刻度混用 ⇒ 同一个界面上出现两套圆角体系。
//
// 历史教训：本文件曾写成 8/12/16/24/32 —— 整条刻度被 +4dp 平移、large 额外 +8dp，
// 结果 25 处 `MaterialTheme.shapes.*` 集体偏圆（14 处卡片取 extraLarge = 32 而非 28）。
// 「更圆」不等于「更 Expressive」：表达性来自形状**变化**（按压变形、选中态切换），
// 不是把静态圆角一路加大。

/**
 * 形状槽位与 M3E 规范的对照（便于改版式时查）：
 *
 * | 槽位 | 值 | 典型用途 |
 * |------|-----|---------|
 * | [Shapes.extraSmall] | 4dp | 分组列表的**行**（静止态）、缩略图角标 |
 * | [Shapes.small] | 8dp | 行内小图标底、紧凑标签 |
 * | [Shapes.medium] | 12dp | 卡片内嵌块、列表缩略图 |
 * | [Shapes.large] | 16dp | 分组容器、次级卡片、按钮按下态 |
 * | [Shapes.largeIncreased] | 20dp | 大尺寸按钮、中等浮层 |
 * | [Shapes.extraLarge] | 28dp | **主卡片 / 封面容器**（最常用的一档） |
 * | [Shapes.extraLargeIncreased] | 32dp | 底部弹窗、大面积浮层 |
 * | [Shapes.extraExtraLarge] | 48dp | 全屏容器、英雄区 |
 */
val AppShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
    largeIncreased = RoundedCornerShape(20.dp),
    extraLargeIncreased = RoundedCornerShape(32.dp),
    extraExtraLarge = RoundedCornerShape(48.dp),
)

/**
 * 少数**必须写死**的非常规形状。
 *
 * 这里**只放 `MaterialTheme.shapes` 表达不了的形状**（百分比圆角、单边圆角）。
 * 凡是「圆角方形容器」一律取 `MaterialTheme.shapes.*` —— 曾经这里另起了一套
 * `CpShapes.sheet = 32dp`，与 `shapes.extraLarge` 数值撞车却各自独立，
 * 改刻度时只改一边就会不一致。
 */
object CpShapes {

    /** 胶囊 / 圆形：百分比圆角，`MaterialTheme.shapes` 无法表达。 */
    val full = RoundedCornerShape(percent = 50)

    /**
     * 底部弹窗：只圆上方两角，取 32dp（= `shapes.extraLargeIncreased`）。
     *
     * 弹窗比卡片"更大"，用更大的一档是刻意的层级表达，不是随手写的数。
     */
    val sheet = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)

    /**
     * 迷你播放器：贴底悬浮，**上圆下略方**。
     *
     * 不对称是刻意的：它贴在内容底部、与屏幕边缘相接，四角全圆会显得"浮在半空"。
     * 上角 28dp 与主卡片（`shapes.extraLarge`）一致，下角 16dp 收一档。
     */
    val miniPlayer = RoundedCornerShape(
        topStart = 28.dp,
        topEnd = 28.dp,
        bottomStart = 16.dp,
        bottomEnd = 16.dp,
    )
}
