#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build-docs-site.py — 生成 CPPlayer 文档站（GitHub Pages 可直接部署）。

    python scripts/build-docs-site.py             # 输出到 <repo>/site/
    python scripts/build-docs-site.py --out DIR   # 输出到 DIR

零第三方依赖（仅标准库）。源文档是 `docs/` 下的 Markdown —— 那是唯一事实源，
本站点每次构建都从它们重新生成，不维护第二份 HTML。

设计约束：
  * Markdown 渲染器按 GitHub Flavored Markdown 的**实际使用子集**实现
    （ATX 标题 / 围栏代码 / GFM 管道表格 / 列表 / 引用 / 分隔线 / 行内标记），
    不追求 100% CommonMark —— 本仓文档不用脚注、定义列表、setext 标题。
  * 表格单元格里的 `\\|` 是转义管道，必须在切分单元格后还原成 `|`
    （GFM 表格扩展的行为），否则 SDK_TYPES.md 里 `string \\| null` 会显示反斜杠。
  * 标题 slug 与 GitHub 一致：小写 → 丢弃标点（CJK 保留）→ 空白转 `-`；
    重复标题追加 `-1` / `-2`。SDK_OPERATIONS.md 里的 `SDK_TYPES.md#accountbindingpage`
    这类跨文件锚点依赖此规则。
"""

from __future__ import annotations

import argparse
import html
import json
import os
import re
import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DOCS = REPO / "docs"

# GitHub 仓库地址 —— 仓库内源码链接（../provider-sdk-kmp/... 这类）改写成它。
REPO_URL = "https://github.com/Aurora-Nasa-1/CPPlayer-KMP"
PAGES_URL = "https://aurora-nasa-1.github.io/CPPlayer-KMP/"

SITE_TITLE = "CPPlayer 文档"

# ---------------------------------------------------------------------------
# 导航配置
#
# 每一项：file = 相对仓库根的 Markdown 源；out = 站点内输出路径；
#         title = 侧边栏标题；section = 分组标题。
# 顺序即侧边栏顺序。构建时会校验 file 存在，缺失即报错 —— 防止导航悄悄腐烂。
# ---------------------------------------------------------------------------
NAV = [
    ("开始使用", [
        ("docs/README.md", "index.html", "首页"),
        ("docs/USER_GUIDE.md", "guide/user.html", "使用指南"),
    ]),
    ("Provider 开发", [
        ("docs/dev/QUICK_PROVIDER_DEV_GUIDE.md", "guide/provider/quickstart.html", "1 · 最短路径上手"),
        ("docs/dev/PROVIDER_DEV_GUIDE.md", "guide/provider/contract.html", "2 · 业务契约"),
        ("docs/dev/MANIFEST_REFERENCE.md", "guide/provider/manifest.html", "3 · Manifest 与安装"),
        ("docs/dev/PROVIDER_WORKFLOWS.md", "guide/provider/workflows.html", "4 · 分域实现流程"),
        ("docs/dev/JS_PROVIDER_DEV_GUIDE.md", "guide/provider/javascript.html", "5 · JavaScript / TypeScript"),
        ("docs/dev/LYRICS_PLUGIN_DEV_GUIDE.md", "guide/provider/lyrics-plugin.html", "歌词源插件（独立体系）"),
    ]),
    ("SDK 参考", [
        ("docs/dev/SDK_OPERATIONS.md", "sdk/operations.html", "操作总表（161 项）"),
        ("docs/dev/SDK_TYPES.md", "sdk/types.html", "字段与类型（155 条）"),
        ("docs/dev/SDK_MIGRATION_STATUS.md", "sdk/migration.html", "迁移状态"),
    ]),
    ("架构与集成", [
        ("docs/dev/ARCHITECTURE.md", "arch/overview.html", "架构与模块职责"),
        ("docs/dev/INTEGRATION_API.md", "arch/integration-api.html", "对外集成 API v1"),
        ("docs/dev/FLICK_ENGINE.md", "arch/flick-engine.html", "FlickEngine 接入对照"),
        ("docs/dev/FONTS.md", "arch/fonts.html", "字体栈"),
        ("docs/dev/I18N.md", "arch/i18n.html", "国际化 (i18n)"),
    ]),
    ("打包与发布", [
        ("docs/dev/RELEASE.md", "release/index.html", "发布流程"),
    ]),
    ("设计记录（归档）", [
        ("docs/history/README.md", "history/index.html", "归档说明"),
        ("docs/history/PROVIDER_SDK_PROPOSAL.md", "history/sdk-proposal.html", "Provider SDK 设计提案"),
    ]),
]

# 站点外链接兜底：站内找不到的 .md 目标 → 仓库内对应路径的 GitHub 链接。
# 归档文档里指向已删除文件的链接正是靠它落到 GitHub 的历史视图上。
EXTRA_MD_OUT = {
    "README.md": "index.html",                      # 仓库根 README → 站首页
    "docs/history/README.md": "history/index.html",
}

# 站内图片资源（相对仓库根）。docs/ 当前没有正文插图，故为空 ——
# 有的话在这里登记，构建时会复制到 site/assets/。
ASSET_FILES: list[str] = []


# ===========================================================================
# Markdown → HTML
# ===========================================================================

_PUNCT_STRIP = re.compile(r"[^\w\s\-]", re.UNICODE)


def slugify(text: str, seen: dict) -> str:
    """与 GitHub 的 anchor 算法对齐。`seen` 保证同页标题唯一。"""
    s = _PUNCT_STRIP.sub("", text.lower())
    s = re.sub(r"\s+", "-", s.strip())
    if not s:
        s = "section"
    if s in seen:
        seen[s] += 1
        s = f"{s}-{seen[s]}"
    else:
        seen[s] = 0
    return s


def escape(t: str) -> str:
    return html.escape(t, quote=False)


def escape_attr(t: str) -> str:
    return html.escape(t, quote=True)


# --- 行内 -------------------------------------------------------------------

_CODE_SPAN = re.compile(r"(?<!\\)(`+)(.+?)\1", re.DOTALL)
_IMAGE = re.compile(r"!\[([^\]]*)\]\(([^)\s]+)(?:\s+\"([^\"]*)\")?\)")
_LINK = re.compile(r"\[([^\]]+)\]\(([^)\s]+)(?:\s+\"([^\"]*)\")?\)")
_STRONG = re.compile(r"\*\*(.+?)\*\*|__(.+?)__", re.DOTALL)
_EM = re.compile(r"(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])|(?<![\w_])_(?!\s)(.+?)(?<!\s)_(?![\w_])", re.DOTALL)
_DEL = re.compile(r"~~(.+?)~~", re.DOTALL)
_AUTOLINK = re.compile(r"<((?:https?|mailto:)[^>\s]+)>")


def render_inline(text: str, link_map) -> str:
    """行内标记渲染。代码跨度先摘出来保护，避免被其它规则改写。"""
    placeholders: list[str] = []

    def stash(t: str) -> str:
        placeholders.append(t)
        return f"\x00{len(placeholders) - 1}\x00"

    def code_sub(m):
        return stash(f"<code>{escape(m.group(2))}</code>")

    text = _CODE_SPAN.sub(code_sub, text)

    def img_sub(m):
        alt, src, title = m.group(1), m.group(2), m.group(3)
        src = link_map(src)
        t = f' title="{escape_attr(title)}"' if title else ""
        return stash(f'<img src="{escape_attr(src)}" alt="{escape_attr(alt)}"{t}>')

    text = _IMAGE.sub(img_sub, text)

    def link_sub(m):
        label, href, title = m.group(1), m.group(2), m.group(3)
        href = link_map(href)
        t = f' title="{escape_attr(title)}"' if title else ""
        # 只把 <a> 标签本身摘出来保护，label 留在主流里继续走行内标记 ——
        # 否则 [**Lyrico Plugin API**](url) 这种「链接文字带加粗」会漏渲染。
        return stash(f'<a href="{escape_attr(href)}"{t}>') + label + stash("</a>")

    text = _LINK.sub(link_sub, text)

    text = _AUTOLINK.sub(lambda m: stash(f'<a href="{escape_attr(m.group(1))}">{escape(m.group(1))}</a>'), text)

    # 转义反斜杠：`\*` 之类先还原
    text = text.replace("\\*", "\x01").replace("\\_", "\x02").replace("\\~", "\x03")

    text = escape(text)

    # 占位符不能被 escape 影响，这里只做剩余标记
    text = _STRONG.sub(lambda m: f"<strong>{m.group(1) or m.group(2)}</strong>", text)
    text = _EM.sub(lambda m: f"<em>{m.group(1) or m.group(2)}</em>", text)
    text = _DEL.sub(lambda m: f"<del>{m.group(1)}</del>", text)

    text = text.replace("\x01", "*").replace("\x02", "_").replace("\x03", "~")

    def unstash(m):
        return placeholders[int(m.group(1))]

    return re.sub(r"\x00(\d+)\x00", unstash, text)


# --- 块级 -------------------------------------------------------------------

_FENCE = re.compile(r"^(\s*)(`{3,}|~{3,})\s*([\w+-]*)\s*$")
_HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")
_HR = re.compile(r"^\s{0,3}([-*_])[ \t]*(?:\1[ \t]*){2,}$")
_BQ = re.compile(r"^\s{0,3}>\s?(.*)$")
_LIST_ITEM = re.compile(r"^(\s*)([-*+]|\d{1,9}[.)])[ \t]+(.*)$")
_TABLE_DELIM = re.compile(r"^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$")
_HTML_BLOCK = re.compile(r"^\s*<(!--|/?(?:div|p|table|thead|tbody|tr|td|th|img|br|hr|details|summary|ul|ol|li|h[1-6]|pre|blockquote|span|a|sup|sub|kbd|code)\b)", re.I)


def _split_table_row(row: str) -> list[str]:
    r"""按未转义 `|` 切分单元格，并把 `\|` 还原成 `|`（GFM 表格扩展语义）。"""
    row = row.strip()
    if row.startswith("|"):
        row = row[1:]
    if row.endswith("|") and not row.endswith("\\|"):
        row = row[:-1]
    cells = re.split(r"(?<!\\)\|", row)
    return [c.strip().replace("\\|", "|") for c in cells]


def _list_indent_unit(indent: str) -> int:
    """把列表项的缩进归一成'层级数'。宽松处理 tab = 4 空格。"""
    return len(indent.replace("\t", "    "))


def render_blocks(lines: list[str], link_map, toc: list, slug_seen: dict, depth: int = 0) -> str:
    out: list[str] = []
    i, n = 0, len(lines)

    while i < n:
        line = lines[i]

        if not line.strip():
            i += 1
            continue

        # HTML 注释 / 生成标记 —— 直接吞掉
        if line.lstrip().startswith("<!--"):
            while i < n and "-->" not in lines[i]:
                i += 1
            i += 1
            continue

        # 围栏代码
        m = _FENCE.match(line)
        if m:
            fence, lang = m.group(2), m.group(3)
            body: list[str] = []
            i += 1
            close = re.compile(r"^\s{0,3}" + re.escape(fence[0]) + "{" + str(len(fence)) + r",}\s*$")
            while i < n and not close.match(lines[i]):
                body.append(lines[i])
                i += 1
            i += 1  # 跳过收尾围栏
            cls = f' class="language-{escape_attr(lang)}"' if lang else ""
            out.append(f"<pre><code{cls}>{escape(chr(10).join(body))}\n</code></pre>")
            continue

        # ATX 标题
        m = _HEADING.match(line)
        if m:
            level, raw = len(m.group(1)), m.group(2)
            plain = _CODE_SPAN.sub(lambda x: x.group(2), raw)
            plain = _IMAGE.sub(lambda x: x.group(1), plain)
            plain = _LINK.sub(lambda x: x.group(1), plain)
            plain = _PUNCT_STRIP.sub("", plain.replace("*", "").replace("_", "").replace("~", ""))
            sid = slugify(plain, slug_seen)
            body_html = render_inline(raw, link_map)
            anchor = f'<a class="anchor" href="#{sid}" aria-hidden="true">#</a>'
            out.append(f'<h{level} id="{sid}">{body_html}{anchor}</h{level}>')
            if level >= 2:
                toc.append((level, sid, plain))
            i += 1
            continue

        # 分隔线
        if _HR.match(line):
            out.append("<hr>")
            i += 1
            continue

        # 引用块
        if _BQ.match(line):
            body = []
            while i < n and _BQ.match(lines[i]):
                body.append(_BQ.match(lines[i]).group(1))
                i += 1
            out.append("<blockquote>" + render_blocks(body, link_map, toc, slug_seen, depth + 1) + "</blockquote>")
            continue

        # GFM 表格：当前行含 `|` 且下一行是分隔行
        if "|" in line and i + 1 < n and _TABLE_DELIM.match(lines[i + 1]) and line.strip().startswith(("|", " ")):
            header = _split_table_row(line)
            aligns = []
            for cell in _split_table_row(lines[i + 1]):
                left, right = cell.startswith(":"), cell.endswith(":")
                aligns.append("center" if left and right else "right" if right else "left" if left else "")
            i += 2
            rows = []
            while i < n and lines[i].strip() and "|" in lines[i]:
                rows.append(_split_table_row(lines[i]))
                i += 1
            th = "".join(
                f'<th{f" class=ta-{a}" if a else ""}>{render_inline(c, link_map)}</th>'
                for a, c in zip(aligns + [""] * len(header), header)
            )
            body_rows = []
            for r in rows:
                r = r + [""] * (len(header) - len(r))
                tds = "".join(
                    f'<td{f" class=ta-{a}" if a else ""}>{render_inline(c, link_map)}</td>'
                    for a, c in zip(aligns + [""] * len(header), r[:len(header)])
                )
                body_rows.append(f"<tr>{tds}</tr>")
            out.append(
                '<div class="table-wrap"><table><thead><tr>'
                + th + "</tr></thead><tbody>" + "".join(body_rows) + "</tbody></table></div>"
            )
            continue

        # 列表（支持一层嵌套）
        lm = _LIST_ITEM.match(line)
        if lm and depth < 4:
            html_out, i = _render_list(lines, i, link_map, toc, slug_seen, depth)
            out.append(html_out)
            continue

        # HTML 块（少量，按原样透传）
        if _HTML_BLOCK.match(line):
            body = []
            while i < n and lines[i].strip():
                body.append(lines[i])
                i += 1
            out.append("\n".join(body))
            continue

        # 段落：吃到空行 / 下一个块级起始
        para = [line]
        i += 1
        while i < n and lines[i].strip():
            nxt = lines[i]
            if _FENCE.match(nxt) or _HEADING.match(nxt) or _HR.match(nxt) or _BQ.match(nxt) or _LIST_ITEM.match(nxt):
                break
            if "|" in nxt and i + 1 < n and _TABLE_DELIM.match(lines[i + 1]):
                break
            if nxt.lstrip().startswith("<!--"):
                break
            para.append(nxt)
            i += 1
        out.append("<p>" + render_inline("\n".join(para), link_map).replace("\n", "<br>") + "</p>")

    return "\n".join(out)


def _render_list(lines, start, link_map, toc, slug_seen, depth):
    first = _LIST_ITEM.match(lines[start])
    ordered = first.group(2)[0].isdigit()
    base = _list_indent_unit(first.group(1))
    items: list[list[str]] = []
    i, n = start, len(lines)

    while i < n:
        line = lines[i]
        if not line.strip():
            # 空行后若仍有同级/子级列表项则继续，否则结束
            j = i + 1
            while j < n and not lines[j].strip():
                j += 1
            if j < n and _LIST_ITEM.match(lines[j]) and _list_indent_unit(_LIST_ITEM.match(lines[j]).group(1)) >= base:
                i += 1
                continue
            if j < n and lines[j].startswith(" " * (base + 2)):
                i += 1
                continue
            break

        m = _LIST_ITEM.match(line)
        if m:
            ind = _list_indent_unit(m.group(1))
            if ind < base:
                break
            if ind == base:
                items.append([m.group(3)])
                i += 1
                continue
            # 更深一层 → 归入上一项
            if items:
                items[-1].append(line[base + 2:] if len(line) > base + 2 else line.strip())
                i += 1
                continue
            items.append([m.group(3)])
            i += 1
            continue

        # 续行（缩进文本）
        if line.startswith(" " * (base + 2)) and items:
            items[-1].append(line[base + 2:])
            i += 1
            continue
        if line.startswith("  ") and items:
            items[-1].append(line.lstrip())
            i += 1
            continue
        break

    tag = "ol" if ordered else "ul"
    rendered = []
    for it in items:
        head = it[0]
        rest = it[1:]
        if rest:
            # 递归渲染子内容（嵌套列表 / 续行段落）
            inner = render_blocks(rest, link_map, toc, slug_seen, depth + 1)
            rendered.append(f"<li>{render_inline(head, link_map)}{inner}</li>")
        else:
            rendered.append(f"<li>{render_inline(head, link_map)}</li>")
    return f"<{tag}>" + "".join(rendered) + f"</{tag}>", i


# ===========================================================================
# 链接改写
# ===========================================================================

class LinkMapper:
    """把 Markdown 源里的相对链接改写成站点内链接或 GitHub 外链。

    站点内链接一律是**相对当前页面**的（`types.html` 而不是 `sdk/types.html`），
    否则嵌套页面上会解析成 `sdk/sdk/types.html`。GitHub Pages 部署在
    `/<repo>/` 子路径下，站点根绝对路径 `/sdk/...` 同样会失效 —— 只能用相对路径。
    """

    def __init__(self, md_to_out: dict, current_md: Path, current_out: str):
        self.md_to_out = md_to_out
        self.current = current_md
        self.current_out = current_out

    @staticmethod
    def rel_href(from_out: str, to_out: str) -> str:
        base = os.path.dirname(from_out) or "."
        return Path(os.path.relpath(to_out, base)).as_posix()

    def __call__(self, href: str) -> str:
        if href.startswith(("http://", "https://", "mailto:", "data:", "javascript:")):
            return href
        if href.startswith("#"):
            return href

        path_part, _, frag = href.partition("#")
        if not path_part:
            return href

        # 仓库内源码路径 → GitHub
        target = (self.current.parent / path_part).resolve()
        try:
            rel = target.relative_to(REPO)
        except ValueError:
            return href

        key = rel.as_posix()
        for table in (self.md_to_out, EXTRA_MD_OUT):
            if key in table:
                out = self.rel_href(self.current_out, table[key])
                return out + ("#" + frag if frag else "")

        # 非 Markdown 的仓库内文件（源码、脚本）→ GitHub
        if rel.suffix.lower() in {".kt", ".kts", ".mjs", ".js", ".ts", ".json", ".ps1", ".sh", ".py", ".rs", ".toml", ".txt"} \
                or rel.is_dir() or "/" in key:
            gh = f"{REPO_URL}/blob/main/{key}" if rel.suffix else f"{REPO_URL}/tree/main/{key}"
            return gh + ("#" + frag if frag else "")

        # 剩下的 .md 目标（站内没有）→ GitHub 上的原文件
        return f"{REPO_URL}/blob/main/{key}" + ("#" + frag if frag else "")


# ===========================================================================
# 页面模板 / 样式 / 脚本
# ===========================================================================

def build_nav_html(current_out: str) -> str:
    parts = []
    for section, items in NAV:
        links = []
        for src, out, title in items:
            active = ' class="active"' if out == current_out else ""
            href = LinkMapper.rel_href(current_out, out)
            links.append(f'<li><a href="{escape_attr(href)}"{active}>{escape(title)}</a></li>')
        parts.append(f'<div class="nav-section"><div class="nav-title">{escape(section)}</div><ul>{"".join(links)}</ul></div>')
    return "\n".join(parts)


def page_html(title: str, body: str, toc: list, current_out: str, md_to_out: dict, depth: int) -> str:
    rel_root = "../" * depth
    toc_html = ""
    if toc:
        items = "".join(
            f'<li class="toc-l{lvl}"><a href="#{sid}">{escape(t)}</a></li>'
            for lvl, sid, t in toc
        )
        toc_html = f'<nav class="toc"><div class="toc-title">本页目录</div><ul>{items}</ul></nav>'

    nav = build_nav_html(current_out)

    return f"""<!DOCTYPE html>
<html lang="zh-CN" data-theme="light">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{escape(title)} · {escape(SITE_TITLE)}</title>
<meta name="description" content="CPPlayer —— 跨平台音乐播放器文档：使用指南、Provider 开发、SDK 参考、架构与发布。">
<link rel="stylesheet" href="{rel_root}assets/style.css">
<link rel="icon" href="data:image/svg+xml,{favicon_data_uri()}">
<script>
  // 首屏前定主题，避免闪白
  try {{
    var t = localStorage.getItem("cpdocs-theme");
    if (!t) t = matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
    document.documentElement.dataset.theme = t;
  }} catch (e) {{}}
</script>
</head>
<body>
<header class="topbar">
  <button class="icon-btn sidebar-toggle" id="sidebarToggle" aria-label="目录">☰</button>
  <a class="brand" href="{rel_root}index.html"><span class="brand-mark">♪</span> {escape(SITE_TITLE)}</a>
  <div class="search-box">
    <input type="search" id="searchInput" placeholder="搜索文档…" autocomplete="off" aria-label="搜索文档">
    <div class="search-results" id="searchResults" hidden></div>
  </div>
  <button class="icon-btn" id="themeToggle" aria-label="切换深浅色">◐</button>
  <a class="icon-btn gh" href="{REPO_URL}" target="_blank" rel="noopener" aria-label="GitHub">
    <svg viewBox="0 0 16 16" width="18" height="18" fill="currentColor" aria-hidden="true"><path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27s1.36.09 2 .27c1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8z"/></svg>
  </a>
</header>

<div class="layout">
  <aside class="sidebar" id="sidebar">
    {nav}
  </aside>
  <main class="content">
    <article class="markdown-body">
{body}
    </article>
    <footer class="page-footer">
      <span>源文档：<code>{escape(current_out)}</code> 由 <code>scripts/build-docs-site.py</code> 从 Markdown 生成</span>
      <a href="{PAGES_URL}">在线文档站</a>
    </footer>
  </main>
  {toc_html}
</div>

<div class="search-backdrop" id="searchBackdrop" hidden></div>
<script>window.__SITE_ROOT__ = "{rel_root}";</script>
<script src="{rel_root}assets/search-index.js"></script>
<script src="{rel_root}assets/app.js"></script>
</body>
</html>
"""


def favicon_data_uri() -> str:
    svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32"><rect width="32" height="32" rx="7" fill="#0969da"/><text x="16" y="23" font-size="20" text-anchor="middle" fill="#fff" font-family="sans-serif">♪</text></svg>'
    return "data:image/svg+xml," + svg.replace("<", "%3C").replace(">", "%3E").replace("#", "%23").replace('"', "'")


CSS = r"""
:root {
  --bg: #ffffff;
  --bg-soft: #f6f8fa;
  --bg-code: #f6f8fa;
  --text: #1f2328;
  --text-soft: #59636e;
  --border: #d1d9e0;
  --accent: #0969da;
  --accent-soft: #ddf4ff;
  --shadow: 0 1px 3px rgba(31,35,40,.08), 0 8px 24px rgba(31,35,40,.06);
  --radius: 10px;
  --topbar-h: 56px;
  --sidebar-w: 268px;
  --toc-w: 216px;
  --maxw: 1560px;
}
[data-theme="dark"] {
  --bg: #0d1117;
  --bg-soft: #161b22;
  --bg-code: #161b22;
  --text: #e6edf3;
  --text-soft: #9198a1;
  --border: #30363d;
  --accent: #4493f8;
  --accent-soft: #121d2f;
  --shadow: 0 1px 3px rgba(0,0,0,.4), 0 8px 24px rgba(0,0,0,.3);
}

* { box-sizing: border-box; }
html { scroll-behavior: smooth; scroll-padding-top: calc(var(--topbar-h) + 16px); }
body {
  margin: 0;
  background: var(--bg);
  color: var(--text);
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Noto Sans SC", "PingFang SC",
               "Hiragino Sans GB", "Microsoft YaHei", "Source Han Sans SC", sans-serif;
  font-size: 15.5px;
  line-height: 1.85;
  -webkit-font-smoothing: antialiased;
  word-break: normal;
  overflow-wrap: break-word;
  line-break: normal;
}

/* ---------- topbar ---------- */
.topbar {
  position: fixed; inset: 0 0 auto 0; height: var(--topbar-h); z-index: 50;
  display: flex; align-items: center; gap: 10px; padding: 0 16px;
  background: color-mix(in srgb, var(--bg) 88%, transparent);
  backdrop-filter: saturate(180%) blur(12px);
  border-bottom: 1px solid var(--border);
}
.brand {
  display: flex; align-items: center; gap: 8px;
  font-weight: 650; font-size: 16px; color: var(--text); text-decoration: none; white-space: nowrap;
}
.brand-mark {
  display: inline-grid; place-items: center; width: 26px; height: 26px; border-radius: 7px;
  background: var(--accent); color: #fff; font-size: 15px;
}
.icon-btn {
  display: inline-grid; place-items: center; width: 34px; height: 34px; flex: none;
  border: 1px solid transparent; border-radius: 8px; background: transparent;
  color: var(--text-soft); cursor: pointer; font-size: 16px; text-decoration: none;
}
.icon-btn:hover { background: var(--bg-soft); color: var(--text); border-color: var(--border); }
.icon-btn.gh { margin-left: auto; }
.topbar:has(.search-box) .icon-btn.gh { margin-left: 0; }
.sidebar-toggle { display: none; }

.search-box { position: relative; flex: 1; max-width: 420px; margin-left: auto; }
.search-box input {
  width: 100%; height: 34px; padding: 0 12px 0 34px; border-radius: 8px;
  border: 1px solid var(--border); background: var(--bg-soft); color: var(--text);
  font-size: 14px; font-family: inherit; outline: none;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16' fill='%23656d76'%3E%3Cpath d='M11.5 7a4.5 4.5 0 1 1-9 0 4.5 4.5 0 0 1 9 0zm-.82 4.39a6 6 0 1 1 1.06-1.06l3.04 3.04a.75.75 0 1 1-1.06 1.06l-3.04-3.04z'/%3E%3C/svg%3E");
  background-repeat: no-repeat; background-position: 10px center; background-size: 15px;
}
.search-box input:focus { border-color: var(--accent); background: var(--bg); }
.search-results {
  position: absolute; top: 42px; left: 0; right: 0; max-height: 60vh; overflow: auto;
  background: var(--bg); border: 1px solid var(--border); border-radius: var(--radius);
  box-shadow: var(--shadow); padding: 6px; z-index: 60;
}
.search-results a {
  display: block; padding: 8px 10px; border-radius: 7px; color: var(--text);
  text-decoration: none; font-size: 14px; line-height: 1.55;
}
.search-results a:hover, .search-results a.sel { background: var(--accent-soft); }
.search-results .sr-title { font-weight: 600; }
.search-results .sr-path { color: var(--text-soft); font-size: 12px; }
.search-results .sr-snip { color: var(--text-soft); font-size: 12.5px; margin-top: 2px; }
.search-results .sr-snip b { color: var(--accent); font-weight: 650; }
.search-empty { padding: 12px; color: var(--text-soft); font-size: 13.5px; }
.search-backdrop { position: fixed; inset: 0; z-index: 40; }

/* ---------- layout ---------- */
.layout {
  display: grid;
  grid-template-columns: var(--sidebar-w) minmax(0, 1fr) var(--toc-w);
  gap: 0;
  max-width: var(--maxw); margin: 0 auto;
  padding-top: var(--topbar-h);
}
.sidebar {
  position: sticky; top: var(--topbar-h); height: calc(100vh - var(--topbar-h));
  overflow-y: auto; overscroll-behavior: contain;
  padding: 18px 12px 40px 18px; border-right: 1px solid var(--border);
  background: var(--bg-soft);
}
.nav-section { margin-bottom: 20px; }
.nav-title {
  font-size: 11.5px; font-weight: 700; letter-spacing: .07em; text-transform: uppercase;
  color: var(--text-soft); padding: 0 10px 6px;
}
.sidebar ul { list-style: none; margin: 0; padding: 0; }
.sidebar li a {
  display: block; padding: 5px 10px; border-radius: 7px; color: var(--text);
  text-decoration: none; font-size: 13.8px; line-height: 1.55; border-left: 2px solid transparent;
}
.sidebar li a:hover { background: color-mix(in srgb, var(--accent) 9%, transparent); }
.sidebar li a.active {
  background: var(--accent-soft); color: var(--accent); font-weight: 620;
  border-left-color: var(--accent);
}

.content { min-width: 0; padding: 28px 44px 80px; }
.markdown-body { max-width: 880px; margin: 0 auto; }

.toc {
  position: sticky; top: calc(var(--topbar-h) + 24px); height: fit-content;
  max-height: calc(100vh - var(--topbar-h) - 48px); overflow-y: auto;
  padding: 28px 18px 40px 0; font-size: 13px;
}
.toc-title {
  font-size: 11.5px; font-weight: 700; letter-spacing: .07em; text-transform: uppercase;
  color: var(--text-soft); margin-bottom: 8px;
}
.toc ul { list-style: none; margin: 0; padding: 0; border-left: 1px solid var(--border); }
.toc li a {
  display: block; padding: 3px 0 3px 12px; margin-left: -1px; border-left: 2px solid transparent;
  color: var(--text-soft); text-decoration: none; line-height: 1.5;
}
.toc li a:hover { color: var(--accent); border-left-color: var(--accent); }
.toc li.toc-l3 a { padding-left: 24px; font-size: 12.4px; }

/* ---------- typography ---------- */
.markdown-body h1, .markdown-body h2, .markdown-body h3,
.markdown-body h4, .markdown-body h5, .markdown-body h6 {
  line-height: 1.35; font-weight: 680; margin: 1.7em 0 .65em; scroll-margin-top: calc(var(--topbar-h) + 18px);
}
.markdown-body h1 { font-size: 1.95em; margin-top: .2em; padding-bottom: .35em; border-bottom: 1px solid var(--border); }
.markdown-body h2 { font-size: 1.52em; padding-bottom: .3em; border-bottom: 1px solid var(--border); }
.markdown-body h3 { font-size: 1.22em; }
.markdown-body h4 { font-size: 1.06em; }
.markdown-body h5, .markdown-body h6 { font-size: 1em; color: var(--text-soft); }
.markdown-body > :first-child { margin-top: 0; }
.markdown-body p { margin: .85em 0; }
.markdown-body a { color: var(--accent); text-decoration: none; border-bottom: 1px solid transparent; }
.markdown-body a:hover { border-bottom-color: var(--accent); }
.anchor {
  opacity: 0; margin-left: .4em; font-weight: 400; color: var(--text-soft);
  text-decoration: none; border: 0 !important; font-size: .82em;
}
h1:hover .anchor, h2:hover .anchor, h3:hover .anchor,
h4:hover .anchor, h5:hover .anchor, h6:hover .anchor { opacity: 1; }

.markdown-body code {
  font-family: ui-monospace, "JetBrains Mono", "Cascadia Mono", Consolas, "SFMono-Regular",
               "Liberation Mono", Menlo, monospace;
  font-size: .875em; padding: .18em .42em; border-radius: 5px;
  background: var(--bg-code); border: 1px solid var(--border);
}
.markdown-body pre {
  background: var(--bg-code); border: 1px solid var(--border); border-radius: var(--radius);
  padding: 14px 16px; overflow-x: auto; margin: 1.1em 0; line-height: 1.62; font-size: 13.2px;
}
.markdown-body pre code {
  background: none; border: 0; padding: 0; font-size: inherit; color: var(--text);
}
.markdown-body blockquote {
  margin: 1.1em 0; padding: 2px 18px; border-left: 3px solid var(--accent);
  background: var(--bg-soft); border-radius: 0 var(--radius) var(--radius) 0; color: var(--text-soft);
}
.markdown-body blockquote p { margin: .55em 0; }
.markdown-body hr { border: 0; border-top: 1px solid var(--border); margin: 2em 0; }

.markdown-body ul, .markdown-body ol { padding-left: 1.55em; margin: .8em 0; }
.markdown-body li { margin: .32em 0; }
.markdown-body li > ul, .markdown-body li > ol { margin: .28em 0; }

.table-wrap { overflow-x: auto; margin: 1.15em 0; border: 1px solid var(--border); border-radius: var(--radius); }
.markdown-body table { border-collapse: collapse; width: 100%; font-size: 13.8px; line-height: 1.62; }
.markdown-body th, .markdown-body td {
  padding: 8px 12px; border-bottom: 1px solid var(--border); text-align: left; vertical-align: top;
}
.markdown-body th {
  background: var(--bg-soft); font-weight: 650; white-space: nowrap;
  position: sticky; top: 0;
}
.markdown-body tr:last-child td { border-bottom: 0; }
.markdown-body tbody tr:hover { background: color-mix(in srgb, var(--accent) 5%, transparent); }
.ta-left { text-align: left; } .ta-right { text-align: right; } .ta-center { text-align: center; }

.markdown-body img { max-width: 100%; border-radius: var(--radius); }
.markdown-body del { color: var(--text-soft); }

.page-footer {
  max-width: 880px; margin: 56px auto 0; padding-top: 18px; border-top: 1px solid var(--border);
  display: flex; flex-wrap: wrap; gap: 12px; justify-content: space-between;
  color: var(--text-soft); font-size: 12.5px;
}
.page-footer code { font-size: 11.8px; }

/* ---------- responsive ---------- */
@media (max-width: 1180px) {
  .layout { grid-template-columns: var(--sidebar-w) minmax(0, 1fr); }
  .toc { display: none; }
}
@media (max-width: 860px) {
  .layout { grid-template-columns: minmax(0, 1fr); }
  .sidebar {
    position: fixed; top: var(--topbar-h); left: 0; bottom: 0; width: min(84vw, 320px);
    transform: translateX(-102%); transition: transform .22s ease; z-index: 45;
    box-shadow: var(--shadow);
  }
  .sidebar.open { transform: translateX(0); }
  .sidebar-toggle { display: inline-grid; }
  .content { padding: 20px 18px 64px; }
  .search-box { max-width: none; }
  .brand { font-size: 15px; }
  .icon-btn.gh { display: none; }
}
@media (prefers-reduced-motion: reduce) {
  html { scroll-behavior: auto; }
  .sidebar { transition: none; }
}
"""

JS = r"""
(function () {
  "use strict";

  /* ---- theme ---- */
  var root = document.documentElement;
  var themeBtn = document.getElementById("themeToggle");
  function applyTheme(t) {
    root.dataset.theme = t;
    try { localStorage.setItem("cpdocs-theme", t); } catch (e) {}
    if (themeBtn) themeBtn.textContent = t === "dark" ? "☀" : "◐";
  }
  applyTheme(root.dataset.theme || "light");
  if (themeBtn) {
    themeBtn.addEventListener("click", function () {
      applyTheme(root.dataset.theme === "dark" ? "light" : "dark");
    });
  }

  /* ---- mobile sidebar ---- */
  var sidebar = document.getElementById("sidebar");
  var toggle = document.getElementById("sidebarToggle");
  if (toggle && sidebar) {
    toggle.addEventListener("click", function () {
      sidebar.classList.toggle("open");
    });
    document.addEventListener("click", function (e) {
      if (!sidebar.classList.contains("open")) return;
      if (sidebar.contains(e.target) || toggle.contains(e.target)) return;
      sidebar.classList.remove("open");
    });
  }

  /* ---- search ---- */
  var input = document.getElementById("searchInput");
  var box = document.getElementById("searchResults");
  var backdrop = document.getElementById("searchBackdrop");
  var index = window.__SEARCH_INDEX__ || [];
  var ROOT = window.__SITE_ROOT__ || "";
  var sel = -1;

  function resolveUrl(u) {
    if (!u) return u;
    if (u.charAt(0) === "#" || /^(https?:|mailto:|data:)/.test(u)) return u;
    return ROOT + u;
  }

  function close() {
    if (box) box.hidden = true;
    if (backdrop) backdrop.hidden = true;
    sel = -1;
  }

  function score(entry, q) {
    var s = 0, t = entry.t.toLowerCase(), p = entry.p.toLowerCase();
    if (t === q) s += 100;
    if (t.indexOf(q) === 0) s += 60;
    if (t.indexOf(q) >= 0) s += 34;
    if (p.indexOf(q) >= 0) s += 12;
    var body = entry.b.toLowerCase(), pos = body.indexOf(q);
    if (pos >= 0) s += 14 - Math.min(10, pos / 40);
    return s;
  }

  function snippet(body, q) {
    var pos = body.toLowerCase().indexOf(q);
    if (pos < 0) return "";
    var start = Math.max(0, pos - 32), end = Math.min(body.length, pos + q.length + 52);
    var s = (start > 0 ? "…" : "") + body.slice(start, end) + (end < body.length ? "…" : "");
    return s.replace(/[<>&]/g, function (c) {
      return { "<": "&lt;", ">": "&gt;", "&": "&amp;" }[c];
    }).replace(new RegExp("(" + q.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + ")", "ig"), "<b>$1</b>");
  }

  function render(q) {
    if (!box) return;
    if (!q) { close(); return; }
    var ql = q.toLowerCase();
    var hits = [];
    for (var i = 0; i < index.length; i++) {
      var s = score(index[i], ql);
      if (s > 0) hits.push({ e: index[i], s: s });
    }
    hits.sort(function (a, b) { return b.s - a.s; });
    hits = hits.slice(0, 14);
    if (!hits.length) {
      box.innerHTML = '<div class="search-empty">没有匹配「' + q.replace(/[<>&]/g, "") + '」的结果</div>';
    } else {
      var html = "";
      for (var j = 0; j < hits.length; j++) {
        var e = hits[j].e;
        html += '<a href="' + resolveUrl(e.u) + '">' +
          '<div class="sr-title">' + e.t.replace(/[<>&]/g, "") + '</div>' +
          '<div class="sr-path">' + e.s.replace(/[<>&]/g, "") + '</div>' +
          (e.b ? '<div class="sr-snip">' + snippet(e.b, ql) + '</div>' : "") +
          "</a>";
      }
      box.innerHTML = html;
    }
    box.hidden = false;
    if (backdrop) backdrop.hidden = false;
    sel = -1;
  }

  if (input) {
    input.addEventListener("input", function () { render(input.value.trim()); });
    input.addEventListener("focus", function () { if (input.value.trim()) render(input.value.trim()); });
    input.addEventListener("keydown", function (e) {
      var links = box && !box.hidden ? box.querySelectorAll("a") : [];
      if (e.key === "Escape") { close(); input.blur(); return; }
      if (e.key === "ArrowDown" || e.key === "ArrowUp") {
        if (!links.length) return;
        e.preventDefault();
        sel = e.key === "ArrowDown" ? (sel + 1) % links.length : (sel - 1 + links.length) % links.length;
        for (var i = 0; i < links.length; i++) links[i].classList.toggle("sel", i === sel);
        links[sel].scrollIntoView({ block: "nearest" });
        return;
      }
      if (e.key === "Enter") {
        var target = links[sel >= 0 ? sel : 0];
        if (target) { e.preventDefault(); location.href = target.getAttribute("href"); }
      }
    });
  }
  if (backdrop) backdrop.addEventListener("click", close);

  /* ---- toc scroll-spy ---- */
  var tocLinks = Array.prototype.slice.call(document.querySelectorAll(".toc a"));
  if (tocLinks.length && "IntersectionObserver" in window) {
    var map = {};
    tocLinks.forEach(function (a) { map[a.getAttribute("href").slice(1)] = a; });
    var current = null;
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (!en.isIntersecting) return;
        if (current) current.classList.remove("on");
        var a = map[en.target.id];
        if (a) { a.classList.add("on"); current = a; }
      });
    }, { rootMargin: "-72px 0px -70% 0px", threshold: 0 });
    Object.keys(map).forEach(function (id) {
      var el = document.getElementById(id);
      if (el) io.observe(el);
    });
  }
})();
"""


# ===========================================================================
# 构建
# ===========================================================================

def plain_text(md: str) -> str:
    """剥掉 Markdown 标记，供搜索索引用。"""
    t = md
    t = re.sub(r"<!--.*?-->", " ", t, flags=re.DOTALL)
    t = re.sub(r"```.*?```", " ", t, flags=re.DOTALL)
    t = re.sub(r"`[^`]*`", " ", t)
    t = re.sub(r"!\[[^\]]*\]\([^)]*\)", " ", t)
    t = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", t)
    t = re.sub(r"^\s{0,3}\|", " ", t, flags=re.M)
    t = re.sub(r"[#*_~>`]", " ", t)
    t = re.sub(r"\s+", " ", t)
    return t.strip()


def collect_search_text(md: str, limit: int = 2600) -> str:
    return plain_text(md)[:limit]


def main() -> int:
    ap = argparse.ArgumentParser(description="Build the CPPlayer docs site.")
    ap.add_argument("--out", default=str(REPO / "site"), help="output directory (default: <repo>/site)")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    out_dir = Path(args.out).resolve()
    log = (lambda *a: None) if args.quiet else (lambda *a: print(*a))

    # --- 校验导航里每个源文件都存在 ---
    missing = [src for _, items in NAV for src, _, _ in items if not (REPO / src).is_file()]
    if missing:
        for m in missing:
            print(f"error: 导航引用的源文件不存在: {m}", file=sys.stderr)
        return 1

    md_to_out = {src: out for _, items in NAV for src, out, _ in items}
    md_to_out.update({k: v for k, v in EXTRA_MD_OUT.items() if (REPO / k).is_file()})
    out_to_title = {out: title for _, items in NAV for _, out, title in items}

    if out_dir.exists():
        shutil.rmtree(out_dir)
    (out_dir / "assets").mkdir(parents=True, exist_ok=True)

    # --- 静态资源 ---
    (out_dir / "assets" / "style.css").write_text(CSS, encoding="utf-8")
    (out_dir / "assets" / "app.js").write_text(JS, encoding="utf-8")
    for rel in ASSET_FILES:
        src = REPO / rel
        if src.is_file():
            shutil.copy2(src, out_dir / "assets" / src.name)
            log(f"  asset  {rel}")

    # --- 逐页渲染 ---
    search_entries = []
    for _, items in NAV:
        for src, out, title in items:
            raw = (REPO / src).read_text(encoding="utf-8")
            mapper = LinkMapper(md_to_out, REPO / src, out)
            toc: list = []
            slug_seen: dict = {}
            body = render_blocks(raw.split("\n"), mapper, toc, slug_seen)
            depth = out.count("/")
            page = page_html(title, body, toc, out, md_to_out, depth)

            dest = out_dir / out
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_text(page, encoding="utf-8")
            log(f"  page   {src}  ->  {out}  ({len(raw)} chars)")

            # 搜索索引里的 URL 一律是**站点根相对**（`sdk/types.html`），
            # 由 app.js 按 window.__SITE_ROOT__ 加前缀 —— 否则同一份索引
            # 在不同深度的页面上点击会错位。
            search_entries.append({
                "t": title,
                "u": out,
                "p": out,
                "b": collect_search_text(raw),
            })
            # 每个二级标题也进索引，便于直接跳节
            for lvl, sid, txt in toc:
                if lvl <= 3 and len(txt) >= 2:
                    search_entries.append({
                        "t": txt,
                        "u": out + "#" + sid,
                        "p": out + " · " + title,
                        "b": "",
                    })

    # --- 搜索索引单独成文件：浏览器跨页复用缓存，不往每个 HTML 里塞一遍 ---
    idx_json = json.dumps(search_entries, ensure_ascii=False, separators=(",", ":"))
    (out_dir / "assets" / "search-index.js").write_text(
        "window.__SEARCH_INDEX__ = " + idx_json + ";\n", encoding="utf-8"
    )

    # --- 404 ---
    (out_dir / "404.html").write_text(
        page_html("页面不存在",
                  "<h1>404</h1><p>页面不存在。<a href=\"index.html\">回到首页</a></p>",
                  [], "index.html", md_to_out, 0),
        encoding="utf-8",
    )

    log(f"\n完成：{len(search_entries)} 条搜索索引，输出 {out_dir}")
    log(f"本地预览：直接打开 {out_dir / 'index.html'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
