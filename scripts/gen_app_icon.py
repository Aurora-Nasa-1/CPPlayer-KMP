# -*- coding: utf-8 -*-
"""
生成 CPPlayer 桌面端应用图标（Windows .ico / macOS .icns / Linux .png）。

为什么用脚本而不是"找一张图"：图标要跟着主题色走（主色 #4F55A5 蓝紫、辅色 #006A68 青绿），
而且要能随时改主色 / 改形状重新出一版。这里用**带符号距离场（SDF）**直接算覆盖率，
抗锯齿是解析式的（半个像素的软边），不是"先画硬边再缩放"，所以 16px 也不糊。

用法：
    python scripts/gen_app_icon.py                       # 桌面 + 安卓（默认方案 wave-play）
    python scripts/gen_app_icon.py --variant bars        # 换方案
    python scripts/gen_app_icon.py --target android      # 只出安卓
    python scripts/gen_app_icon.py --preview             # 只出预览图，不写产物

产物：
    桌面  app/desktop-icons/{icon.ico,icon.icns,icon.png}
    安卓  app-android/src/main/res/mipmap-*/{ic_launcher.png,ic_launcher_foreground.png,ic_launcher_background.png}
          app-android/src/main/res/drawable-*/ic_stat_playback.png（通知栏单色图标）
          （XML 是源码，手写：mipmap-anydpi-v26/ic_launcher.xml）
"""

from __future__ import annotations

import argparse
import io
import math
import os
import struct

import numpy as np
from PIL import Image

# ---------------------------------------------------------------- 常量

N = 512.0  # 图标设计坐标系：512x512 单位，所有尺寸都按这个坐标系写

# 背景渐变（对角线，左上 -> 右下）。中段刻意留品牌主色，两端做提亮 / 压暗。
BG_STOPS = [
    (0.00, (0x6E, 0x60, 0xF2)),
    (0.48, (0x4F, 0x55, 0xA5)),  # ← 品牌主色
    (1.00, (0x27, 0x2B, 0x66)),
]
# 青绿副色的柔光，落在左下角，让纯紫不闷。
GLOW_COLOR = (0x14, 0xB8, 0xA6)
GLOW_CENTER = (150.0, 400.0)
GLOW_RADIUS = 0.62 * N
GLOW_STRENGTH = 0.34

FG = (255, 255, 255)  # 前景（播放三角 / 波形）纯白

SQUIRCLE_RADIUS = 112.0  # 约 22%，接近 macOS 的圆角比例
SUPERSAMPLE = 4  # 主图先按 2048 渲染再缩到 512


# ---------------------------------------------------------------- SDF 基元

def sd_box(qx, qy, hx, hy, r):
    """
    圆角矩形 SDF（iq 的标准公式）。

    ⚠️ 「+ r」写在 `|q| - b` 同一项里，不是单独减 —— 这是 iq 版的关键：
    把矩形外扩一个 r，再 length(max(.,0))，这样转角处量到的是外扩后的角点
    到原转角的距离，最后再 `- r` 就拿到圆角表面的真实距离。
    之前我写成 `length(max(|q|-b,0))`，转角处算成 0 - r = -r，等于在转角
    "往内陷了 r"，结果整张图标没有圆角、四角还出血。
    """
    qx = np.abs(qx) - hx + r
    qy = np.abs(qy) - hy + r
    outside = np.hypot(np.maximum(qx, 0.0), np.maximum(qy, 0.0))
    inside = np.minimum(np.maximum(qx, qy), 0.0)
    return outside + inside - r


def sd_triangle(px, py, ax, ay, bx, by, cx, cy):
    """
    三角形 SDF（iq 的经典公式）。正=外，负=内。

    ⚠️ 两个 min 是**按分量分别取**的：距离平方取三者最小，三条边的有向符号也取三者最小，
    最后 `符号 = sign(min(三个有向符号))`。这不是"整体乘一个 sign" ——
    我第一版就是那么写的，结果整个平面都判成内部（因为 CCW 三角形那个全局 sign 恒为 -1），
    图标上直接看不到三角。
    """
    e0x, e0y = bx - ax, by - ay
    e1x, e1y = cx - bx, cy - by
    e2x, e2y = ax - cx, ay - cy

    v0x, v0y = px - ax, py - ay
    v1x, v1y = px - bx, py - by
    v2x, v2y = px - cx, py - cy

    def perp(vx, vy, ex, ey):
        h = np.clip((vx * ex + vy * ey) / (ex * ex + ey * ey), 0.0, 1.0)
        return vx - ex * h, vy - ey * h

    p0x, p0y = perp(v0x, v0y, e0x, e0y)
    p1x, p1y = perp(v1x, v1y, e1x, e1y)
    p2x, p2y = perp(v2x, v2y, e2x, e2y)

    d = np.minimum(np.minimum(p0x * p0x + p0y * p0y,
                              p1x * p1x + p1y * p1y),
                   p2x * p2x + p2y * p2y)

    s = np.sign(e0x * e2y - e0y * e2x)
    side = np.minimum(np.minimum(s * (v0x * e0y - v0y * e0x),
                                 s * (v1x * e1y - v1y * e1x)),
                      s * (v2x * e2y - v2y * e2x))
    return -np.sqrt(d) * np.sign(side)


def sd_wave_stroke(x, y, x0, x1, yc, amp, cycles, width):
    """
    正弦粗线（两端圆头）。

    精确的距离场没有闭式解，这里用「垂直距离近似」：|y - f(x)| * cos(theta)，
    theta 是切线倾角。正弦的斜率不大时这个近似误差 < 1%，视觉上看不出来。
    两端用 hypot 接上端点距离，于是自然得到圆头端帽。
    """
    span = x1 - x0
    xc = np.clip(x, x0, x1)
    t = (xc - x0) / span * cycles * 2.0 * np.pi
    f = yc + amp * np.sin(t)
    dydx = amp * np.cos(t) * (cycles * 2.0 * np.pi / span)
    cos_t = 1.0 / np.sqrt(1.0 + dydx * dydx)
    dy = (y - f) * cos_t
    dx = x - xc
    return np.hypot(dx, dy) - width * 0.5


# ---------------------------------------------------------------- 合成

def coverage(sd, pixel):
    """SDF -> 覆盖率（解析抗锯齿：边界处半个像素的软过渡）。"""
    return np.clip(0.5 - sd / pixel, 0.0, 1.0)


def gradient_color(t, stops):
    t = np.clip(t, 0.0, 1.0)
    out = np.zeros(t.shape + (3,), dtype=np.float64)
    for i in range(len(stops) - 1):
        t0, c0 = stops[i]
        t1, c1 = stops[i + 1]
        w = np.clip((t - t0) / (t1 - t0), 0.0, 1.0)
        # smoothstep，避免分段线性在停靠点出现可见的折角
        w = w * w * (3.0 - 2.0 * w)
        for ch in range(3):
            out[..., ch] += (c0[ch] + (c1[ch] - c0[ch]) * w) * (t >= t0) * (t <= t1)
    # 端点之外：取最近停靠点的颜色（上面按区间相乘已经保证只有一段生效，
    # 但 t 恰等于 1.0 时会落在最后一段的 w=1，没问题）
    return out


def glyph_sd(x, y, variant):
    """前景图形的 SDF。返回 None 表示"该方案没有前景"。"""
    if variant == "wave":
        # 纯波形：一条粗正弦，居中，1.5 个周期。
        return sd_wave_stroke(x, y, 96.0, 416.0, 256.0, 62.0, 1.5, 52.0)

    if variant == "bars":
        # 播放三角 + 三根等响条
        tri = sd_triangle(x, y, 118.0, 168.0, 254.0, 256.0, 118.0, 344.0) - 30.0
        bars = None
        for cx, half_h in ((302.0, 60.0), (344.0, 88.0), (386.0, 64.0)):
            b = sd_box(x - cx, y - 256.0, 14.0, half_h, 14.0)
            bars = b if bars is None else np.minimum(bars, b)
        return np.minimum(tri, bars)

    # 默认：wave-play —— 播放三角 + 从它右侧"流"出去的波形。
    # 三角稍小一点，把横向空间让给波形（波形是这套视觉的身份标识，
    # 应用里的进度条也是波浪的，图标跟着走）。
    tri = sd_triangle(x, y, 104.0, 162.0, 232.0, 256.0, 104.0, 350.0) - 28.0
    wave = sd_wave_stroke(x, y, 268.0, 424.0, 256.0, 40.0, 1.25, 44.0)
    return np.minimum(tri, wave)


def render(
    variant: str,
    size: int = 512,
    supersample: int = SUPERSAMPLE,
    squircle: bool = True,
    glyph: bool = True,
) -> Image.Image:
    """
    画一层图标。

    - `squircle=True`：底板是圆角方形（桌面图标、安卓旧式图标这么用）；
      关掉就是**满铺方形**（安卓自适应图标的 background —— 那一层本来就要被厂商遮罩裁，
      自己再带圆角等于被裁两次）。
    - `glyph=False`：只出背景，不带前景图形。
    """
    px = int(size * supersample)
    step = N / px
    axis = (np.arange(px) + 0.5) * step
    x, y = np.meshgrid(axis, axis)

    # —— 底板：圆角方形（或满铺）
    if squircle:
        bg_sd = sd_box(x - 256.0, y - 256.0, 256.0, 256.0, SQUIRCLE_RADIUS)
        bg_cov = coverage(bg_sd, step)
    else:
        bg_cov = np.ones_like(x)

    # —— 底板渐变（对角线）
    t = (x + y) / (2.0 * N)
    col = gradient_color(t, BG_STOPS)

    # —— 左下角青绿柔光
    r = np.hypot(x - GLOW_CENTER[0], y - GLOW_CENTER[1]) / GLOW_RADIUS
    # ⚠️ 保持 2D：glow 若带一个长度为 1 的尾轴，下面与 col[..., ch] 相乘会广播成
    # (px, px, px)，直接申请 64GB 内存炸掉。
    glow = np.exp(-2.0 * r * r) * GLOW_STRENGTH
    for ch in range(3):
        col[..., ch] = col[..., ch] * (1.0 - glow) + GLOW_COLOR[ch] * glow

    # —— 顶部一道很淡的高光，避免大色块发闷
    gloss = np.clip(1.0 - y / (0.62 * N), 0.0, 1.0) ** 2 * 0.10
    for ch in range(3):
        col[..., ch] = col[..., ch] * (1.0 - gloss) + 255.0 * gloss

    # —— 前景
    if glyph:
        g_sd = glyph_sd(x, y, variant)
        if g_sd is not None:
            g_cov = coverage(g_sd, step)
            for ch in range(3):
                col[..., ch] = col[..., ch] * (1.0 - g_cov) + FG[ch] * g_cov

    rgba = np.empty((px, px, 4), dtype=np.float64)
    rgba[..., :3] = col
    rgba[..., 3] = bg_cov * 255.0
    img = Image.fromarray(np.clip(rgba, 0, 255).astype(np.uint8), mode="RGBA")
    if supersample != 1:
        img = img.resize((size, size), Image.LANCZOS)
    return img


# ---------------------------------------------------------------- 安卓自适应图标

# ⚠️ 安卓**不能**直接拿桌面那张圆角方形当启动图标：各厂商会用圆形 / 方圆 / 圆角方形
# 等遮罩去裁它，圆角是自己带的 ⇒ 被裁后要么四角露出底色、要么被切成怪形状。
# 必须拆成两层：background（满铺，被裁的就是它）+ foreground（图形，收在安全圈内）。
#
# 尺寸单位是 dp（不是 px）：画布 108dp，其中**保证可见**的是中心直径 66dp 的圆，
# 外圈 21dp 是"可能被裁掉"的活动区。所以图形必须缩到 66dp 圈里。
ANDROID_RES = os.path.join("app-android", "src", "main", "res")
ANDROID_DENSITIES = [("mdpi", 1.0), ("hdpi", 1.5), ("xhdpi", 2.0),
                     ("xxhdpi", 3.0), ("xxxhdpi", 4.0)]
FOREGROUND_DP = 108.0
SAFE_DP = 66.0
LEGACY_DP = 48.0  # 旧式方形图标（API < 26 与部分 OEM 用）的基准尺寸
SAFE_MARGIN = 0.95  # 图形缩到安全圈的 95%，留出抗锯齿与遮罩误差

# —— 通知栏小图标（状态栏里的那个）——
# ⚠️ 它和启动图标是**两套要求**：必须是**白色剪影 + 透明底**，颜色由系统着色
# （Android 5.0 起状态栏图标一律被 tint），带颜色/带渐变的图会被糊成一团色块。
# 而且它只有 24dp，比启动图标小一个量级 —— 所以**只用播放三角，不带波形**：
# 波形在这个尺寸上会糊成一条抖动的线，三角才是唯一还认得出来的形状。
NOTIFICATION_DP = 24.0
NOTIFICATION_RES = os.path.join("app-android", "src", "main", "res")


def notification_glyph_sd(x, y):
    """通知栏图标的图形：圆角播放三角，撑满 24dp 画布的约 82%（留出系统安全边距）。"""
    return sd_triangle(x, y, 117.0, 82.0, 395.0, 256.0, 117.0, 430.0) - 36.0


def render_notification(size: int, supersample: int = 4) -> Image.Image:
    px = int(size * supersample)
    step = N / px
    axis = (np.arange(px) + 0.5) * step
    x, y = np.meshgrid(axis, axis)
    cov = coverage(notification_glyph_sd(x, y), step)
    rgba = np.zeros((px, px, 4), dtype=np.float64)
    rgba[..., :3] = FG
    rgba[..., 3] = cov * 255.0
    img = Image.fromarray(np.clip(rgba, 0, 255).astype(np.uint8), mode="RGBA")
    if supersample != 1:
        img = img.resize((size, size), Image.LANCZOS)
    return img


def glyph_bounds(variant: str, res: int = 768) -> tuple:
    """算出图形的包围盒（设计坐标系）。用数值法，别手填 —— 手填会随改方案而失效。"""
    step = N / res
    axis = (np.arange(res) + 0.5) * step
    x, y = np.meshgrid(axis, axis)
    mask = glyph_sd(x, y, variant) < 0
    ys, xs = np.nonzero(mask)
    return (xs.min() * step, ys.min() * step,
            (xs.max() + 1) * step, (ys.max() + 1) * step)


def render_foreground(variant: str, size: int, supersample: int = 4) -> Image.Image:
    """
    自适应图标的**前景层**：透明底 + 白色图形，图形按中心缩放进 66dp 安全圈。

    做法是把画布坐标**反变换**回图形空间再求 SDF（而不是先画再缩放）——
    缩放后的抗锯齿宽度也要跟着变（`step / scale`），否则边缘会过软或过硬。
    """
    x0, y0, x1, y1 = glyph_bounds(variant)
    cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    radius_now = 0.5 * math.hypot(x1 - x0, y1 - y0)
    radius_safe = (SAFE_DP / FOREGROUND_DP) * (N / 2.0)
    scale = radius_safe / radius_now * SAFE_MARGIN

    px = int(size * supersample)
    step = N / px
    axis = (np.arange(px) + 0.5) * step
    X, Y = np.meshgrid(axis, axis)
    qx = cx + (X - N / 2.0) / scale
    qy = cy + (Y - N / 2.0) / scale

    cov = coverage(glyph_sd(qx, qy, variant), step / scale)
    rgba = np.zeros((px, px, 4), dtype=np.float64)
    rgba[..., :3] = FG
    rgba[..., 3] = cov * 255.0
    img = Image.fromarray(np.clip(rgba, 0, 255).astype(np.uint8), mode="RGBA")
    if supersample != 1:
        img = img.resize((size, size), Image.LANCZOS)
    return img


def write_android(master: Image.Image, variant: str) -> None:
    """
    写 mipmap-* 下的三层 PNG。

    - `ic_launcher_background`：满铺渐变（自适应图标的背景，会被遮罩裁）
    - `ic_launcher_foreground`：透明底 + 白色图形（收在 66dp 安全圈内）
    - `ic_launcher`：旧式方形图标（自带圆角）。minSdk 29 用不到它，但 OEM /
      Play / lint 会按密度找 mipmap，缺了会报 "missing density"。

    ⚠️ 背景**不写成 XML 渐变**：那份颜色会和脚本里的 `BG_STOPS` 变成两个真相源，
    改了桌面忘了改安卓就出现两套配色。直接渲染 PNG 就不会漂移。
    组装它们的 `mipmap-anydpi-v26/ic_launcher.xml` 是源码，手写、已入库。
    """
    fg_master = render_foreground(variant, 432, supersample=4)
    bg_master = render(variant, 432, supersample=2, squircle=False, glyph=False)
    notif_master = render_notification(int(round(NOTIFICATION_DP * 4)), supersample=4)
    for name, mult in ANDROID_DENSITIES:
        d = os.path.join(ANDROID_RES, f"mipmap-{name}")
        os.makedirs(d, exist_ok=True)
        side = int(round(FOREGROUND_DP * mult))
        fg_master.resize((side, side), Image.LANCZOS).save(
            os.path.join(d, "ic_launcher_foreground.png"))
        bg_master.resize((side, side), Image.LANCZOS).convert("RGB").save(
            os.path.join(d, "ic_launcher_background.png"))
        legacy = int(round(LEGACY_DP * mult))
        master.resize((legacy, legacy), Image.LANCZOS).save(
            os.path.join(d, "ic_launcher.png"))
        # 通知栏图标放 drawable-*（不是 mipmap-*）：它不是"应用图标"，
        # 走 drawable 才符合 Android 的资源约定。
        dd = os.path.join(NOTIFICATION_RES, f"drawable-{name}")
        os.makedirs(dd, exist_ok=True)
        notif_master.resize((int(round(NOTIFICATION_DP * mult)),) * 2,
                            Image.LANCZOS).save(
            os.path.join(dd, "ic_stat_playback.png"))
    print("android ->", os.path.abspath(ANDROID_RES))


# ---------------------------------------------------------------- 输出

ICO_SIZES = [(16, 16), (20, 20), (24, 24), (32, 32), (40, 40), (48, 48),
             (64, 64), (96, 96), (128, 128), (256, 256)]

# ICNS：类型 -> 边长。'ic10' 是 512@2x（1024），带上它 Retina 下不会发虚。
ICNS_TYPES = [("ic11", 32), ("ic12", 64), ("ic07", 128),
              ("ic08", 256), ("ic09", 512), ("ic10", 1024)]


def write_icns(master: Image.Image, path: str) -> None:
    """手写 ICNS 容器：'icns' 头 + 若干 (类型, 长度, PNG) 条目。"""
    chunks = []
    for typ, side in ICNS_TYPES:
        buf = io.BytesIO()
        master.resize((side, side), Image.LANCZOS).save(buf, format="PNG")
        data = buf.getvalue()
        chunks.append(typ.encode("ascii") + struct.pack(">I", len(data) + 8) + data)
    body = b"".join(chunks)
    with open(path, "wb") as f:
        f.write(b"icns" + struct.pack(">I", len(body) + 8) + body)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--variant", default="wave-play",
                    choices=["wave-play", "bars", "wave"])
    ap.add_argument("--out", default=os.path.join("app", "desktop-icons"))
    ap.add_argument("--target", default="all",
                    choices=["desktop", "android", "all"])
    ap.add_argument("--preview", action="store_true",
                    help="只输出预览图（含安卓前景层与圆形遮罩效果），不写产物")
    args = ap.parse_args()

    master = render(args.variant, 1024, supersample=2)
    base = master.resize((512, 512), Image.LANCZOS)

    if args.preview:
        os.makedirs("build/icon-preview", exist_ok=True)
        base.save(f"build/icon-preview/{args.variant}-512.png")
        strip = Image.new("RGBA", (32 * 4 + 8 * 5, 40), (0, 0, 0, 0))
        for i, s in enumerate((16, 32, 64, 128)):
            strip.paste(base.resize((s, s), Image.LANCZOS),
                        (8 + i * (32 + 8), 40 - s))
        strip.save(f"build/icon-preview/{args.variant}-sizes.png")
        # 安卓一起出预览：前景层 + 圆形遮罩后的效果（模拟最常见裁切）
        fg = render_foreground(args.variant, 432, supersample=3)
        fg.save(f"build/icon-preview/{args.variant}-android-fg.png")
        bg = base.resize((432, 432), Image.LANCZOS).convert("RGBA")
        merged = Image.alpha_composite(bg, fg)
        circle = Image.new("L", (432 * 4, 432 * 4), 0)
        from PIL import ImageDraw
        ImageDraw.Draw(circle).ellipse((0, 0, 432 * 4 - 1, 432 * 4 - 1), fill=255)
        merged.putalpha(Image.composite(
            Image.new("L", (432, 432), 255), Image.new("L", (432, 432), 0),
            circle.resize((432, 432), Image.LANCZOS)))
        merged.save(f"build/icon-preview/{args.variant}-android-circle.png")
        print("preview ->", os.path.abspath(f"build/icon-preview/{args.variant}-512.png"))
        return

    if args.target in ("desktop", "all"):
        os.makedirs(args.out, exist_ok=True)
        base.save(os.path.join(args.out, "icon.png"))
        base.save(os.path.join(args.out, "icon.ico"),
                  format="ICO", sizes=ICO_SIZES)
        write_icns(master, os.path.join(args.out, "icon.icns"))
        print("icons ->", os.path.abspath(args.out))
    if args.target in ("android", "all"):
        write_android(base, args.variant)


if __name__ == "__main__":
    main()
