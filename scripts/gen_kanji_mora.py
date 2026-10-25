"""生成 `core/src/commonMain/kotlin/cp/player/core/lyrics/KanjiMoraTable.kt`。

数据源：Unicode Unihan（`kJapanese` + `kGradeLevel`），许可是 Unicode License v3，
与仓库的 Apache-2.0 兼容（KANJIDIC2 是 CC BY-SA 4.0，ShareAlike 会传染，所以不用它）。

用法（需要 Unihan_Readings.txt / Unihan_DictionaryLikeData.txt 在 --unihan 目录）：
    python scripts/gen_kanji_mora.py --unihan /path/to/unihan

只做一件事：给常用汉字 + 人名用汉字标上「音读拍数 / 训读拍数」。
→ 逐字时长权重按拍数走（涙 3 拍 ≠ の 1 拍），这是日语行现在最明显的失真。
"""

import argparse
import os
import sys

SMALL = set("ゃゅょぁぃぅぇぉゎ")
ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_"
assert len(ALPHABET) == 64


def mora_count(kana: str) -> int:
    """数拍：拗音小字并进前一拍；っ / ー / ん 各算一拍。"""
    return max(1, sum(1 for c in kana if c not in SMALL))


def kata_to_hira(word: str) -> str:
    return "".join(chr(ord(c) - 0x60) if "\u30A1" <= c <= "\u30F6" else c for c in word)


def is_hiragana(word: str) -> bool:
    return word and all("\u3041" <= c <= "\u309F" or c == "\u30FC" for c in word)


def is_katakana(word: str) -> bool:
    return word and all("\u30A1" <= c <= "\u30FF" for c in word)


def median(values):
    s = sorted(set(values))
    return s[len(s) // 2] if s else 0


def parse_unihan(unihan_dir):
    grades = {}
    with open(os.path.join(unihan_dir, "Unihan_DictionaryLikeData.txt"), encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) == 3 and parts[1] == "kGradeLevel":
                grades[int(parts[0][2:], 16)] = int(parts[2])

    japanese = {}
    with open(os.path.join(unihan_dir, "Unihan_Readings.txt"), encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) == 3 and parts[1] == "kJapanese":
                japanese[int(parts[0][2:], 16)] = parts[2].split()

    # ⚠️ 新字体常常不是旧字体所在的那个码位（涙 U+6D99 vs 淚 U+6DDA），
    # 而 Unihan 的 kJapanese / kGradeLevel 只挂在旧字体上 ⇒ 必须顺着
    # kTraditionalVariant 借读音，否则「涙」这种最高频歌词字直接查不到。
    variant = {}
    with open(os.path.join(unihan_dir, "Unihan_Variants.txt"), encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) == 3 and parts[1] == "kJapaneseOldVariant":
                variant[int(parts[0][2:], 16)] = [
                    int(t[2:], 16) for t in parts[2].split() if t.startswith("U+")
                ]

    def words_for(cp):
        if cp in japanese:
            return japanese[cp]
        for alt in variant.get(cp, []):
            if alt in japanese:
                return japanese[alt]
        return []

    graded = {cp: g for cp, g in grades.items() if 1 <= g <= 8}
    # 新字体本身没被标注（涙 U+6D99 未进年级表，旧字体 淚 U+6DDA 才在），
    # 但它声明 kTraditionalVariant 指向一个已收录的字 ⇒ 一并收进来借读音。
    inherited = {
        cp: next(graded[t] for t in targets if t in graded)
        for cp, targets in variant.items()
        if cp not in graded and any(t in graded for t in targets)
    }

    rows = []
    missing = 0
    for cp, grade in {**graded, **inherited}.items():
        words = words_for(cp)
        if not words:
            missing += 1
        on = [kata_to_hira(w) for w in words if is_katakana(w)]
        kun = [w for w in words if is_hiragana(w)]
        on_mora = median([mora_count(w) for w in on])
        # Unihan 的训读不带「汉字/送假名」分界（ゆめみる 会把送假名也算进来），
        # 取最短的一条当词干拍数，宁可短一点也不要让动词把整句拖慢。
        kun_mora = min((mora_count(w) for w in kun), default=0)
        rows.append((chr(cp), on_mora, min(kun_mora, 7)))

    rows.sort(key=lambda r: r[0])
    print(f"  年级表 {len(graded)} 字 + 异体继承 {len(inherited)} 字；无读音 {missing} 个（按 1 拍处理）")
    return rows


def emit(rows, out_path):
    kanji_str = "".join(k for k, _, _ in rows)
    # 6 bit 一字符：`音读档(0=无, 1..4) * 8 + 训读拍数(0..7)`，最大 39 < 64。
    packed = "".join(ALPHABET[min(on, 4) * 8 + kunm] for _, on, kunm in rows)
    assert len(kanji_str) == len(packed)

    def wrap(s, width=120):
        chunks = [s[i:i + width] for i in range(0, len(s), width)]
        return "\n".join(
            '        "%s"%s' % (c, " +" if i < len(chunks) - 1 else "")
            for i, c in enumerate(chunks)
        )

    body = f'''package cp.player.core.lyrics

/**
 * 常用汉字 / 人名用汉字的「音读拍数 · 训读拍数」速查表。
 *
 * ## 为什么长这样
 * 逐字时长必须按**拍数**走：`涙`(なみだ) 是 3 拍、`の` 是 1 拍，若都按 1.0 参与分配，
 * 日语行里汉字永远被压短、假名永远被拖长 —— 这是现在日语行最明显的失真。
 *
 * 但把 {len(rows)} 条写成 `"漢" to 2` 会让源文件涨到 40KB 级、常量池也膨胀。
 * 这里用**等长索引 + 6 bit 打包**：
 * - [KANJI]  与 [PACKED] 逐字符一一对应，下标即字；
 * - [PACKED] 每字符是 `ALPHABET` 的下标 `v`：`v shr 3` = 音读档（0 表示无音读，1..4 即拍数），
 *   `v and 0b111` = 训读拍数（0 表示无训读）；
 * - 两串常量常驻 ≈ {len(rows) * 2 // 1024} KB；首次查询再建一次 `HashMap`（{len(rows)} 项，
 *   装箱后约 150 KB 堆），之后 O(1)。比起多背一个分词器（11~70 MB）可以忽略。
 *
 * ## 读哪个
 * 调用方判据（见 [moraFor]）：**汉字后接假名 ⇒ 训读，孤字 ⇒ 训读，汉字连写 ⇒ 音读**。
 * 这是日语书写的基本规则，比任何静态字典都准；遇到熟字训（今日=きょう）仍会错，
 * 那是词表要解决的事，不在本表职责内。
 *
 * ## 数据来源
 * Unicode Unihan `kJapanese` + `kGradeLevel`（Unicode License v3，与 Apache-2.0 兼容）。
 * **由 `scripts/gen_kanji_mora.py` 生成，不要手工编辑本文件。**
 */
internal object KanjiMoraTable {{

    private const val ALPHABET = "{ALPHABET}"

    /** 与 [PACKED] 等长，逐字对应。 */
    private const val KANJI =
{wrap(kanji_str)}

    /** 每字符 6 bit：`v shr 3` 音读档（0 = 无），`v and 0b111` 训读拍数。 */
    private const val PACKED =
{wrap(packed)}

    private val index: Map<Char, Int> by lazy {{
        HashMap<Char, Int>({len(rows) * 4 // 3}).apply {{
            for (i in KANJI.indices) put(KANJI[i], i)
        }}
    }}

    /** 该字是否在表内（表外汉字一律按 1 拍处理）。 */
    fun contains(ch: Char): Boolean = index.containsKey(ch)

    /** 音读拍数；无音读返回 0。 */
    fun onMora(ch: Char): Int = (packOf(ch) ?: return 0) shr 3

    /** 训读拍数；无训读返回 0。 */
    fun kunMora(ch: Char): Int = (packOf(ch) ?: return 0) and 0b111

    /**
     * 按上下文取拍数。
     *
     * @param followedByKana 该字后面紧跟假名（含送假名）⇒ 走训读。
     * @param inKanjiRun 该字左右还有汉字 ⇒ 走音读。
     */
    fun moraFor(ch: Char, followedByKana: Boolean, inKanjiRun: Boolean): Int {{
        val on = onMora(ch)
        val kun = kunMora(ch)
        val pick = when {{
            followedByKana || !inKanjiRun -> if (kun > 0) kun else on
            else -> if (on > 0) on else kun
        }}
        return if (pick > 0) pick else 1
    }}

    private fun packOf(ch: Char): Int? {{
        val i = index[ch] ?: return null
        val v = ALPHABET.indexOf(PACKED[i])
        return if (v < 0) null else v
    }}
}}
'''
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(body)
    return len(kanji_str), len(packed)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--unihan", required=True, help="解压后的 Unihan 目录")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    rows = parse_unihan(args.unihan)
    if not rows:
        sys.exit("没有解析到任何汉字，检查 --unihan 路径")
    out = args.out or os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "core/src/commonMain/kotlin/cp/player/core/lyrics/KanjiMoraTable.kt",
    )
    n, packed = emit(rows, out)
    on_only = sum(1 for _, on, _ in rows if on > 0)
    kun_only = sum(1 for _, _, kun in rows if kun > 0)
    print(f"生成 {out}")
    print(f"  汉字 {n} 个（音读 {on_only} / 训读 {kun_only}），打包串 {packed} 字符")
    print(f"  源文件 {os.path.getsize(out)} B，常驻 ≈ {n * 2 // 1024} KB")


if __name__ == "__main__":
    main()
