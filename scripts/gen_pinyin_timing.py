"""生成 `core/src/commonMain/kotlin/cp/player/core/lyrics/PinyinTimingTable.kt`。

数据源：Unicode Unihan `kMandarin`（带声调符号的拼音）+ `kIRG_GSource`（GB2312 收字范围），
许可是 Unicode License v3，与仓库的 Apache-2.0 兼容。

只做一件事：给 GB2312 的 6763 个汉字标上
**声母类（8 档）· 韵母类（8 档）· 声调（1~4，0=轻声/未知）**，
供 `KaraokeArticulation` 算「字前暗区」与「元音弹性权重」。

用法：
    python scripts/gen_pinyin_timing.py --unihan /path/to/unihan
"""

import argparse
import os
import sys

ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_"
assert len(ALPHABET) == 64

# 声母 → 类别（0 零声母, 1 不送气塞音, 2 送气塞音, 3 塞擦音, 4 擦音, 5 鼻音, 6 边音/日, 7 半元音）
INITIALS = {
    "zh": 3, "ch": 3, "sh": 4,
    "b": 1, "d": 1, "g": 1,
    "p": 2, "t": 2, "k": 2,
    "z": 3, "c": 3, "j": 3, "q": 3,
    "f": 4, "s": 4, "x": 4, "h": 4,
    "m": 5, "n": 5,
    "l": 6, "r": 6,
    "y": 7, "w": 7,
}

# 韵母 → 类别（0 大开, 1 中, 2 闭前, 3 闭后, 4 前响双元音, 5 后响双元音, 6 前鼻音, 7 后鼻音）
FINALS = {
    "a": 0, "ia": 0, "ua": 0,
    "o": 1, "uo": 1, "e": 1, "ie": 1, "ve": 1, "ê": 1,
    "i": 2, "v": 2, "ii": 2,
    "u": 3,
    "ai": 4, "uai": 4, "ei": 4, "uei": 4, "ui": 4,
    "ao": 5, "iao": 5, "ou": 5, "iou": 5, "iu": 5,
    "an": 6, "ian": 6, "uan": 6, "van": 6, "en": 6, "in": 6, "uen": 6, "un": 6, "vn": 6,
    "ang": 7, "iang": 7, "uang": 7, "eng": 7, "ing": 7, "ueng": 7, "ong": 7, "iong": 7,
}

TONE_MAP = {
    "ā": ("a", 1), "á": ("a", 2), "ǎ": ("a", 3), "à": ("a", 4),
    "ē": ("e", 1), "é": ("e", 2), "ě": ("e", 3), "è": ("e", 4),
    "ī": ("i", 1), "í": ("i", 2), "ǐ": ("i", 3), "ì": ("i", 4),
    "ō": ("o", 1), "ó": ("o", 2), "ǒ": ("o", 3), "ò": ("o", 4),
    "ū": ("u", 1), "ú": ("u", 2), "ǔ": ("u", 3), "ù": ("u", 4),
    "ǖ": ("v", 1), "ǘ": ("v", 2), "ǚ": ("v", 3), "ǜ": ("v", 4), "ü": ("v", 0),
}


def parse_pinyin(raw: str):
    """'hǎi' -> (initial_class, final_class, tone)。解析失败返回 None。"""
    base, tone = [], 0
    for ch in raw.strip().lower():
        if ch in TONE_MAP:
            rep, t = TONE_MAP[ch]
            base.append(rep)
            tone = tone or t
        elif ch.isalpha() or ch == "ê":
            base.append(ch)
    word = "".join(base)
    if not word:
        return None
    for size in (2, 1):
        ini = word[:size]
        if ini in INITIALS:
            fin = word[size:]
            break
    else:
        ini, fin = "", word
    if not fin:
        return None
    # zhi chi shi ri zi ci si 的舌尖元音写作 i，归「闭前」即可
    fin_class = FINALS.get(fin)
    if fin_class is None:
        # 少数没有规范化的写法（如 ê / er）按最接近的类兜底
        fin_class = 1 if fin.startswith("e") else (0 if fin.startswith("a") else 2)
    return INITIALS.get(ini, 0), fin_class, tone


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--unihan", required=True)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    mandarin, gb = {}, set()
    for name, key in (("Unihan_Readings.txt", "kMandarin"), ("Unihan_IRGSources.txt", "kIRG_GSource")):
        with open(os.path.join(args.unihan, name), encoding="utf-8") as f:
            for line in f:
                if line.startswith("#"):
                    continue
                parts = line.rstrip("\n").split("\t")
                if len(parts) != 3 or parts[1] != key:
                    continue
                cp = int(parts[0][2:], 16)
                if key == "kMandarin":
                    mandarin[cp] = parts[2].split()[0]
                elif parts[2].startswith(("G0-", "G1-")):
                    gb.add(cp)

    rows = []
    skipped = 0
    for cp in sorted(gb):
        py = mandarin.get(cp)
        parsed = parse_pinyin(py) if py else None
        if parsed is None:
            skipped += 1
            continue
        rows.append((chr(cp),) + parsed)

    out = args.out or os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "core/src/commonMain/kotlin/cp/player/core/lyrics/PinyinTimingTable.kt",
    )
    kanji = "".join(r[0] for r in rows)
    # 9 bit：`声母类(3) * 64 + 韵母类(3) * 8 + 声调(3)`，用 2 个 6 bit 字符装
    packed = "".join(
        ALPHABET[(ini * 64 + fin * 8 + tone) >> 6] + ALPHABET[(ini * 64 + fin * 8 + tone) & 63]
        for _, ini, fin, tone in rows
    )

    def wrap(s, width=120):
        chunks = [s[i:i + width] for i in range(0, len(s), width)]
        return "\n".join('        "%s"%s' % (c, " +" if i < len(chunks) - 1 else "")
                         for i, c in enumerate(chunks))

    body = f'''package cp.player.core.lyrics

/**
 * GB2312 汉字的「声母类 · 韵母类 · 声调」速查表。
 *
 * ## 为什么需要它
 * 逐字时长要区分「字与字为什么不一样长」：`海`(hǎi) 是擦音起头 + 前响双元音 + 三声，
 * `的`(de) 是塞音 + 轻声 —— 两者在歌唱里的可延展性差一倍以上。
 * 之前靠一张手挑的几百字白名单猜（繁体、生僻、粤语词全落空），这里换成全量拼音。
 *
 * ## 编码
 * [KANJI] 与 [PACKED] 逐字对应，[PACKED] 每两字符装 9 bit：
 * `声母类(0..7) * 64 + 韵母类(0..7) * 8 + 声调(0..4)`。
 *
 * 声母类：0 零声母 · 1 不送气塞音 · 2 送气塞音 · 3 塞擦音 · 4 擦音 · 5 鼻音 · 6 边/日 · 7 半元音
 * 韵母类：0 大开 · 1 中 · 2 闭前 · 3 闭后 · 4 前响双元音 · 5 后响双元音 · 6 前鼻音 · 7 后鼻音
 *
 * ## 数据来源
 * Unicode Unihan `kMandarin` + `kIRG_GSource`（GB2312 G0/G1 共 {len(rows)} 字；
 * 多音字取 Unihan 的首条读音）。**由 `scripts/gen_pinyin_timing.py` 生成，勿手改。**
 */
internal object PinyinTimingTable {{

    private const val ALPHABET = "{ALPHABET}"

    private const val KANJI =
{wrap(kanji)}

    private const val PACKED =
{wrap(packed)}

    private val index: Map<Char, Int> by lazy {{
        HashMap<Char, Int>({len(rows) * 4 // 3}).apply {{
            for (i in KANJI.indices) put(KANJI[i], i)
        }}
    }}

    fun contains(ch: Char): Boolean = index.containsKey(ch)

    /** 声母类 0..7；表外按零声母处理。 */
    fun initialClass(ch: Char): Int = valueOf(ch)?.let {{ (it shr 6) and 0b111 }} ?: 0

    /** 韵母类 0..7；表外按「中」处理。 */
    fun finalClass(ch: Char): Int = valueOf(ch)?.let {{ (it shr 3) and 0b111 }} ?: 1

    /** 声调 0..4（0 = 轻声 / 未知）。 */
    fun tone(ch: Char): Int = valueOf(ch)?.let {{ it and 0b111 }} ?: 0

    private fun valueOf(ch: Char): Int? {{
        val i = index[ch] ?: return null
        val hi = ALPHABET.indexOf(PACKED[i * 2])
        val lo = ALPHABET.indexOf(PACKED[i * 2 + 1])
        if (hi < 0 || lo < 0) return null
        return (hi shl 6) or lo
    }}
}}
'''
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write(body)
    print(f"生成 {out}")
    print(f"  汉字 {len(rows)} 个（跳过无拼音 {skipped}），打包串 {len(packed)} 字符")
    print(f"  源文件 {os.path.getsize(out)} B，常驻 ≈ {len(kanji) * 2 // 1024} KB")
    if len(rows) < 3000:
        sys.exit("收录字数异常偏少，检查 Unihan 路径")


if __name__ == "__main__":
    main()
