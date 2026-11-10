#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""check-docs-site.py — 校验 build-docs-site.py 的产物：

   1. 所有 `xxx.html#anchor` 跨页锚点在目标页真实存在
   2. 表格里的 `\|` 已还原成 `|`，不残留反斜杠
   3. 正文不残留未渲染的 Markdown 标记
   4. 站内相对链接指向的文件确实存在

用法：python scripts/check-docs-site.py   （须先跑 build-docs-site.py）
"""
import os
import re
import sys
from pathlib import Path

SITE = Path(__file__).resolve().parent.parent / "site"
errors, warns = [], []

pages = {p.relative_to(SITE).as_posix(): p for p in SITE.rglob("*.html")}

ids = {}
for rel, p in pages.items():
    txt = p.read_text(encoding="utf-8")
    ids[rel] = set(re.findall(r'\sid="([^"]+)"', txt))


def resolve(from_page: str, href: str) -> str:
    """把 from_page 里的相对 href 归一成站点根相对路径。"""
    base = os.path.dirname(from_page) or "."
    target = href.split("#")[0]
    if not target:
        return from_page
    return Path(os.path.normpath(os.path.join(base, target))).as_posix()


# --- 1) 锚点 -----------------------------------------------------------------
for rel, p in pages.items():
    txt = p.read_text(encoding="utf-8")
    for href, frag in re.findall(r'href="([^"]+?\.html)#([^"]+)"', txt):
        if href.startswith(("http://", "https://")):
            continue
        cand = resolve(rel, href)
        if cand not in ids:
            errors.append(f"{rel}: 锚点目标页不存在 {href}#{frag} (-> {cand})")
        elif frag not in ids[cand]:
            errors.append(f"{rel}: 锚点 #{frag} 在 {cand} 中不存在")

# --- 2) 表格转义残留 ---------------------------------------------------------
for rel, p in pages.items():
    txt = p.read_text(encoding="utf-8")
    body = txt.split('<article class="markdown-body">', 1)[-1]
    for m in re.finditer(r"<td[^>]*>(.*?)</td>", body, re.DOTALL):
        cell = m.group(1)
        if re.search(r"\\[|*_`~]", cell):
            warns.append(f"{rel}: 表格单元格残留转义反斜杠: {cell[:70]!r}")

# --- 3) 未渲染的 Markdown 标记 ----------------------------------------------
for rel, p in pages.items():
    txt = p.read_text(encoding="utf-8")
    body = txt.split('<article class="markdown-body">', 1)[-1].split("</article>")[0]
    body = re.sub(r"<pre>.*?</pre>", "", body, flags=re.DOTALL)
    body = re.sub(r"<code>.*?</code>", "", body, flags=re.DOTALL)
    body = re.sub(r"<[^>]+>", "", body)  # 去掉标签再看纯文本
    for pat, name in [(r"^\s*\|.*\|\s*$", "表格管道行"),
                      (r"\]\([^)]*\)", "未渲染链接"),
                      (r"\*\*[^*]+\*\*", "未渲染加粗"),
                      (r"(?m)^#{1,6}\s", "未渲染标题"),
                      (r"(?m)^\s*[-*+]\s+\[", "未渲染列表链接")]:
        for m in re.finditer(pat, body, re.M):
            errors.append(f"{rel}: 正文残留 Markdown（{name}）: {m.group(0)[:70]!r}")

# --- 4) 站内相对链接 ---------------------------------------------------------
for rel, p in pages.items():
    txt = p.read_text(encoding="utf-8")
    for href in re.findall(r'href="([^"]+)"', txt):
        if href.startswith(("http://", "https://", "#", "mailto:", "data:")):
            continue
        if not href.split("#")[0]:
            continue
        cand = resolve(rel, href)
        if not (SITE / cand).exists():
            errors.append(f"{rel}: 站内链接失效 {href} (-> {cand})")

print(f"检查 {len(pages)} 个页面")
if warns:
    print(f"\n警告 {len(warns)} 条：")
    for w in warns[:20]:
        print("  !", w)
if errors:
    print(f"\n错误 {len(errors)} 条：")
    for e in errors[:40]:
        print("  x", e)
    sys.exit(1)
print("全部通过 ✓")
